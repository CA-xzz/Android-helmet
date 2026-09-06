package com.example.helmet.communication.sync

import android.content.Context
import android.security.KeyChain
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence

enum class MqttConnectionState {
    CONNECTING,
    READY,
    DISCONNECTED,
    MESSAGE_REJECTED,
    PROCESSING_ERROR,
    ERROR,
    STOPPED,
}

data class MqttConnectionStatus(
    val state: MqttConnectionState,
    val detail: String? = null,
)

internal enum class MqttInboundKind {
    COMMAND_WAKE,
    APPLICATION_RECEIPT,
}

internal fun classifyMqttInbound(
    topic: String,
    deviceId: String,
    qos: Int,
    retained: Boolean,
): MqttInboundKind {
    if (qos != 1 || retained) {
        throw MqttProtocolException("MQTT delivery attributes are invalid")
    }
    return when {
        topic == MqttDeviceProtocol.commandTopic(deviceId) -> MqttInboundKind.COMMAND_WAKE
        topic.startsWith("helmet/v1/devices/$deviceId/down/result/") ->
            MqttInboundKind.APPLICATION_RECEIPT
        else -> throw MqttProtocolException("MQTT downlink topic is unsupported")
    }
}

internal suspend fun processMqttIncomingDelivery(
    topic: String,
    payload: ByteArray,
    qos: Int,
    retained: Boolean,
    deviceId: String,
    onCommandWake: suspend () -> Unit,
    onApplicationReceipt: (MqttApplicationReceipt) -> Unit,
) {
    when (classifyMqttInbound(topic, deviceId, qos, retained)) {
        MqttInboundKind.COMMAND_WAKE -> onCommandWake()
        MqttInboundKind.APPLICATION_RECEIPT -> onApplicationReceipt(
            MqttDeviceProtocol.parseApplicationReceipt(topic, payload, deviceId),
        )
    }
}

internal fun shouldAcknowledgeOversizedMqttDelivery(qos: Int): Boolean = qos == 1

internal fun isValidMqttCertificateAlias(alias: String): Boolean =
    alias.matches(Regex("^[A-Za-z0-9._:-]{1,256}$"))

internal const val MAX_BUFFERED_MQTT_INCOMING_MESSAGES = 16
internal const val MAX_MQTT_INCOMING_PAYLOAD_BYTES = 64 * 1_024

internal enum class MqttIncomingAdmission {
    ACCEPTED,
    QUEUE_FULL,
    OVERSIZED_ACKNOWLEDGED,
    OVERSIZED_UNACKNOWLEDGED,
}

internal fun admitMqttIncomingPayload(
    commandWake: Boolean,
    payload: ByteArray,
    qos: Int,
    offer: (ByteArray) -> Boolean,
): MqttIncomingAdmission {
    if (!commandWake && payload.size > MAX_MQTT_INCOMING_PAYLOAD_BYTES) {
        return if (shouldAcknowledgeOversizedMqttDelivery(qos)) {
            MqttIncomingAdmission.OVERSIZED_ACKNOWLEDGED
        } else {
            MqttIncomingAdmission.OVERSIZED_UNACKNOWLEDGED
        }
    }
    val bufferedPayload = if (commandWake) EMPTY_MQTT_PAYLOAD else payload.copyOf()
    return if (offer(bufferedPayload)) {
        MqttIncomingAdmission.ACCEPTED
    } else {
        MqttIncomingAdmission.QUEUE_FULL
    }
}

private val EMPTY_MQTT_PAYLOAD = ByteArray(0)

object MqttDeviceSessionRegistry {
    @Volatile
    private var active: MqttDeviceSession? = null

    @Synchronized
    fun register(session: MqttDeviceSession) {
        check(active == null || active === session) { "another MQTT device session is already registered" }
        active = session
    }

    @Synchronized
    fun unregister(session: MqttDeviceSession) {
        if (active === session) active = null
    }

    fun ready(deviceId: String): MqttDeviceSession? = active?.takeIf {
        it.deviceId == deviceId && it.isReady
    }
}

