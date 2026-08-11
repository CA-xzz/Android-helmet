package com.example.helmet.alert.sync

import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.feature.connectivity.HttpConnectionPolicy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpAlertUploadClient(
    baseUrl: String,
    private val bearerToken: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : AlertUploadTransport {
    private val endpoint = validateAndNormalizeBaseUrl(baseUrl)

    override suspend fun upload(alert: SafetyAlertRecord): AlertUploadReceipt = withContext(Dispatchers.IO) {
        require(alert.deliveryState.name in setOf("PENDING", "IN_FLIGHT", "FAILED")) {
            "alert is not pending delivery"
        }
        val request = alert.toJson().toString().toByteArray(Charsets.UTF_8)
        val response = requestJson(request)
        val returnedAlertId = response.getString("alertId")
        if (returnedAlertId != alert.alertId) {
            throw AlertUploadException("alert server acknowledgement does not match request", retryable = true)
        }
        AlertUploadReceipt(
            messageId = alert.messageId,
            alertId = returnedAlertId,
            deduplicated = response.getBoolean("deduplicated"),
        )
    }

    private fun requestJson(body: ByteArray): JSONObject {
        val connection = HttpConnectionPolicy.apply(
            URL("$endpoint/v1/alerts").openConnection() as HttpURLConnection,
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
        return try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val responseText = HttpConnectionPolicy.readUtf8Response(
                connection,
                status,
                MAX_RESPONSE_BYTES,
            )
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(responseText).optString("error") }.getOrNull()
                    ?.takeIf(String::isNotBlank)
                    ?: responseText.take(MAX_ERROR_TEXT)
                throw AlertUploadException(
                    "alert server HTTP $status: $detail",
                    retryable = status == 408 || status == 429 || status >= 500,
                    statusCode = status,
                )
            }
            JSONObject(responseText)
        } catch (error: AlertUploadException) {
            throw error
        } catch (error: IOException) {
            throw AlertUploadException("alert server I/O failure", retryable = true, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
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

        internal fun SafetyAlertRecord.toJson(): JSONObject = JSONObject()
            .put("messageId", messageId)
            .put("alertId", alertId)
            .put("deviceId", deviceId)
            .put("type", alarmType)
            .put("severity", severity.name)
            .put("active", active)
            .put("configVersion", configVersion ?: JSONObject.NULL)
            .put("sampleReference", sampleReference ?: JSONObject.NULL)
            .put("monotonicMillis", monotonicMillis)
            .put("occurredAtEpochMillis", occurredAtEpochMillis)
            .put("localActions", localActions)
            .put("sensorFaults", sensorFaults)
            .put("simulated", simulated)
            .put("sensorSnapshot", JSONObject(sensorSnapshotJson))
            .put(
                "location",
                JSONObject()
                    .put("latitude", latitude ?: JSONObject.NULL)
                    .put("longitude", longitude ?: JSONObject.NULL)
                    .put("horizontalAccuracyMeters", horizontalAccuracyMeters ?: JSONObject.NULL)
                    .put("fixType", locationFixType),
            )
            .put(
                "evidence",
                JSONObject()
                    .put("mediaAssetId", evidenceAssetId ?: JSONObject.NULL)
                    .put("relatedEventId", messageId),
            )

        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ERROR_TEXT = 1_024
    }
}
