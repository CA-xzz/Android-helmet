package com.example.helmet.service.runtime

import com.example.helmet.core.protocol.Rtcm3StreamDecoder
import com.example.helmet.feature.location.NmeaParser
import com.example.helmet.feature.location.NmeaSentence
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class NtripEndpoint(
    val secure: Boolean,
    val host: String,
    val port: Int,
    val mountPoint: String,
) {
    val authority: String
        get() = if (port == if (secure) 443 else 80) host else "$host:$port"

    val redactedDescription: String
        get() = "${if (secure) "https" else "http"}://$authority$mountPoint"

    companion object {
        fun parse(raw: String): NtripEndpoint {
            require(raw.length <= 2_048 && '\r' !in raw && '\n' !in raw) { "invalid NTRIP URL" }
            val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("invalid NTRIP URL", it) }
            val scheme = uri.scheme?.lowercase(Locale.US)
            require(scheme in setOf("https", "http")) { "NTRIP URL must use HTTPS" }
            require(uri.rawUserInfo == null) { "NTRIP credentials must not be embedded in the URL" }
            require(uri.rawQuery == null && uri.rawFragment == null) { "NTRIP URL must not contain query or fragment" }
            val host = uri.host?.lowercase(Locale.US).orEmpty()
            require(host.isNotBlank()) { "NTRIP URL must contain a host" }
            val secure = scheme == "https"
            require(secure || isLoopbackHost(host)) { "cleartext NTRIP is allowed only on loopback" }
            val port = if (uri.port >= 0) uri.port else if (secure) 443 else 80
            require(port in 1..65_535) { "invalid NTRIP port" }
            val mountPoint = uri.rawPath.orEmpty()
            require(mountPoint.length in 2..512 && mountPoint.startsWith('/')) {
                "NTRIP URL must contain a mount point"
            }
            return NtripEndpoint(secure, host, port, mountPoint)
        }

        private fun isLoopbackHost(host: String): Boolean {
            if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1") return true
            val octets = host.split('.')
            return octets.size == 4 && octets.firstOrNull() == "127" && octets.all { octet ->
                octet.toIntOrNull() in 0..255
            }
        }
    }
}

data class NtripResponse(
    val statusCode: Int,
    val statusLine: String,
    val headers: Map<String, String>,
)

data class NtripSessionResult(
    val correctionFrames: Long,
    val correctionBytes: Long,
    val decoderCrcErrors: Long,
)

class NtripProtocolException(
    message: String,
    val retryable: Boolean,
) : IOException(message)

internal object NtripWireProtocol {
    fun buildRequest(
        endpoint: NtripEndpoint,
        username: String,
        password: String,
        initialGga: String?,
    ): ByteArray {
        require(username.isNotBlank() && username.length <= 256 && ':' !in username)
        require(password.isNotBlank() && password.length <= 512)
        require('\r' !in username && '\n' !in username && '\r' !in password && '\n' !in password)
        val authorization = Base64.getEncoder().encodeToString(
            "$username:$password".toByteArray(StandardCharsets.UTF_8),
        )
        val normalizedGga = initialGga?.let(::normalizeGga)
        val request = buildString {
            append("GET ${endpoint.mountPoint} HTTP/1.1\r\n")
            append("Host: ${endpoint.authority}\r\n")
            append("User-Agent: NTRIP HelmetAndroid/1.0\r\n")
            append("Ntrip-Version: Ntrip/2.0\r\n")
            append("Accept: gnss/data, application/octet-stream, */*\r\n")
            append("Authorization: Basic $authorization\r\n")
            if (normalizedGga != null) append("Ntrip-GGA: $normalizedGga\r\n")
            append("Connection: close\r\n\r\n")
        }
        return request.toByteArray(StandardCharsets.ISO_8859_1)
    }

