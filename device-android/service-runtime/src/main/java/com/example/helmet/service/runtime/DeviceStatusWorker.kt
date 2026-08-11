package com.example.helmet.service.runtime

import android.content.Context
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.feature.connectivity.HttpConnectionPolicy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DeviceBatteryStatus(
    val present: Boolean,
    val percent: Int?,
    val voltageMillivolts: Int?,
) {
    init {
        require(present || (percent == null && voltageMillivolts == null))
        require(percent == null || percent in 0..100)
        require(voltageMillivolts == null || voltageMillivolts in 1..100_000)
    }
}

data class DeviceLocationStatus(
    val latitude: Double?,
    val longitude: Double?,
    val horizontalAccuracyMeters: Float?,
    val fixType: String,
    val occurredAtEpochMillis: Long?,
) {
    init {
        require((latitude == null) == (longitude == null))
        require(latitude == null || latitude in -90.0..90.0)
        require(longitude == null || longitude in -180.0..180.0)
        require(horizontalAccuracyMeters == null || horizontalAccuracyMeters >= 0)
        require(fixType.isNotBlank())
        require(occurredAtEpochMillis == null || occurredAtEpochMillis > 0)
    }
}

data class DeviceRtkStatus(
    val state: String = "DISABLED",
    val correctionFrames: Long = 0,
    val correctionBytes: Long = 0,
    val lastFixQuality: String = "NO_FIX",
    val lastError: String? = null,
) {
    init {
        require(state.isNotBlank() && correctionFrames >= 0 && correctionBytes >= 0)
        require(lastFixQuality.isNotBlank() && (lastError == null || lastError.length <= 256))
    }
}

data class DeviceLocalIntercomStatus(
    val state: String = "DISABLED",
    val peerCount: Int = 0,
    val codec: String = "unknown",
    val rssiDbm: Int? = null,
    val packetLossPermille: Int? = null,
    val oneWayLatencyMillis: Int? = null,
    val faultCode: Int = 0,
    val lastError: String? = null,
) {
    init {
        require(state.isNotBlank() && peerCount in 0..255 && codec.isNotBlank())
        require(rssiDbm == null || rssiDbm in -200..0)
        require(packetLossPermille == null || packetLossPermille in 0..1_000)
        require(oneWayLatencyMillis == null || oneWayLatencyMillis in 0..0xFFFF)
        require(faultCode in 0..0xFFFF && (lastError == null || lastError.length <= 256))
    }
}

data class DeviceTimeStatus(
    val source: DeviceTimeSource,
    val synchronized: Boolean,
    val uncertaintyMillis: Long?,
    val calibrationAgeMillis: Long?,
    val calibrationSequence: Long?,
) {
    init {
        require(synchronized == (source != DeviceTimeSource.SYSTEM))
        require(!synchronized || (uncertaintyMillis != null && calibrationAgeMillis != null && calibrationSequence != null))
        require(uncertaintyMillis == null || uncertaintyMillis >= 0)
        require(calibrationAgeMillis == null || calibrationAgeMillis >= 0)
        require(calibrationSequence == null || calibrationSequence > 0)
    }
}

