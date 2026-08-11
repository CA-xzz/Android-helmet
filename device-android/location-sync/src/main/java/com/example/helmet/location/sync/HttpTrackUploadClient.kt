package com.example.helmet.location.sync

import com.example.helmet.core.model.TrackPoint
import com.example.helmet.feature.connectivity.HttpConnectionPolicy
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpTrackUploadClient(
    baseUrl: String,
    private val bearerToken: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : TrackUploadTransport {
    private val endpoint = validateAndNormalizeBaseUrl(baseUrl)

    override suspend fun upload(points: List<TrackPoint>): TrackUploadReceipt = withContext(Dispatchers.IO) {
        validateBatch(points)
        val request = JSONObject().put(
            "points",
            JSONArray().also { array -> points.forEach { point -> array.put(point.toJson()) } },
        )
        val response = requestJson(request.toString().toByteArray(Charsets.UTF_8))
        val accepted = response.getJSONArray("acceptedMessageIds").toStringSet()
        val duplicates = response.getJSONArray("duplicateMessageIds").toStringSet()
        val requested = points.map(TrackPoint::messageId).toSet()
        if (accepted.intersect(duplicates).isNotEmpty() || accepted + duplicates != requested) {
            throw TrackUploadException("track server acknowledgement does not match request", retryable = true)
        }
        TrackUploadReceipt(accepted, duplicates)
    }

    private fun requestJson(body: ByteArray): JSONObject {
        val connection = HttpConnectionPolicy.apply(
            URL("$endpoint/v1/tracks:batch").openConnection() as HttpURLConnection,
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
            connection.outputStream.use { output -> output.write(body) }
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
                throw TrackUploadException(
                    "track server HTTP $status: $detail",
                    retryable = status == 408 || status == 429 || status >= 500,
                    statusCode = status,
                )
            }
            JSONObject(responseText)
        } catch (error: TrackUploadException) {
            throw error
        } catch (error: IOException) {
            throw TrackUploadException("track server I/O failure", retryable = true, cause = error)
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

        private fun validateBatch(points: List<TrackPoint>) {
            require(points.isNotEmpty()) { "track batch is empty" }
            require(points.size <= MAX_POINTS_PER_BATCH) { "track batch is too large" }
            require(points.none { it.fix.isMock }) { "mock locations cannot be uploaded" }
            require(points.map(TrackPoint::messageId).toSet().size == points.size) { "duplicate message ID" }
            val keys = points.map { it.deviceId to it.sequence }
            require(keys.toSet().size == keys.size) { "duplicate device sequence" }
            require(keys == keys.sortedWith(compareBy<Pair<String, Long>> { it.first }.thenBy { it.second })) {
                "track batch must be ordered by device and sequence"
            }
        }

        private fun TrackPoint.toJson(): JSONObject = JSONObject()
            .put("messageId", messageId)
            .put("deviceId", deviceId)
            .put("sequence", sequence)
            .put("fixId", fix.fixId)
            .put("occurredAtEpochMillis", fix.occurredAtEpochMillis)
            .put("elapsedRealtimeNanos", fix.elapsedRealtimeNanos ?: JSONObject.NULL)
            .put("source", fix.source.name)
            .put("quality", fix.quality.name)
            .put("latitude", fix.latitude)
            .put("longitude", fix.longitude)
            .put("altitudeMeters", fix.altitudeMeters ?: JSONObject.NULL)
            .put("horizontalAccuracyMeters", fix.horizontalAccuracyMeters ?: JSONObject.NULL)
            .put("verticalAccuracyMeters", fix.verticalAccuracyMeters ?: JSONObject.NULL)
            .put("speedMetersPerSecond", fix.speedMetersPerSecond ?: JSONObject.NULL)
            .put("speedAccuracyMetersPerSecond", fix.speedAccuracyMetersPerSecond ?: JSONObject.NULL)
            .put("bearingDegrees", fix.bearingDegrees ?: JSONObject.NULL)
            .put("bearingAccuracyDegrees", fix.bearingAccuracyDegrees ?: JSONObject.NULL)
            .put("satellitesUsed", fix.gnss.satellitesUsed ?: JSONObject.NULL)
            .put("satellitesVisible", fix.gnss.satellitesVisible ?: JSONObject.NULL)
            .put("pdop", fix.gnss.pdop ?: JSONObject.NULL)
            .put("hdop", fix.gnss.hdop ?: JSONObject.NULL)
            .put("vdop", fix.gnss.vdop ?: JSONObject.NULL)
            .put("correctionAgeSeconds", fix.gnss.correctionAgeSeconds ?: JSONObject.NULL)
            .put("correctionStationId", fix.gnss.correctionStationId ?: JSONObject.NULL)
            .put("provider", fix.provider ?: JSONObject.NULL)
            .put("isMock", false)

        private fun JSONArray.toStringSet(): Set<String> = buildSet {
            for (index in 0 until length()) add(getString(index))
        }

        private const val MAX_POINTS_PER_BATCH = 200
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val MAX_RESPONSE_BYTES = 1024 * 1024
        private const val MAX_ERROR_TEXT = 1_024
    }
}