class MqttDeviceSession(
    context: Context,
    brokerUri: String,
    val deviceId: String,
    private val certificateAlias: String,
    parentScope: CoroutineScope,
    private val onCommandWake: suspend () -> Unit,
    private val onReady: suspend () -> Unit,
    private val onStatus: (MqttConnectionStatus) -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val endpoint = MqttDeviceProtocol.normalizeBrokerUri(brokerUri)
    private val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
    private val incoming = Channel<IncomingMessage>(MAX_BUFFERED_MQTT_INCOMING_MESSAGES)
    private val pendingReceipts = ConcurrentHashMap<String, CompletableDeferred<MqttApplicationReceipt>>()
    private val ready = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val initialReady = CompletableDeferred<Unit>()
    private val terminalFailure = CompletableDeferred<Throwable>()
    private val persistenceDirectory = File(applicationContext.noBackupFilesDir, "mqtt-persistence")
    private lateinit var client: MqttAsyncClient

    val isReady: Boolean
        get() = ready.get() && ::client.isInitialized && client.isConnected

    init {
        require(isValidMqttCertificateAlias(certificateAlias)) {
            "MQTT certificate alias is invalid"
        }
        MqttDeviceProtocol.commandTopic(deviceId)
        scope.launch(Dispatchers.IO) { processIncomingMessages() }
    }

    suspend fun start() = withContext(Dispatchers.IO) {
        check(!closed.get()) { "MQTT session is closed" }
        onStatus(MqttConnectionStatus(MqttConnectionState.CONNECTING))
        check(persistenceDirectory.isDirectory || persistenceDirectory.mkdirs()) {
            "failed to create MQTT persistence directory"
        }
        val socketFactory = AndroidMqttTls.createSocketFactory(
            applicationContext,
            certificateAlias,
            deviceId,
        )
        client = MqttAsyncClient(
            endpoint,
            deviceId,
            MqttDefaultFilePersistence(persistenceDirectory.absolutePath),
        )
        client.setManualAcks(true)
        client.setCallback(callback)
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            mqttVersion = MqttConnectOptions.MQTT_VERSION_3_1_1
            keepAliveInterval = KEEP_ALIVE_SECONDS
            connectionTimeout = CONNECT_TIMEOUT_SECONDS
            maxInflight = MAX_INFLIGHT
            isHttpsHostnameVerificationEnabled = true
            setSocketFactory(socketFactory)
        }
        try {
            client.connect(options).waitForCompletion(CONNECT_TIMEOUT_MILLIS)
            withTimeout(SUBSCRIBE_TIMEOUT_MILLIS) { initialReady.await() }
        } catch (error: Throwable) {
            ready.set(false)
            initialReady.completeExceptionally(error)
            onStatus(MqttConnectionStatus(MqttConnectionState.ERROR, safeDetail(error)))
            throw error
        }
    }

    suspend fun synchronizeCommands(afterSequence: Long) {
        val uplink = MqttDeviceProtocol.commandSync(deviceId, afterSequence, wallClock())
        publishAndAwait(uplink)
    }

    suspend fun awaitTermination(): Nothing {
        throw terminalFailure.await()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        ready.set(false)
        incoming.close()
        terminalFailure.complete(CancellationException("MQTT session closed"))
        pendingReceipts.values.forEach { it.cancel() }
        pendingReceipts.clear()
        if (::client.isInitialized) {
            runCatching {
                if (client.isConnected) {
                    client.disconnectForcibly(
                        DISCONNECT_TIMEOUT_MILLIS,
                        DISCONNECT_QUIESCE_MILLIS,
                        true,
                    )
                }
            }
            runCatching { client.close() }
        }
        scope.cancel()
        onStatus(MqttConnectionStatus(MqttConnectionState.STOPPED))
    }

    private suspend fun publishAndAwait(uplink: MqttUplink): MqttApplicationReceipt {
        if (!isReady) throw CommunicationException("MQTT device session is not ready", retryable = true)
        val deferred = CompletableDeferred<MqttApplicationReceipt>()
        check(pendingReceipts.putIfAbsent(uplink.messageId, deferred) == null) {
            "MQTT request is already pending"
        }
        return try {
            withContext(Dispatchers.IO) {
                client.publish(uplink.topic, uplink.payload, 1, false)
                    .waitForCompletion(PUBLISH_TIMEOUT_MILLIS)
            }
            val receipt = withTimeout(APPLICATION_RECEIPT_TIMEOUT_MILLIS) { deferred.await() }
            if (!receipt.accepted) {
                val status = receipt.status
                throw CommunicationException(
                    "MQTT application request rejected: ${receipt.error}",
                    retryable = status == 408 || status == 429 || (status != null && status >= 500),
                    statusCode = status,
                )
            }
            receipt
        } catch (error: CommunicationException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw CommunicationException("MQTT request failed", retryable = true, cause = error)
        } finally {
            pendingReceipts.remove(uplink.messageId, deferred)
        }
    }

    private val callback = object : MqttCallbackExtended {
        override fun connectComplete(reconnect: Boolean, serverURI: String?) {
            if (closed.get()) return
            ready.set(false)
            val filter = "helmet/v1/devices/$deviceId/down/#"
            try {
                client.subscribe(filter, 1, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken) {
                    val granted = asyncActionToken.grantedQos
                    val rejected = granted == null || granted.isEmpty() || granted.any { it != 1 }
                    if (rejected) {
                        handleSubscriptionFailure(IllegalStateException("MQTT subscription rejected"))
                        return
                    }
                    ready.set(true)
                    initialReady.complete(Unit)
                    onStatus(MqttConnectionStatus(MqttConnectionState.READY))
                    scope.launch(Dispatchers.IO) { onReady() }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable) {
                    handleSubscriptionFailure(exception)
                }
                })
            } catch (error: Throwable) {
                handleSubscriptionFailure(error)
            }
        }

        override fun connectionLost(cause: Throwable?) {
            ready.set(false)
            onStatus(MqttConnectionStatus(MqttConnectionState.DISCONNECTED, cause?.let(::safeDetail)))
        }

        override fun messageArrived(topic: String, message: MqttMessage) {
            val commandWake = topic == MqttDeviceProtocol.commandTopic(deviceId)
            when (
                admitMqttIncomingPayload(
                    commandWake = commandWake,
                    payload = message.payload,
                    qos = message.qos,
                ) { payload ->
                    incoming.trySend(
                        IncomingMessage(
                            topic = topic,
                            payload = payload,
                            qos = message.qos,
                            retained = message.isRetained,
                            messageId = message.id,
                        ),
                    ).isSuccess
                }
            ) {
                MqttIncomingAdmission.ACCEPTED -> Unit
                MqttIncomingAdmission.QUEUE_FULL -> onStatus(
                    MqttConnectionStatus(MqttConnectionState.PROCESSING_ERROR, "MQTT_WAKE_QUEUE_FULL"),
                )
                MqttIncomingAdmission.OVERSIZED_ACKNOWLEDGED -> {
                    onStatus(MqttConnectionStatus(MqttConnectionState.MESSAGE_REJECTED, "MQTT_PAYLOAD_TOO_LARGE"))
                    runCatching { client.messageArrivedComplete(message.id, message.qos) }
                }
                MqttIncomingAdmission.OVERSIZED_UNACKNOWLEDGED -> onStatus(
                    MqttConnectionStatus(MqttConnectionState.MESSAGE_REJECTED, "MQTT_PAYLOAD_TOO_LARGE"),
                )
            }
        }

        override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
    }

    private fun handleSubscriptionFailure(error: Throwable) {
        ready.set(false)
        initialReady.completeExceptionally(error)
        terminalFailure.complete(error)
        onStatus(MqttConnectionStatus(MqttConnectionState.ERROR, safeDetail(error)))
        runCatching { client.disconnectForcibly() }
    }

    private suspend fun processIncomingMessages() {
        for (message in incoming) {
            var acknowledge = false
            try {
                processMqttIncomingDelivery(
                    topic = message.topic,
                    payload = message.payload,
                    qos = message.qos,
                    retained = message.retained,
                    deviceId = deviceId,
                    onCommandWake = onCommandWake,
                    onApplicationReceipt = { receipt ->
                        pendingReceipts[receipt.messageId]?.complete(receipt)
                    },
                )
                acknowledge = true
            } catch (error: MqttProtocolException) {
                acknowledge = true
                onStatus(MqttConnectionStatus(MqttConnectionState.MESSAGE_REJECTED, safeDetail(error)))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                onStatus(MqttConnectionStatus(MqttConnectionState.PROCESSING_ERROR, safeDetail(error)))
            }
            if (acknowledge && ::client.isInitialized) {
                runCatching { client.messageArrivedComplete(message.messageId, message.qos) }
            }
        }
    }

    private data class IncomingMessage(
        val topic: String,
        val payload: ByteArray,
        val qos: Int,
        val retained: Boolean,
        val messageId: Int,
    )

    companion object {
        private const val KEEP_ALIVE_SECONDS = 60
        private const val CONNECT_TIMEOUT_SECONDS = 20
        private const val MAX_INFLIGHT = 20
        private const val CONNECT_TIMEOUT_MILLIS = 25_000L
        private const val SUBSCRIBE_TIMEOUT_MILLIS = 20_000L
        private const val PUBLISH_TIMEOUT_MILLIS = 20_000L
        private const val APPLICATION_RECEIPT_TIMEOUT_MILLIS = 30_000L
        private const val DISCONNECT_TIMEOUT_MILLIS = 500L
        private const val DISCONNECT_QUIESCE_MILLIS = 500L
        private fun safeDetail(error: Throwable): String = buildString {
            append(error.javaClass.name)
            error.cause?.let { cause -> append(" cause=").append(cause.javaClass.name) }
        }.take(512)
    }
}