    fun readResponse(input: BufferedInputStream): NtripResponse {
        val output = ByteArrayOutputStream()
        var matched = 0
        while (output.size() < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw EOFException("NTRIP response ended before headers")
            output.write(value)
            matched = when {
                matched == 0 && value == '\r'.code -> 1
                matched == 1 && value == '\n'.code -> 2
                matched == 2 && value == '\r'.code -> 3
                matched == 3 && value == '\n'.code -> 4
                value == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) break
        }
        if (matched != 4) throw NtripProtocolException("NTRIP response headers exceed limit", retryable = false)
        val lines = output.toString(StandardCharsets.ISO_8859_1.name()).split("\r\n")
        val statusLine = lines.firstOrNull().orEmpty()
        if (statusLine.startsWith("SOURCETABLE", ignoreCase = true)) {
            throw NtripProtocolException("NTRIP mount point returned a source table", retryable = false)
        }
        val statusCode = when {
            statusLine.startsWith("ICY ", ignoreCase = true) -> statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            statusLine.startsWith("HTTP/") -> statusLine.split(' ').getOrNull(1)?.toIntOrNull()
            else -> null
        } ?: throw NtripProtocolException("invalid NTRIP response status", retryable = false)
        val headers = lines.drop(1).mapNotNull { line ->
            val separator = line.indexOf(':')
            if (separator <= 0) null else line.substring(0, separator).trim().lowercase(Locale.US) to
                line.substring(separator + 1).trim()
        }.toMap()
        return NtripResponse(statusCode, statusLine, headers)
    }

    fun normalizeGga(raw: String): String {
        require(raw.length <= 128 && '\r' !in raw && '\n' !in raw) { "invalid NTRIP GGA" }
        require(NmeaParser.parse(raw) is NmeaSentence.Gga) { "NTRIP GGA must be checksum-valid" }
        return raw
    }

    private const val MAX_HEADER_BYTES = 16 * 1024
}

class NtripCorrectionClient(
    private val endpoint: NtripEndpoint,
    private val username: String,
    private val password: String,
    private val connectTimeoutMillis: Int = 10_000,
    private val readPollMillis: Int = 1_000,
    private val ggaIntervalMillis: Long = 10_000,
    private val monotonicClockMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val activeSocket = AtomicReference<Socket?>()

    fun stream(
        ggaProvider: () -> String?,
        onConnected: () -> Unit = {},
        onCorrectionFrame: (ByteArray) -> Unit,
    ): NtripSessionResult {
        check(!closed.get()) { "NTRIP client is closed" }
        val socket = openSocket()
        activeSocket.set(socket)
        try {
            socket.soTimeout = readPollMillis
            val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
            val output = BufferedOutputStream(socket.getOutputStream(), 2 * 1024)
            val initialGga = ggaProvider()?.let(NtripWireProtocol::normalizeGga)
            output.write(NtripWireProtocol.buildRequest(endpoint, username, password, initialGga))
            output.flush()
            val response = NtripWireProtocol.readResponse(input)
            if (response.statusCode != 200) {
                val retryable = response.statusCode == 408 || response.statusCode == 429 || response.statusCode >= 500
                throw NtripProtocolException("NTRIP server returned HTTP ${response.statusCode}", retryable)
            }
            onConnected()
            val decoder = Rtcm3StreamDecoder()
            val buffer = ByteArray(4 * 1024)
            var frames = 0L
            var bytes = 0L
            var nextGgaAt = monotonicClockMillis() + ggaIntervalMillis
            while (!closed.get()) {
                val now = monotonicClockMillis()
                if (now >= nextGgaAt) {
                    val gga = ggaProvider()?.let(NtripWireProtocol::normalizeGga)
                        ?: throw NtripProtocolException("NTRIP GGA is unavailable", retryable = true)
                    output.write(gga.toByteArray(StandardCharsets.US_ASCII))
                    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                    nextGgaAt = now + ggaIntervalMillis
                }
                val count = try {
                    input.read(buffer)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (count < 0) break
                if (count == 0) continue
                decoder.feed(buffer.copyOf(count)).forEach { frame ->
                    onCorrectionFrame(frame)
                    frames += 1
                    bytes += frame.size
                }
            }
            return NtripSessionResult(frames, bytes, decoder.stats.crcErrors)
        } finally {
            activeSocket.compareAndSet(socket, null)
            runCatching { socket.close() }
        }
    }

    override fun close() {
        closed.set(true)
        activeSocket.getAndSet(null)?.let { socket -> runCatching { socket.close() } }
    }

    private fun openSocket(): Socket {
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
            if (!endpoint.secure) return plain
            val sslFactory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val ssl = sslFactory.createSocket(
                plain,
                endpoint.host,
                endpoint.port,
                true,
            ) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
            return ssl
        } catch (error: Throwable) {
            runCatching { plain.close() }
            throw error
        }
    }
}
