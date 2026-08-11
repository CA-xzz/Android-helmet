package com.example.helmet.webrtc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.example.helmet.core.model.CallMediaMode
import com.example.helmet.core.model.IceConfiguration
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SessionDescription
import org.webrtc.SdpObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

enum class WebRtcMediaState {
    NEW,
    OFFER_READY,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED,
}

data class WebRtcStatus(
    val callId: String,
    val state: WebRtcMediaState,
    val audioEnabled: Boolean,
    val videoEnabled: Boolean,
    val degradedReason: String?,
    val peerConnectionState: String,
    val iceConnectionState: String,
)

data class LocalIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String,
)

interface WebRtcCallListener {
    fun onStatus(status: WebRtcStatus)
    fun onLocalIceCandidate(candidate: LocalIceCandidate)
    fun onIceGatheringComplete()
}

data class WebRtcOffer(
    val callId: String,
    val sdp: String,
    val audioEnabled: Boolean,
    val videoEnabled: Boolean,
    val degradedReason: String?,
)

class WebRtcException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Owns one DTLS-SRTP WebRTC peer connection for device-originated audio/video uplink. */
class WebRtcCallEngine(
    context: Context,
    private val callId: String,
    private val mediaMode: CallMediaMode,
    private val iceConfiguration: IceConfiguration,
    private val listener: WebRtcCallListener,
    private val captureAudio: Boolean = true,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : Closeable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val audioFailureReported = AtomicBoolean(false)
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var eglBase: EglBase? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var cameraCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var degradedReason: String? = null
    private var audioEnabled = false
    private var videoEnabled = false
    private var lastPeerState = PeerConnection.PeerConnectionState.NEW
    private var lastIceState = PeerConnection.IceConnectionState.NEW

    suspend fun createOffer(): WebRtcOffer {
        check(!closed.get()) { "WebRTC engine is closed" }
        require(iceConfiguration.callId == callId) { "ICE configuration call mismatch" }
        if (!iceConfiguration.isUsableAt(wallClock())) {
            throw WebRtcException("ICE_CONFIGURATION_EXPIRED")
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw WebRtcException("RECORD_AUDIO_PERMISSION_DENIED")
        }
        ensureFactoryInitialized()
        val connection = createPeerConnection()
        peerConnection = connection
        attachAudio(connection)
        connection.setAudioRecording(captureAudio)
        if (!captureAudio) degradedReason = "AUDIO_CAPTURE_DISABLED_FOR_PROBE"
        if (mediaMode == CallMediaMode.VIDEO_UPLINK) attachVideoIfAvailable(connection)

        val offer = createSessionDescription(connection)
        setLocalDescription(connection, offer)
        publishStatus(WebRtcMediaState.OFFER_READY)
        return WebRtcOffer(
            callId = callId,
            sdp = offer.description,
            audioEnabled = true,
            videoEnabled = videoEnabled,
            degradedReason = degradedReason,
        )
    }

    suspend fun applyRemoteAnswer(sdp: String) {
        require(sdp.isNotBlank())
        val connection = requireNotNull(peerConnection) { "peer connection has not started" }
        setRemoteDescription(connection, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        publishStatus(WebRtcMediaState.CONNECTING)
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String): Boolean {
        require(sdpMLineIndex >= 0)
        require(candidate.isNotBlank())
        return requireNotNull(peerConnection) { "peer connection has not started" }
            .addIceCandidate(IceCandidate(sdpMid, sdpMLineIndex, candidate))
    }

    fun setLowBandwidthMode(enabled: Boolean): Boolean {
        val connection = peerConnection ?: return false
        var changed = connection.setBitrate(
            if (enabled) 24_000 else 32_000,
            if (enabled) 180_000 else 600_000,
            if (enabled) 350_000 else 1_500_000,
        )
        connection.senders.filter { it.track()?.kind() == "video" }.forEach { sender ->
            val parameters = sender.parameters
            parameters.degradationPreference = if (enabled) {
                org.webrtc.RtpParameters.DegradationPreference.MAINTAIN_FRAMERATE
            } else {
                org.webrtc.RtpParameters.DegradationPreference.BALANCED
            }
            parameters.encodings.forEach { encoding ->
                encoding.maxBitrateBps = if (enabled) 350_000 else 1_500_000
                encoding.maxFramerate = if (enabled) 10 else 15
                encoding.scaleResolutionDownBy = if (enabled) 2.0 else 1.0
            }
            changed = sender.setParameters(parameters) || changed
        }
        return changed
    }

    fun restartIce() {
        requireNotNull(peerConnection) { "peer connection has not started" }.restartIce()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnection = null
        runCatching { cameraCapturer?.stopCapture() }
        cameraCapturer?.dispose()
        surfaceTextureHelper?.dispose()
        videoTrack?.dispose()
        videoSource?.dispose()
        audioTrack?.dispose()
        audioTrack = null
        audioEnabled = false
        audioSource?.dispose()
        peerConnectionFactory?.dispose()
        peerConnectionFactory = null
        audioDeviceModule?.release()
        eglBase?.release()
        publishStatus(WebRtcMediaState.CLOSED)
    }

    private fun ensureFactoryInitialized() {
        initializeWebRtcOnce(appContext)
        val cameraAvailable = mediaMode == CallMediaMode.VIDEO_UPLINK && availableCameraName() != null
        if (mediaMode == CallMediaMode.VIDEO_UPLINK && !cameraAvailable) degradedReason = "CAMERA_UNAVAILABLE"
        val audioModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
            .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
            .setAudioRecordErrorCallback(object : JavaAudioDeviceModule.AudioRecordErrorCallback {
                override fun onWebRtcAudioRecordInitError(error: String) =
                    reportAudioFailure("AUDIO_RECORD_INIT", error)

                override fun onWebRtcAudioRecordStartError(
                    code: JavaAudioDeviceModule.AudioRecordStartErrorCode,
                    error: String,
                ) = reportAudioFailure("AUDIO_RECORD_START_${code.name}", error)

                override fun onWebRtcAudioRecordError(error: String) =
                    reportAudioFailure("AUDIO_RECORD_RUNTIME", error)
            })
            .setAudioTrackErrorCallback(object : JavaAudioDeviceModule.AudioTrackErrorCallback {
                override fun onWebRtcAudioTrackInitError(error: String) =
                    reportAudioFailure("AUDIO_PLAYOUT_INIT", error)

                override fun onWebRtcAudioTrackStartError(
                    code: JavaAudioDeviceModule.AudioTrackStartErrorCode,
                    error: String,
                ) = reportAudioFailure("AUDIO_PLAYOUT_START_${code.name}", error)

                override fun onWebRtcAudioTrackError(error: String) =
                    reportAudioFailure("AUDIO_PLAYOUT_RUNTIME", error)
            })
            .createAudioDeviceModule()
        audioDeviceModule = audioModule
        val builder = PeerConnectionFactory.builder().setAudioDeviceModule(audioModule)
        if (cameraAvailable) {
            val egl = EglBase.create()
            eglBase = egl
            builder
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
        }
        peerConnectionFactory = builder.createPeerConnectionFactory()
    }

    private fun createPeerConnection(): PeerConnection {
        val servers = iceConfiguration.servers.map { server ->
            PeerConnection.IceServer.builder(server.urls).apply {
                if (server.username != null) {
                    setUsername(server.username)
                    setPassword(requireNotNull(server.credential))
                }
            }.createIceServer()
        }
        val configuration = PeerConnection.RTCConfiguration(servers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            keyType = PeerConnection.KeyType.ECDSA
            iceCandidatePoolSize = 2
            enableDscp = true
            enableCpuOveruseDetection = true
            suspendBelowMinBitrate = true
        }
        return peerConnectionFactory?.createPeerConnection(configuration, Observer())
            ?: throw WebRtcException("PEER_CONNECTION_CREATION_FAILED")
    }

    private fun attachAudio(connection: PeerConnection) {
        val constraints = MediaConstraints().apply {
            mandatory += MediaConstraints.KeyValuePair("googEchoCancellation", "true")
            mandatory += MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
            mandatory += MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            mandatory += MediaConstraints.KeyValuePair("googHighpassFilter", "true")
        }
        val source = requireNotNull(peerConnectionFactory).createAudioSource(constraints)
        val track = requireNotNull(peerConnectionFactory).createAudioTrack("helmet-audio", source)
        track.setEnabled(true)
        connection.addTrack(track, listOf("helmet-$callId"))
        audioSource = source
        audioTrack = track
        audioEnabled = true
    }

    private fun attachVideoIfAvailable(connection: PeerConnection) {
        val cameraName = availableCameraName() ?: return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            degradedReason = "CAMERA_PERMISSION_DENIED"
            return
        }
        val enumerator = Camera2Enumerator(appContext)
        val capturer = enumerator.createCapturer(cameraName, null) ?: run {
            degradedReason = "CAMERA_OPEN_FAILED"
            return
        }
        val egl = eglBase ?: run {
            degradedReason = "VIDEO_CODEC_UNAVAILABLE"
            capturer.dispose()
            return
        }
        val helper = SurfaceTextureHelper.create("HelmetVideoCapture", egl.eglBaseContext)
        val source = requireNotNull(peerConnectionFactory).createVideoSource(false)
        capturer.initialize(helper, appContext, source.capturerObserver)
        runCatching { capturer.startCapture(640, 480, 15) }.onFailure {
            degradedReason = "CAMERA_START_FAILED:${it.javaClass.simpleName}"
            source.dispose()
            helper.dispose()
            capturer.dispose()
            return
        }
        val track = requireNotNull(peerConnectionFactory).createVideoTrack("helmet-video", source)
        track.setEnabled(true)
        connection.addTrack(track, listOf("helmet-$callId"))
        cameraCapturer = capturer
        surfaceTextureHelper = helper
        videoSource = source
        videoTrack = track
        videoEnabled = true
    }

    private fun availableCameraName(): String? = runCatching {
        val enumerator = Camera2Enumerator(appContext)
        enumerator.deviceNames.firstOrNull(enumerator::isBackFacing)
            ?: enumerator.deviceNames.firstOrNull()
    }.getOrNull()

    private suspend fun createSessionDescription(connection: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            connection.createOffer(
                object : SdpObserverAdapter() {
                    override fun onCreateSuccess(description: SessionDescription) {
                        if (continuation.isActive) continuation.resume(description)
                    }

                    override fun onCreateFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(WebRtcException("SDP_OFFER_FAILED:$error"))
                    }
                },
                MediaConstraints(),
            )
        }

    private suspend fun setLocalDescription(connection: PeerConnection, description: SessionDescription) =
        setDescription { observer -> connection.setLocalDescription(observer, description) }

    private suspend fun setRemoteDescription(connection: PeerConnection, description: SessionDescription) =
        setDescription { observer -> connection.setRemoteDescription(observer, description) }

    private suspend fun setDescription(operation: (SdpObserver) -> Unit): Unit =
        suspendCancellableCoroutine { continuation ->
            operation(
                object : SdpObserverAdapter() {
                    override fun onSetSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onSetFailure(error: String) {
                        if (continuation.isActive) continuation.resumeWithException(WebRtcException("SDP_SET_FAILED:$error"))
                    }
                },
            )
        }

    private fun publishStatus(state: WebRtcMediaState) {
        listener.onStatus(
            WebRtcStatus(
                callId = callId,
                state = state,
                audioEnabled = audioEnabled,
                videoEnabled = videoEnabled,
                degradedReason = degradedReason,
                peerConnectionState = lastPeerState.name,
                iceConnectionState = lastIceState.name,
            ),
        )
    }

    private fun reportAudioFailure(code: String, error: String) {
        degradedReason = "$code:${error.take(MAX_NATIVE_ERROR_LENGTH)}"
        if (audioFailureReported.compareAndSet(false, true)) publishStatus(WebRtcMediaState.FAILED)
    }

    private inner class Observer : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            lastIceState = state
            if (state == PeerConnection.IceConnectionState.FAILED) publishStatus(WebRtcMediaState.FAILED)
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            lastPeerState = state
            val mapped = when (state) {
                PeerConnection.PeerConnectionState.NEW -> WebRtcMediaState.NEW
                PeerConnection.PeerConnectionState.CONNECTING -> WebRtcMediaState.CONNECTING
                PeerConnection.PeerConnectionState.CONNECTED -> WebRtcMediaState.CONNECTED
                PeerConnection.PeerConnectionState.DISCONNECTED -> WebRtcMediaState.DISCONNECTED
                PeerConnection.PeerConnectionState.FAILED -> WebRtcMediaState.FAILED
                PeerConnection.PeerConnectionState.CLOSED -> WebRtcMediaState.CLOSED
            }
            publishStatus(mapped)
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) listener.onIceGatheringComplete()
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onLocalIceCandidate(
                LocalIceCandidate(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp),
            )
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) = Unit
    }

    private open class SdpObserverAdapter : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String) = Unit
        override fun onSetFailure(error: String) = Unit
    }

    companion object {
        private val initialized = AtomicBoolean(false)
        private const val MAX_NATIVE_ERROR_LENGTH = 512

        private fun initializeWebRtcOnce(context: Context) {
            if (initialized.compareAndSet(false, true)) {
                try {
                    PeerConnectionFactory.initialize(
                        PeerConnectionFactory.InitializationOptions.builder(context)
                            .setEnableInternalTracer(false)
                            .createInitializationOptions(),
                    )
                } catch (error: Throwable) {
                    initialized.set(false)
                    throw WebRtcException("WEBRTC_NATIVE_INITIALIZATION_FAILED", error)
                }
            }
        }

        fun nativeLibraryAvailable(context: Context): Boolean = runCatching {
            initializeWebRtcOnce(context.applicationContext)
            true
        }.getOrDefault(false)

        fun iceCandidatePayload(candidate: LocalIceCandidate): String = JSONObject()
            .put("sdpMid", candidate.sdpMid ?: JSONObject.NULL)
            .put("sdpMLineIndex", candidate.sdpMLineIndex)
            .put("candidate", candidate.candidate)
            .toString()
    }
}