internal object AndroidMqttTls {
    fun createSocketFactory(context: Context, alias: String, expectedDeviceId: String): SSLSocketFactory {
        val privateKey = requireNotNull(KeyChain.getPrivateKey(context, alias)) {
            "MQTT private key alias is unavailable to the application"
        }
        val chain = requireNotNull(KeyChain.getCertificateChain(context, alias)) {
            "MQTT certificate chain is unavailable to the application"
        }
        require(chain.size >= 2) { "MQTT certificate chain must include its private CA root" }
        chain.forEach(X509Certificate::checkValidity)
        for (index in 0 until chain.lastIndex) chain[index].verify(chain[index + 1].publicKey)
        val leaf = chain.first()
        require(MqttCertificateIdentity.commonName(leaf.subjectX500Principal.name) == expectedDeviceId) {
            "MQTT client certificate CN does not match the device ID"
        }
        require(privateKey.algorithm.equals(leaf.publicKey.algorithm, ignoreCase = true)) {
            "MQTT private key algorithm does not match its certificate"
        }
        leaf.extendedKeyUsage?.let { usages ->
            require(CLIENT_AUTH_OID in usages) { "MQTT certificate does not permit client authentication" }
        }
        val root = chain.last()
        require(root.basicConstraints >= 0) { "MQTT trust anchor is not a CA certificate" }
        root.verify(root.publicKey)

        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("mqtt-private-ca", root)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(trustStore)
            trustManagers
        }
        val sslContext = SSLContext.getInstance("TLSv1.2").apply {
            init(
                arrayOf<KeyManager>(SingleAliasKeyManager(alias, privateKey, chain)),
                trustManagers,
                SecureRandom(),
            )
        }
        return HostnameVerifyingSocketFactory(sslContext.socketFactory)
    }

    private const val CLIENT_AUTH_OID = "1.3.6.1.5.5.7.3.2"
}

