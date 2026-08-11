package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.helmet.core.model.MediaAsset
import com.example.helmet.core.model.MediaKind
import com.example.helmet.core.model.MediaTransferState
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaBatchContinuationInstrumentedTest {
    @Test
    fun workManagerAppendsAndRunsEveryMediaBatch() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val mediaStore = MediaStore(HelmetDatabase.get(context))
        val workManager = WorkManager.getInstance(context)
        val route = backendWorkRoute(MEDIA_WORK_BASE_NAME, LOOPBACK_ENDPOINT)
        val previousWorkIds = workManager.workInfos(route.activeName).map { item -> item.id }.toSet()
        workManager.cancelUniqueWork(route.activeName).result.await()
        workManager.cancelUniqueWork(route.staleName).result.await()

        val nonce = UUID.randomUUID().toString()
        val assetIds = (0 until ASSET_COUNT).map { index ->
            val assetId = "batch-$nonce-$index"
            assertTrue(
                mediaStore.add(
                    MediaAsset(
                        assetId = assetId,
                        kind = MediaKind.VIDEO,
                        filePath = File(context.cacheDir, "$assetId.mp4").absolutePath,
                        mimeType = "video/mp4",
                        byteSize = 1,
                        sha256 = "0".repeat(64),
                        width = 1_920,
                        height = 1_080,
                        durationMillis = 1_000,
                        createdAtEpochMillis = System.currentTimeMillis() + index,
                        deviceId = "batch-device-$nonce",
                        relatedEventId = null,
                        transferState = MediaTransferState.PENDING,
                        attemptCount = 0,
                    ),
                ),
            )
            assetId
        }

        try {
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = LOOPBACK_ENDPOINT,
                    backendBearerToken = "batch-test-token",
                ),
            )
            MediaUploadWorker.enqueue(context)

            withTimeout(WORK_TIMEOUT_MILLIS) {
                while (assetIds.any { id -> mediaStore.find(id)?.transferState != MediaTransferState.REJECTED }) {
                    delay(WORK_POLL_MILLIS)
                }
            }

            withTimeout(WORK_TIMEOUT_MILLIS) {
                var work = workManager.workInfos(route.activeName)
                    .filterNot { item -> item.id in previousWorkIds }
                while (work.size < 2 || work.any { item -> !item.state.isFinished }) {
                    delay(WORK_POLL_MILLIS)
                    work = workManager.workInfos(route.activeName)
                        .filterNot { item -> item.id in previousWorkIds }
                }
                assertTrue(work.all { item -> item.state == WorkInfo.State.SUCCEEDED })
            }
            assetIds.forEach { id ->
                assertEquals(MediaTransferState.REJECTED, mediaStore.find(id)?.transferState)
                assertEquals(0, mediaStore.find(id)?.attemptCount)
            }
        } finally {
            configStore.save(originalConfig)
            workManager.cancelUniqueWork(route.activeName).result.await()
        }
    }

    private suspend fun WorkManager.workInfos(name: String): List<WorkInfo> =
        withContext(Dispatchers.IO) { getWorkInfosForUniqueWork(name).get() }

    private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(): T =
        withContext(Dispatchers.IO) { get() }

    companion object {
        private const val MEDIA_WORK_BASE_NAME = "helmet-media-upload"
        private const val LOOPBACK_ENDPOINT = "http://127.0.0.1:1"
        private const val ASSET_COUNT = 101
        private const val WORK_TIMEOUT_MILLIS = 30_000L
        private const val WORK_POLL_MILLIS = 50L
    }
}
