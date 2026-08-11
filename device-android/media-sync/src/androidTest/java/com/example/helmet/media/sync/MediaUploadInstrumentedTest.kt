package com.example.helmet.media.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class MediaUploadInstrumentedTest {
    @Test
    fun uploadIsResumableIntegrityCheckedAndIdempotent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val nonce = UUID.randomUUID().toString().toByteArray()
        val bytes = ByteArray(700_000) { index ->
            ((index % 251) xor nonce[index % nonce.size].toInt()).toByte()
        }
        val file = File(context.cacheDir, "media-upload-${UUID.randomUUID()}.mp4")
        file.writeBytes(bytes)
        val asset = MediaAsset(
            assetId = "android-${UUID.randomUUID()}",
            kind = MediaKind.VIDEO,
            filePath = file.absolutePath,
            mimeType = "video/mp4",
            byteSize = file.length(),
            sha256 = MediaIntegrity.sha256(file),
            width = 1_920,
            height = 1_080,
            durationMillis = 1_000,
            createdAtEpochMillis = System.currentTimeMillis(),
            deviceId = "board-2c001031774186e21d3",
            relatedEventId = "instrumentation-event",
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            locationFixType = "NO_FIX",
        )

        try {
            val client = HttpMediaUploadClient(TEST_ENDPOINT, TEST_TOKEN)
            val primedBytes = primeOneChunk(asset, bytes)
            val first = client.upload(asset)
            assertEquals(asset.assetId, first.archiveId)
            assertEquals(asset.sha256, first.contentSha256)
            assertEquals(asset.byteSize - primedBytes, first.bytesUploaded)

            val repeated = client.upload(asset)
            assertEquals(asset.assetId, repeated.archiveId)
            assertEquals(0, repeated.bytesUploaded)
            assertTrue(repeated.deduplicated)
        } finally {
            file.delete()
        }
    }

    private fun primeOneChunk(asset: MediaAsset, bytes: ByteArray): Long {
        val metadata = JSONObject()
            .put("mediaId", asset.assetId)
            .put("deviceId", asset.deviceId)
            .put("kind", asset.kind.name)
            .put("mimeType", asset.mimeType)
            .put("byteSize", asset.byteSize)
            .put("sha256", asset.sha256)
            .put("width", asset.width)
            .put("height", asset.height)
            .put("durationMillis", asset.durationMillis)
            .put("createdAtEpochMillis", asset.createdAtEpochMillis)
            .put("personId", JSONObject.NULL)
            .put("relatedEventId", asset.relatedEventId)
            .put(
                "location",
                JSONObject()
                    .put("latitude", JSONObject.NULL)
                    .put("longitude", JSONObject.NULL)
                    .put("horizontalAccuracyMeters", JSONObject.NULL)
                    .put("fixType", "NO_FIX"),
            )
        val session = requestJson("POST", "/v1/media/sessions", metadata.toString().toByteArray())
        val chunkSize = session.getInt("chunkSize")
        val chunk = bytes.copyOfRange(0, minOf(chunkSize, bytes.size))
        val end = chunk.size - 1
        val response = requestJson(
            method = "PUT",
            path = "/v1/media/sessions/${session.getString("sessionId")}/chunks",
            body = chunk,
            contentType = "application/octet-stream",
            headers = mapOf(
                "Content-Range" to "bytes 0-$end/${bytes.size}",
                "X-Chunk-SHA256" to MediaIntegrity.sha256(chunk),
            ),
        )
        assertEquals(chunk.size.toLong(), response.getLong("nextOffset"))
        return chunk.size.toLong()
    }

    private fun requestJson(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String = "application/json; charset=utf-8",
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val connection = URL(TEST_ENDPOINT + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
            connection.setRequestProperty("Content-Type", contentType)
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(body) }
            assertEquals(200, connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18080"
        private const val TEST_TOKEN = "stage3-board-integration-token"
    }
}