internal object MqttCertificateIdentity {
    fun commonName(rfc2253Name: String): String? = splitRdns(rfc2253Name)
        .firstOrNull { it.regionMatches(0, "CN=", 0, 3, ignoreCase = true) }
        ?.substring(3)
        ?.takeIf { it.matches(Regex("^[A-Za-z0-9._:-]{1,128}$")) }

    private fun splitRdns(value: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        value.forEach { character ->
            when {
                escaped -> {
                    current.append(character)
                    escaped = false
                }
                character == '\\' -> escaped = true
                character == ',' -> {
                    parts += current.toString().trim()
                    current.clear()
                }
                else -> current.append(character)
            }
        }
        if (escaped) current.append('\\')
        parts += current.toString().trim()
        return parts
    }
}

private class SingleAliasKeyManager(
    private val alias: String,
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        if (matches(keyType)) arrayOf(alias) else emptyArray()

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = alias.takeIf { keyType.orEmpty().any(::matches) }

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = alias.takeIf { keyType.orEmpty().any(::matches) }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
        emptyArray()

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = chain.takeIf { alias == this.alias }
    override fun getPrivateKey(alias: String?): PrivateKey? = privateKey.takeIf { alias == this.alias }

    private fun matches(keyType: String?): Boolean =
        keyType != null && keyType.substringBefore('_').equals(privateKey.algorithm, ignoreCase = true)
}

private class HostnameVerifyingSocketFactory(
    private val delegate: SSLSocketFactory,
) : SSLSocketFactory() {
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = configure(delegate.createSocket())
    override fun createSocket(socket: Socket, host: String, port: Int, autoClose: Boolean): Socket =
        configure(delegate.createSocket(socket, host, port, autoClose))

    override fun createSocket(host: String, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(
        host: String,
        port: Int,
        localHost: InetAddress,
        localPort: Int,
    ): Socket = configure(delegate.createSocket(host, port, localHost, localPort))

    override fun createSocket(host: InetAddress, port: Int): Socket = configure(delegate.createSocket(host, port))
    override fun createSocket(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress,
        localPort: Int,
    ): Socket = configure(delegate.createSocket(address, port, localAddress, localPort))

    private fun configure(socket: Socket): Socket = socket.also { value ->
        if (value is SSLSocket) {
            value.enabledProtocols = value.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
            value.sslParameters = value.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
    }
}
