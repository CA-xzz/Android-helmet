package com.example.helmet.media.sync

import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.feature.connectivity.HttpConnectionPolicy
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpMediaUploadClient(
    baseUrl: String,
    private val bearerToken: String,
    private val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MILLIS,
) : MediaUploadTransport {
    private val endpoint = validateAndNormalizeBaseUrl(baseUrl)

    override suspend fun upload(asset: MediaAsset): MediaUploadReceipt = withContext(Dispatchers.IO) {
        val file = validateLocalAsset(asset)
        val session = createOrResumeSession(asset)
        if (session.status == STATUS_COMPLETED) {
            return@withContext session.toReceipt(asset, bytesUploaded = 0)
        }

        var offset = session.nextOffset
        var bytesUploaded = 0L
        RandomAccessFile(file, "r").use { source ->
            while (offset < asset.byteSize) {
                source.seek(offset)
                val requested = minOf(session.chunkSize.toLong(), asset.byteSize - offset).toInt()
                val bytes = ByteArray(requested)
                source.readFully(bytes)
                val nextOffset = uploadChunk(session.sessionId, offset, asset.byteSize, bytes)
                if (nextOffset <= offset || nextOffset > asset.byteSize) {
                    throw MediaUploadException(
                        "server returned invalid next offset $nextOffset for ${asset.assetId}",
                        retryable = false,
                    )
                }
                bytesUploaded += nextOffset - offset
                offset = nextOffset
            }
        }
        completeSession(session.sessionId, asset).toReceipt(asset, bytesUploaded)
    }

    private fun validateLocalAsset(asset: MediaAsset): File {
        require(asset.assetId.isNotBlank()) { "assetId is blank" }
        require(asset.byteSize >= 0) { "negative media size" }
        require(SHA_256.matches(asset.sha256)) { "invalid media SHA-256" }
        val file = File(asset.filePath)
        if (!file.isFile) {
            throw MediaUploadException("media file is missing: ${asset.assetId}", retryable = false)
        }
        if (file.length() != asset.byteSize) {
            throw MediaUploadException("media size changed: ${asset.assetId}", retryable = false)
        }
        val actualSha256 = MediaIntegrity.sha256(file)
        if (actualSha256 != asset.sha256) {
            throw MediaUploadException("media SHA-256 changed: ${asset.assetId}", retryable = false)
        }
        if (asset.kind == MediaKind.VOICE) {
            val durationMillis = asset.durationMillis
            val voiceCallId = asset.voiceCallId
            val valid = asset.mimeType in VOICE_MIME_TYPES &&
                durationMillis != null && durationMillis > 0 &&
                asset.voiceSenderId?.matches(ID_PATTERN) == true &&
                asset.voiceSenderRole != null && asset.voiceAllowedRoles.isNotEmpty() &&
                (voiceCallId == null || voiceCallId.matches(ID_PATTERN))
            if (!valid) {
                throw MediaUploadException("voice metadata is invalid: ${asset.assetId}", retryable = false)
            }
        }
        return file
    }

    private fun createOrResumeSession(asset: MediaAsset): SessionResponse {
        val request = metadataJson(asset)
        return parseSession(
            requestJson("POST", "/v1/media/sessions", request.toString().toByteArray(Charsets.UTF_8)),
        )
    }

    private fun uploadChunk(sessionId: String, offset: Long, total: Long, bytes: ByteArray): Long {
        val endInclusive = offset + bytes.size - 1
        val response = requestJson(
            method = "PUT",
            path = "/v1/media/sessions/${encodePathSegment(sessionId)}/chunks",
            body = bytes,
            contentType = "application/octet-stream",
            extraHeaders = mapOf(
                "Content-Range" to "bytes $offset-$endInclusive/$total",
                "X-Chunk-SHA256" to MediaIntegrity.sha256(bytes),
            ),
        )
        return response.getLong("nextOffset")
    }

    private fun completeSession(sessionId: String, asset: MediaAsset): SessionResponse {
        val body = JSONObject()
            .put("mediaId", asset.assetId)
            .put("byteSize", asset.byteSize)
            .put("sha256", asset.sha256)
            .toString()
            .toByteArray(Charsets.UTF_8)
        return parseSession(
            requestJson(
                "POST",
                "/v1/media/sessions/${encodePathSegment(sessionId)}/complete",
                body,
            ),
        )
    }

    private fun requestJson(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String = "application/json; charset=utf-8",
        extraHeaders: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = HttpConnectionPolicy.apply(
            URL("$endpoint$path").openConnection() as HttpURLConnection,
        ).apply {
            requestMethod = method
            connectTimeout = connectTimeoutMillis
            readTimeout = readTimeoutMillis
            doInput = true
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", contentType)
            setFixedLengthStreamingMode(body.size)
            if (bearerToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $bearerToken")
            extraHeaders.forEach(::setRequestProperty)
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
                throw MediaUploadException(
                    "media server HTTP $status: $detail",
                    retryable = status == 408 || status == 429 || status >= 500,
                    statusCode = status,
                )
            }
            JSONObject(responseText)
        } catch (error: MediaUploadException) {
            throw error
        } catch (error: IOException) {
            throw MediaUploadException("media server I/O failure", retryable = true, cause = error)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseSession(json: JSONObject): SessionResponse = SessionResponse(
        sessionId = json.getString("sessionId"),
        nextOffset = json.getLong("nextOffset"),
        chunkSize = json.getInt("chunkSize"),
        status = json.getString("status"),
        archiveId = json.optString("archiveId").takeIf(String::isNotBlank),
        deduplicated = json.optBoolean("deduplicated", false),
    ).also { response ->
        require(response.chunkSize in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES) {
            "server chunk size is outside the supported range"
        }
    }

    private data class SessionResponse(
        val sessionId: String,
        val nextOffset: Long,
        val chunkSize: Int,
        val status: String,
        val archiveId: String?,
        val deduplicated: Boolean,
    ) {
        fun toReceipt(asset: MediaAsset, bytesUploaded: Long): MediaUploadReceipt {
            if (status != STATUS_COMPLETED || archiveId.isNullOrBlank()) {
                throw MediaUploadException("media server did not complete ${asset.assetId}", retryable = true)
            }
            return MediaUploadReceipt(
                mediaId = asset.assetId,
                archiveId = archiveId,
                contentSha256 = asset.sha256,
                bytesUploaded = bytesUploaded,
                deduplicated = deduplicated,
            )
        }
    }

    companion object {
        internal fun metadataJson(asset: MediaAsset): JSONObject {
            val request = JSONObject()
                .put("mediaId", asset.assetId)
                .put("deviceId", asset.deviceId)
                .put("kind", asset.kind.name)
                .put("mimeType", asset.mimeType)
                .put("byteSize", asset.byteSize)
                .put("sha256", asset.sha256)
                .put("createdAtEpochMillis", asset.createdAtEpochMillis)
                .put("relatedEventId", asset.relatedEventId ?: JSONObject.NULL)
            if (asset.kind == MediaKind.VOICE) {
                request
                    .put("durationMillis", asset.durationMillis ?: JSONObject.NULL)
                    .put("senderId", asset.voiceSenderId ?: JSONObject.NULL)
                    .put("senderRole", asset.voiceSenderRole?.name ?: JSONObject.NULL)
                    .put(
                        "allowedRoles",
                        JSONArray(asset.voiceAllowedRoles.map { it.name }.sorted()),
                    )
                    .put("callId", asset.voiceCallId ?: JSONObject.NULL)
            } else {
                request
                    .put("width", asset.width)
                    .put("height", asset.height)
                    .put("durationMillis", asset.durationMillis ?: JSONObject.NULL)
                    .put("personId", asset.personId ?: JSONObject.NULL)
                    .put(
                        "location",
                        JSONObject()
                            .put("latitude", asset.latitude ?: JSONObject.NULL)
                            .put("longitude", asset.longitude ?: JSONObject.NULL)
                            .put(
                                "horizontalAccuracyMeters",
                                asset.horizontalAccuracyMeters ?: JSONObject.NULL,
                            )
                            .put("fixType", asset.locationFixType),
                    )
            }
            return request
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

        private fun encodePathSegment(value: String): String =
            java.net.URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

        private val SHA_256 = Regex("^[0-9a-f]{64}$")
        private val ID_PATTERN = Regex("^[A-Za-z0-9._:-]{1,128}$")
        private val VOICE_MIME_TYPES = setOf(
            "audio/mp4",
            "audio/aac",
            "audio/ogg",
            "audio/webm",
            "audio/wav",
        )
        private const val STATUS_COMPLETED = "COMPLETED"
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val MIN_CHUNK_BYTES = 64 * 1024
        private const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ERROR_TEXT = 1_024
    }
}
