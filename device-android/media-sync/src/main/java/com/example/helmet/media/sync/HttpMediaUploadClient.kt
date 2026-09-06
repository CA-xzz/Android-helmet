package com.example.helmet.media.sync

import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.feature.connectivity.HttpConnectionPolicy
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.io.InputStream
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
    private val contentSource: MediaContentSource = PlainFileMediaContentSource,
) : MediaUploadTransport {
    private val endpoint = validateAndNormalizeBaseUrl(baseUrl)

    override suspend fun upload(asset: MediaAsset): MediaUploadReceipt = withContext(Dispatchers.IO) {
        validateLocalAsset(asset)
        val session = createOrResumeSession(asset)
        if (session.status == STATUS_COMPLETED) {
            return@withContext session.toReceipt(asset, bytesUploaded = 0)
        }

        var offset = session.nextOffset
        var bytesUploaded = 0L
        contentSource.open(asset, offset).use { source ->
            while (offset < asset.byteSize) {
                val requested = minOf(session.chunkSize.toLong(), asset.byteSize - offset).toInt()
                val bytes = ByteArray(requested)
                source.readFully(bytes)
                val nextOffset = requireExactNextOffset(asset.assetId, offset, bytes.size, uploadChunk(
                    session.sessionId,
                    offset,
                    asset.byteSize,
                    bytes,
                ))
                bytesUploaded += nextOffset - offset
                offset = nextOffset
            }
        }
        completeSession(session.sessionId, asset).toReceipt(asset, bytesUploaded)
    }

    private fun validateLocalAsset(asset: MediaAsset) {
        validateMetadata(asset)
        val integrity = try {
            contentSource.inspect(asset)
        } catch (error: IOException) {
            throw MediaUploadException("media file is unavailable: ${asset.assetId}", retryable = false, cause = error)
        } catch (error: SecurityException) {
            throw MediaUploadException("media file authentication failed: ${asset.assetId}", retryable = false, cause = error)
        } catch (error: IllegalArgumentException) {
            throw MediaUploadException("media file is invalid: ${asset.assetId}", retryable = false, cause = error)
        } catch (error: IllegalStateException) {
            throw MediaUploadException("media file is invalid: ${asset.assetId}", retryable = false, cause = error)
        }
        if (integrity.byteSize != asset.byteSize) {
            throw MediaUploadException("media size changed: ${asset.assetId}", retryable = false)
        }
        if (integrity.sha256 != asset.sha256) {
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
    }

    private fun InputStream.readFully(destination: ByteArray) {
        var offset = 0
        while (offset < destination.size) {
            val count = read(destination, offset, destination.size - offset)
            if (count < 0) throw MediaUploadException("media ended before its stored size", retryable = false)
            offset += count
        }
    }

    private fun createOrResumeSession(asset: MediaAsset): SessionResponse {
        val request = metadataJson(asset)
        return parseSession(
            requestJson("POST", "/v1/media/sessions", request.toString().toByteArray(Charsets.UTF_8)),
            asset,
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
        return responseInteger(response, "nextOffset")
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
            asset,
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

    private fun parseSession(json: JSONObject, asset: MediaAsset): SessionResponse = try {
        val archiveValue = json.opt("archiveId")
        val deduplicatedValue = json.opt("deduplicated")
        SessionResponse(
            sessionId = json.getString("sessionId"),
            nextOffset = responseInteger(json, "nextOffset"),
            chunkSize = responseInteger(json, "chunkSize").let { value ->
                require(value <= Int.MAX_VALUE)
                value.toInt()
            },
            status = json.getString("status"),
            archiveId = when (archiveValue) {
                null, JSONObject.NULL -> null
                is String -> archiveValue.takeIf(String::isNotBlank)
                else -> error("archiveId is not a string")
            },
            deduplicated = when (deduplicatedValue) {
                null -> false
                is Boolean -> deduplicatedValue
                else -> error("deduplicated is not a boolean")
            },
        ).also { response ->
            require(response.sessionId.matches(ID_PATTERN))
            require(response.chunkSize in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES)
            require(response.status in setOf(STATUS_UPLOADING, STATUS_COMPLETED))
            require(response.nextOffset in 0..asset.byteSize)
            if (response.status == STATUS_COMPLETED) {
                require(response.nextOffset == asset.byteSize && !response.archiveId.isNullOrBlank())
            } else {
                require(response.archiveId == null)
            }
        }
    } catch (error: MediaUploadException) {
        throw error
    } catch (error: Throwable) {
        throw MediaUploadException(
            "media server returned an invalid session for ${asset.assetId}",
            retryable = false,
            cause = error,
        )
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
        internal fun validateMetadata(asset: MediaAsset) {
            fun rejectUnless(condition: Boolean, detail: String) {
                if (!condition) {
                    throw MediaUploadException(
                        "media metadata is invalid for ${asset.assetId}: $detail",
                        retryable = false,
                    )
                }
            }

            val relatedEventId = asset.relatedEventId
            val personId = asset.personId
            rejectUnless(asset.assetId.matches(ID_PATTERN), "assetId")
            rejectUnless(asset.deviceId.matches(ID_PATTERN), "deviceId")
            rejectUnless(relatedEventId == null || relatedEventId.matches(ID_PATTERN), "relatedEventId")
            rejectUnless(personId == null || personId.matches(ID_PATTERN), "personId")
            rejectUnless(asset.byteSize in 1..MAX_MEDIA_BYTES, "byteSize")
            rejectUnless(SHA_256.matches(asset.sha256), "sha256")
            rejectUnless(asset.createdAtEpochMillis > 0, "createdAtEpochMillis")

            if (asset.kind == MediaKind.VOICE) {
                val durationMillis = asset.durationMillis
                val voiceCallId = asset.voiceCallId
                val valid = asset.mimeType in VOICE_MIME_TYPES &&
                    asset.width == 0 && asset.height == 0 &&
                    durationMillis != null && durationMillis in 1..MAX_DURATION_MILLIS &&
                    asset.voiceSenderId?.matches(ID_PATTERN) == true &&
                    asset.voiceSenderRole != null && asset.voiceAllowedRoles.isNotEmpty() &&
                    (voiceCallId == null || voiceCallId.matches(ID_PATTERN))
                rejectUnless(valid, "voice fields")
                return
            }

            rejectUnless(
                asset.mimeType == if (asset.kind == MediaKind.PHOTO) "image/jpeg" else "video/mp4",
                "mimeType",
            )
            rejectUnless(asset.width in 1..MAX_DIMENSION && asset.height in 1..MAX_DIMENSION, "dimensions")
            rejectUnless(
                if (asset.kind == MediaKind.PHOTO) asset.durationMillis == null
                else asset.durationMillis?.let { it in 1..MAX_DURATION_MILLIS } == true,
                "durationMillis",
            )
            rejectUnless(asset.voiceSenderId == null && asset.voiceSenderRole == null, "voice sender fields")
            rejectUnless(asset.voiceAllowedRoles.isEmpty() && asset.voiceCallId == null, "voice authorization fields")
            rejectUnless(asset.locationFixType in LOCATION_FIX_TYPES, "locationFixType")
            val latitude = asset.latitude
            val longitude = asset.longitude
            val accuracy = asset.horizontalAccuracyMeters
            val hasLatitude = latitude != null
            val hasLongitude = longitude != null
            rejectUnless(hasLatitude == hasLongitude, "coordinates")
            rejectUnless(latitude == null || latitude.isFinite() && latitude in -90.0..90.0, "latitude")
            rejectUnless(longitude == null || longitude.isFinite() && longitude in -180.0..180.0, "longitude")
            rejectUnless(
                accuracy == null || accuracy.isFinite() && accuracy >= 0f,
                "horizontalAccuracyMeters",
            )
            rejectUnless(asset.locationFixType != "NO_FIX" || !hasLatitude, "NO_FIX coordinates")
            rejectUnless(asset.locationFixType == "NO_FIX" || hasLatitude, "position coordinates")
        }

        internal fun requireExactNextOffset(
            assetId: String,
            offset: Long,
            chunkBytes: Int,
            nextOffset: Long,
        ): Long {
            val expected = try {
                Math.addExact(offset, chunkBytes.toLong())
            } catch (error: ArithmeticException) {
                throw MediaUploadException("media offset overflow for $assetId", retryable = false, cause = error)
            }
            if (nextOffset != expected) {
                throw MediaUploadException(
                    "server returned invalid next offset $nextOffset for $assetId; expected $expected",
                    retryable = false,
                )
            }
            return nextOffset
        }

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
        private val LOCATION_FIX_TYPES = setOf(
            "NO_FIX",
            "UNVALIDATED",
            "STANDARD",
            "DIFFERENTIAL",
            "RTK_FLOAT",
            "RTK_FIXED",
            "DEAD_RECKONING",
        )
        private val VOICE_MIME_TYPES = setOf(
            "audio/mp4",
            "audio/aac",
            "audio/ogg",
            "audio/webm",
            "audio/wav",
        )
        private const val STATUS_COMPLETED = "COMPLETED"
        private const val STATUS_UPLOADING = "UPLOADING"
        private const val MAX_DIMENSION = 32_768
        private const val MAX_DURATION_MILLIS = 24L * 60 * 60 * 1_000
        private const val MAX_MEDIA_BYTES = 8L * 1024 * 1024 * 1024
        private const val DEFAULT_CONNECT_TIMEOUT_MILLIS = 15_000
        private const val DEFAULT_READ_TIMEOUT_MILLIS = 30_000
        private const val MIN_CHUNK_BYTES = 64 * 1024
        private const val MAX_CHUNK_BYTES = 4 * 1024 * 1024
        private const val MAX_RESPONSE_BYTES = 256 * 1024
        private const val MAX_ERROR_TEXT = 1_024

        private fun responseInteger(json: JSONObject, field: String): Long {
            val value = json.opt(field)
            if (value !is Number || value is Float || value is Double) {
                throw IllegalArgumentException("$field is not an integer")
            }
            return value.toLong()
        }
    }
}

private object PlainFileMediaContentSource : MediaContentSource {
    override fun inspect(asset: MediaAsset): MediaContentIntegrity {
        val file = File(asset.filePath)
        require(file.isFile) { "media file is missing" }
        return MediaContentIntegrity(file.length(), MediaIntegrity.sha256(file))
    }

    override fun open(asset: MediaAsset, offset: Long): InputStream = FileInputStream(asset.filePath).also { input ->
        var remaining = offset
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                input.close()
                throw IOException("media offset exceeds file length")
            }
            remaining -= skipped
        }
    }
}
