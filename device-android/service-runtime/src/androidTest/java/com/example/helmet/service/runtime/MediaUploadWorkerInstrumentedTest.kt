package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.media.sync.MediaIntegrity
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaUploadWorkerInstrumentedTest {
    @Test
    fun workerUploadsPendingPrivateMediaAndMarksItDelivered() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val configSnapshot = captureRuntimeConfigurationForTest(context)
        val mediaStore = MediaStore(HelmetDatabase.get(context))
        val mediaDirectory = File(context.filesDir, "media/video").apply { mkdirs() }
        val file = File(mediaDirectory, "worker-${UUID.randomUUID()}.mp4")
        val nonce = UUID.randomUUID().toString().toByteArray()
        file.writeBytes(
            ByteArray(300_000) { index ->
                ((index % 239) xor nonce[index % nonce.size].toInt()).toByte()
            },
        )
        val asset = MediaAsset(
            assetId = "worker-${UUID.randomUUID()}",
            kind = MediaKind.VIDEO,
            filePath = file.absolutePath,
            mimeType = "video/mp4",
            byteSize = file.length(),
            sha256 = MediaIntegrity.sha256(file),
            width = 1_920,
            height = 1_080,
            durationMillis = 2_000,
            createdAtEpochMillis = System.currentTimeMillis(),
            deviceId = "board-2c001031774186e21d3",
            relatedEventId = "worker-instrumentation-event",
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
        )
        try {
            assertTrue(mediaStore.add(asset))
            configStore.saveForInstrumentationTest(
                originalConfig.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                ),
            )
            val worker = TestListenableWorkerBuilder<MediaUploadWorker>(context).build()
            val result = worker.doWork()

            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            val delivered = mediaStore.find(asset.assetId)
            assertEquals(MediaTransferState.DELIVERED, delivered?.transferState)
            assertEquals(1, delivered?.attemptCount)
        } finally {
            restoreRuntimeConfigurationForTest(context, configSnapshot)
            file.delete()
        }
    }

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18080"
        private const val TEST_TOKEN = "stage3-board-integration-token"
    }
}
