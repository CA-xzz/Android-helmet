package com.example.helmet.core.model

enum class HelmetOperationalState {
    BOOTING,
    SELF_TEST,
    OFFLINE_READY,
    IDLE,
    CALLING,
    IN_CALL,
    RECORDING,
    SOS,
    UPDATING,
    FAULT,
    SHUTTING_DOWN,
}

enum class EventSeverity {
    INFO,
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
}

enum class DeliveryState {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED,
    REJECTED,
}

enum class MediaKind {
    PHOTO,
    VIDEO,
    VOICE,
}

enum class VoiceMessageRole {
    DISPATCHER,
    SUPERVISOR,
    ADMIN,
}

enum class VoiceMessageSenderRole {
    DEVICE,
    DISPATCHER,
    SUPERVISOR,
    ADMIN,
}

enum class MediaTransferState {
    PENDING,
    IN_FLIGHT,
    DELIVERED,
    FAILED,
    REJECTED,
}

data class MediaAsset(
    val assetId: String,
    val kind: MediaKind,
    val filePath: String,
    val mimeType: String,
    val byteSize: Long,
    val sha256: String,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val createdAtEpochMillis: Long,
    val deviceId: String,
    val relatedEventId: String?,
    val transferState: MediaTransferState,
    val attemptCount: Int,
    val personId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val horizontalAccuracyMeters: Float? = null,
    val locationFixType: String = "NO_FIX",
    val voiceSenderId: String? = null,
    val voiceSenderRole: VoiceMessageSenderRole? = null,
    val voiceAllowedRoles: Set<VoiceMessageRole> = emptySet(),
    val voiceCallId: String? = null,
)

data class HelmetEvent(
    val messageId: String,
    val eventType: String,
    val severity: EventSeverity,
    val payloadJson: String,
    val occurredAtEpochMillis: Long,
)

data class RtkRuntimeConfig(
    val enabled: Boolean = false,
    val ntripUrl: String = "",
    val username: String = "",
    val password: String = "",
) {
    init {
        require(ntripUrl.length <= 2_048 && '\r' !in ntripUrl && '\n' !in ntripUrl)
        require(username.length <= 256 && ':' !in username && '\r' !in username && '\n' !in username)
        require(password.length <= 512 && '\r' !in password && '\n' !in password)
    }
}

data class LocalIntercomRuntimeConfig(
    val enabled: Boolean = false,
    val fallbackWhenInternetUnavailable: Boolean = true,
    val groupId: Int = 1,
    val channel: Int = 1,
    val keySlot: Int = 1,
) {
    init {
        require(groupId in 1..0xFFFF)
        require(channel in 0..0xFF)
        require(keySlot in 1..0xFF)
    }
}

data class RuntimeConfig(
    val revision: Long = 1,
    val simulatorEnabled: Boolean = true,
    val hardwareDevicePath: String = "/dev/ttyAS2",
    val hardwareBaudRate: Int = 115_200,
    val backendBaseUrl: String = "",
    val backendBearerToken: String = "",
    val mqttBrokerUri: String = "",
    val mqttClientCertificateAlias: String = "",
    val rtk: RtkRuntimeConfig = RtkRuntimeConfig(),
    val localIntercom: LocalIntercomRuntimeConfig = LocalIntercomRuntimeConfig(),
    val geofences: List<CircleGeofence> = emptyList(),
    val safetyThresholds: SafetyThresholdConfig = SafetyThresholdConfig(),
)

data class RuntimeSnapshot(
    val deviceId: String = "uninitialized",
    val state: HelmetOperationalState = HelmetOperationalState.BOOTING,
    val networkAvailable: Boolean = false,
    val networkState: String = "UNAVAILABLE",
    val networkTransports: String = "none",
    val networkMetered: Boolean = false,
    val networkInterface: String = "none",
    val persistedEventCount: Int = 0,
    val lastEventType: String? = null,
    val lastError: String? = null,
    val appVersion: String = "unknown",
    val androidVersion: String = "unknown",
    val configRevision: Long = 0,
    val hardwareMode: String = "unknown",
    val hardwareConnected: Boolean = false,
    val hardwareLinkState: String = "DISCONNECTED",
    val cameraCount: Int = 0,
    val cameraSummary: String = "unavailable",
    val pendingMediaCount: Int = 0,
    val locationState: String = "STOPPED",
    val locationProvider: String = "none",
    val locationFixQuality: String = "NO_FIX",
    val locationHasPosition: Boolean = false,
    val rtkState: String = "DISABLED",
    val rtkCorrectionFrames: Long = 0,
    val rtkCorrectionBytes: Long = 0,
    val rtkLastError: String? = null,
    val localIntercomState: String = "DISABLED",
    val localIntercomPeers: Int = 0,
    val localIntercomRssiDbm: Int? = null,
    val localIntercomPacketLossPermille: Int? = null,
    val localIntercomLatencyMillis: Int? = null,
    val localIntercomLastError: String? = null,
    val pendingTrackCount: Int = 0,
    val activeGeofenceCount: Int = 0,
    val activeCallId: String = "none",
    val callState: String = "none",
    val pendingCallSyncCount: Int = 0,
    val pendingBroadcastReceiptCount: Int = 0,
    val pendingSafetyAlertCount: Int = 0,
    val retainedSafetySampleCount: Int = 0,
    val batteryPresent: Boolean = false,
    val batteryPercent: Int? = null,
    val batteryVoltageMillivolts: Int? = null,
    val timeSource: String = "SYSTEM",
    val timeSynchronized: Boolean = false,
    val timeUncertaintyMillis: Long? = null,
    val timeCalibrationAgeMillis: Long? = null,
)