data class DeviceStatusPayload(
    val messageId: String,
    val deviceId: String,
    val statusSequence: Long,
    val occurredAtEpochMillis: Long,
    val operationalState: String,
    val networkState: String,
    val hardwareMode: String,
    val appVersion: String,
    val cameraAvailable: Boolean,
    val simulated: Boolean,
    val activeCallId: String?,
    val battery: DeviceBatteryStatus,
    val location: DeviceLocationStatus,
    val rtk: DeviceRtkStatus = DeviceRtkStatus(),
    val localIntercom: DeviceLocalIntercomStatus = DeviceLocalIntercomStatus(),
    val time: DeviceTimeStatus,
) {
    init {
        require(messageId.isNotBlank() && deviceId.isNotBlank())
        require(statusSequence > 0)
        require(occurredAtEpochMillis > 0)
        require(location.occurredAtEpochMillis == null || location.occurredAtEpochMillis <= occurredAtEpochMillis)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("schemaVersion", 1)
        .put("messageId", messageId)
        .put("deviceId", deviceId)
        .put("statusSequence", statusSequence)
        .put("personId", JSONObject.NULL)
        .put("occurredAtEpochMillis", occurredAtEpochMillis)
        .put("operationalState", operationalState)
        .put("networkState", networkState)
        .put("hardwareMode", hardwareMode)
        .put("appVersion", appVersion)
        .put("cameraAvailable", cameraAvailable)
        .put("simulated", simulated)
        .put("activeCallId", activeCallId ?: JSONObject.NULL)
        .put(
            "battery",
            JSONObject()
                .put("present", battery.present)
                .put("percent", battery.percent ?: JSONObject.NULL)
                .put("voltageMillivolts", battery.voltageMillivolts ?: JSONObject.NULL),
        )
        .put(
            "location",
            JSONObject()
                .put("latitude", location.latitude ?: JSONObject.NULL)
                .put("longitude", location.longitude ?: JSONObject.NULL)
                .put("horizontalAccuracyMeters", location.horizontalAccuracyMeters ?: JSONObject.NULL)
                .put("fixType", location.fixType)
                .put("occurredAtEpochMillis", location.occurredAtEpochMillis ?: JSONObject.NULL)
                .put("isMock", false),
        )
        .put(
            "rtk",
            JSONObject()
                .put("state", rtk.state)
                .put("correctionFrames", rtk.correctionFrames)
                .put("correctionBytes", rtk.correctionBytes)
                .put("lastFixQuality", rtk.lastFixQuality)
                .put("lastError", rtk.lastError ?: JSONObject.NULL),
        )
        .put(
            "localIntercom",
            JSONObject()
                .put("state", localIntercom.state)
                .put("peerCount", localIntercom.peerCount)
                .put("codec", localIntercom.codec)
                .put("rssiDbm", localIntercom.rssiDbm ?: JSONObject.NULL)
                .put("packetLossPermille", localIntercom.packetLossPermille ?: JSONObject.NULL)
                .put("oneWayLatencyMillis", localIntercom.oneWayLatencyMillis ?: JSONObject.NULL)
                .put("faultCode", localIntercom.faultCode)
                .put("lastError", localIntercom.lastError ?: JSONObject.NULL),
        )
        .put(
            "time",
            JSONObject()
                .put("source", time.source.name)
                .put("synchronized", time.synchronized)
                .put("uncertaintyMillis", time.uncertaintyMillis ?: JSONObject.NULL)
                .put("calibrationAgeMillis", time.calibrationAgeMillis ?: JSONObject.NULL)
                .put("calibrationSequence", time.calibrationSequence ?: JSONObject.NULL),
        )
}

internal class DeviceStatusOutbox(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun replace(payload: DeviceStatusPayload) {
        check(
            preferences.edit()
                .putString(KEY_MESSAGE_ID, payload.messageId)
                .putString(KEY_JSON, payload.toJson().toString())
                .commit(),
        ) { "failed to persist device status" }
    }

    @Synchronized
    fun nextOccurredAt(candidate: Long, calibrationSequence: Long?): Long {
        require(candidate > 0)
        require(calibrationSequence == null || calibrationSequence > 0)
        val marker = calibrationSequence ?: SYSTEM_TIME_MARKER
        val previousMarker = preferences.getLong(KEY_LAST_TIME_MARKER, UNINITIALIZED_TIME_MARKER)
        val next = selectNextDeviceStatusTime(
            candidate,
            preferences.getLong(KEY_LAST_OCCURRED_AT, 0),
            previousMarker,
            marker,
        )
        check(
            preferences.edit()
                .putLong(KEY_LAST_OCCURRED_AT, next)
                .putLong(KEY_LAST_TIME_MARKER, marker)
                .commit(),
        ) {
            "failed to persist device status timestamp"
        }
        return next
    }

    fun current(): StoredDeviceStatus? {
        val messageId = preferences.getString(KEY_MESSAGE_ID, null) ?: return null
        val json = preferences.getString(KEY_JSON, null) ?: return null
        return StoredDeviceStatus(messageId, json)
    }

    fun clearIf(messageId: String) {
        if (preferences.getString(KEY_MESSAGE_ID, null) != messageId) return
        check(preferences.edit().remove(KEY_MESSAGE_ID).remove(KEY_JSON).commit()) {
            "failed to clear delivered device status"
        }
    }

    @Synchronized
    fun nextSequence(): Long {
        val next = preferences.getLong(KEY_LAST_SEQUENCE, 0) + 1
        check(preferences.edit().putLong(KEY_LAST_SEQUENCE, next).commit()) {
            "failed to persist device status sequence"
        }
        return next
    }

    data class StoredDeviceStatus(val messageId: String, val json: String)

    companion object {
        private const val PREFERENCES = "helmet_device_status_outbox"
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_JSON = "payload_json"
        private const val KEY_LAST_OCCURRED_AT = "last_occurred_at"
        private const val KEY_LAST_TIME_MARKER = "last_time_marker"
        private const val KEY_LAST_SEQUENCE = "last_sequence"
        private const val SYSTEM_TIME_MARKER = -1L
        private const val UNINITIALIZED_TIME_MARKER = Long.MIN_VALUE
    }
}

internal fun selectNextDeviceStatusTime(
    candidate: Long,
    previous: Long,
    previousTimeMarker: Long,
    currentTimeMarker: Long,
): Long {
    require(candidate > 0 && previous >= 0)
    return if (currentTimeMarker == previousTimeMarker) maxOf(candidate, previous + 1) else candidate
}

internal data class DeviceStatusReceipt(
    val messageId: String,
    val deduplicated: Boolean,
    val serverReceivedAtEpochMillis: Long,
    val requestStartedAtElapsedRealtimeMillis: Long? = null,
    val responseReceivedAtElapsedRealtimeMillis: Long? = null,
)

internal class DeviceStatusUploadException(
    message: String,
    val retryable: Boolean,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class HttpDeviceStatusClient(
    baseUrl: String,
    private val bearerToken: String,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val elapsedRealtimeClock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val endpoint = validateAndNormalizeBaseUrl(baseUrl)

    init {
        require(bearerToken.isNotBlank()) { "backend bearer token is empty" }
    }

    suspend fun upload(status: DeviceStatusOutbox.StoredDeviceStatus): DeviceStatusReceipt = withContext(Dispatchers.IO) {
        val requestStartedAt = elapsedRealtimeClock()
        val body = status.json.toByteArray(Charsets.UTF_8)
        val connection = HttpConnectionPolicy.apply(
            URL("$endpoint/v1/device-status").openConnection() as HttpURLConnection,
        ).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doInput = true
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            val responseText = HttpConnectionPolicy.readUtf8Response(
                connection,
                code,
                MAX_RESPONSE_BYTES,
            )
            if (code !in 200..299) {
                val detail = runCatching { JSONObject(responseText).optString("error") }.getOrNull()
                    ?.takeIf(String::isNotBlank) ?: responseText.take(1_024)
                throw DeviceStatusUploadException(
                    "device status server HTTP $code: $detail",
                    retryable = code == 408 || code == 429 || code >= 500,
                    statusCode = code,
                )
            }
            val responseReceivedAt = elapsedRealtimeClock()
            parseReceipt(responseText, status.messageId).copy(
                requestStartedAtElapsedRealtimeMillis = requestStartedAt,
                responseReceivedAtElapsedRealtimeMillis = responseReceivedAt,
            )
        } catch (error: DeviceStatusUploadException) {
            throw error
        } catch (error: IOException) {
            throw DeviceStatusUploadException("device status server I/O failure", retryable = true, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_RESPONSE_BYTES = 256 * 1024

        internal fun parseReceipt(raw: String, expectedMessageId: String): DeviceStatusReceipt {
            val response = runCatching { JSONObject(raw) }.getOrElse {
                throw DeviceStatusUploadException(
                    "device status acknowledgement is not valid JSON",
                    retryable = true,
                    cause = it,
                )
            }
            val acknowledged = runCatching { response.getString("messageId") }.getOrElse {
                throw DeviceStatusUploadException(
                    "device status acknowledgement is missing messageId",
                    retryable = true,
                    cause = it,
                )
            }
            if (acknowledged != expectedMessageId) {
                throw DeviceStatusUploadException("device status acknowledgement mismatch", retryable = true)
            }
            val serverReceivedAt = runCatching { response.getLong("serverReceivedAtEpochMillis") }
                .getOrElse {
                    throw DeviceStatusUploadException(
                        "device status acknowledgement is missing server time",
                        retryable = true,
                        cause = it,
                    )
                }
            if (serverReceivedAt <= 0) {
                throw DeviceStatusUploadException(
                    "device status acknowledgement has invalid server time",
                    retryable = true,
                )
            }
            val deduplicated = runCatching { response.getBoolean("deduplicated") }.getOrElse {
                throw DeviceStatusUploadException(
                    "device status acknowledgement is missing deduplication state",
                    retryable = true,
                    cause = it,
                )
            }
            return DeviceStatusReceipt(
                acknowledged,
                deduplicated,
                serverReceivedAt,
            )
        }

        internal fun validateAndNormalizeBaseUrl(raw: String): String {
            val uri = runCatching { URI(raw.trim()) }.getOrElse {
                throw IllegalArgumentException("invalid backend base URL", it)
            }
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "backend base URL must not contain a path" }
            require(uri.query == null && uri.fragment == null && uri.userInfo == null) {
                "backend base URL must not contain credentials, query, or fragment"
            }
            val host = uri.host?.lowercase().orEmpty()
            val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
            require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) {
                "backend base URL must use HTTPS; HTTP is allowed only for loopback tests"
            }
            require(host.isNotBlank()) { "backend base URL host is missing" }
            return raw.trim().trimEnd('/')
        }
    }
}

class DeviceStatusWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val config = RuntimeConfigStore(applicationContext).load()
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) return Result.success()
        val outbox = DeviceStatusOutbox(applicationContext)
        val status = outbox.current() ?: return Result.success()
        val timeAuthority = DeviceTimeAuthorityProvider.get(applicationContext)
        val eventStore = EventStore(HelmetDatabase.get(applicationContext), timeAuthority::nowEpochMillis)
        val client = runCatching { HttpDeviceStatusClient(config.backendBaseUrl, config.backendBearerToken) }
            .getOrElse { return Result.failure() }
        return try {
            val receipt = client.upload(status)
            val calibration = if (receipt.deduplicated) {
                null
            } else {
                runCatching {
                    timeAuthority.calibrateFromServer(
                        receipt.serverReceivedAtEpochMillis,
                        requireNotNull(receipt.requestStartedAtElapsedRealtimeMillis),
                        requireNotNull(receipt.responseReceivedAtElapsedRealtimeMillis),
                    )
                }.getOrNull()
            }
            outbox.clearIf(receipt.messageId)
            eventStore.record(
                eventType = "DEVICE_STATUS_UPLOAD_COMPLETED",
                severity = EventSeverity.INFO,
                payloadJson = JSONObject(
                    mapOf(
                        "messageId" to receipt.messageId,
                        "deduplicated" to receipt.deduplicated,
                        "serverReceivedAtEpochMillis" to receipt.serverReceivedAtEpochMillis,
                        "roundTripMillis" to receipt.requestStartedAtElapsedRealtimeMillis?.let { started ->
                            receipt.responseReceivedAtElapsedRealtimeMillis?.minus(started)
                        },
                        "deviceTimeCalibrated" to (calibration != null),
                        "deviceTimeSource" to calibration?.reading?.source?.name,
                        "deviceTimeUncertaintyMillis" to calibration?.reading?.uncertaintyMillis,
                    ),
                ).toString(),
            )
            calibration?.let(timeAuthority::announceSignificantAdjustment)
            Result.success()
        } catch (error: DeviceStatusUploadException) {
            eventStore.record(
                eventType = if (error.retryable) "DEVICE_STATUS_UPLOAD_RETRY_SCHEDULED" else "DEVICE_STATUS_UPLOAD_REJECTED",
                severity = EventSeverity.MEDIUM,
                payloadJson = JSONObject(
                    mapOf("messageId" to status.messageId, "statusCode" to error.statusCode, "error" to error.toString().take(1_024)),
                ).toString(),
            )
            if (error.retryable) Result.retry() else {
                outbox.clearIf(status.messageId)
                Result.failure()
            }
        }
    }

    companion object {
        private const val UNIQUE_WORK = "helmet-device-status-upload"

        fun replace(context: Context, payload: DeviceStatusPayload) {
            DeviceStatusOutbox(context).replace(payload)
            val backendUrl = RuntimeConfigStore(context).load().backendBaseUrl
            val request = OneTimeWorkRequestBuilder<DeviceStatusWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(requiredNetworkTypeForBackendUrl(backendUrl))
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
