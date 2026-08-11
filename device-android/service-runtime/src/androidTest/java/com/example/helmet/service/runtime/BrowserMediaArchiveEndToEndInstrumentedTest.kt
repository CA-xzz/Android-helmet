package com.example.helmet.service.runtime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BrowserMediaArchiveEndToEndInstrumentedTest {
    @Test
    fun uploadsDeviceGeneratedPhotoForAuthenticatedBrowserPreview() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val endpoint = arguments.getString(ARG_ENDPOINT).orEmpty()
        val token = arguments.getString(ARG_DEVICE_TOKEN).orEmpty()
        val deviceId = arguments.getString(ARG_DEVICE_ID).orEmpty()
        val mediaId = arguments.getString(ARG_MEDIA_ID).orEmpty()
        val createdAtEpochMillis = arguments.getString(ARG_CREATED_AT)?.toLongOrNull()
        assumeTrue(
            "browser media E2E requires endpoint, device token, device ID, media ID and creation time",
            listOf(endpoint, token, deviceId, mediaId).all(String::isNotBlank) &&
                createdAtEpochMillis != null && createdAtEpochMillis > 0,
        )

        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val mediaDirectory = File(context.filesDir, "media/photo").apply { check(mkdirs() || isDirectory) }
        val file = File(mediaDirectory, "$mediaId.jpg")
        writeTestPhoto(file, mediaId)
        val asset = MediaAsset(
            assetId = mediaId,
            kind = MediaKind.PHOTO,
            filePath = file.absolutePath,
            mimeType = "image/jpeg",
            byteSize = file.length(),
            sha256 = MediaIntegrity.sha256(file),
            width = PHOTO_WIDTH,
            height = PHOTO_HEIGHT,
            durationMillis = null,
            createdAtEpochMillis = requireNotNull(createdAtEpochMillis),
            deviceId = deviceId,
            relatedEventId = "browser-media-e2e",
            transferState = MediaTransferState.PENDING,
            attemptCount = 0,
            locationFixType = "NO_FIX",
        )
        val store = MediaStore(HelmetDatabase.get(context))
        try {
            assertTrue(store.add(asset))
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = endpoint,
                    backendBearerToken = token,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                ),
            )
            val result = TestListenableWorkerBuilder<MediaUploadWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            val delivered = store.find(mediaId)
            assertEquals(MediaTransferState.DELIVERED, delivered?.transferState)
            assertEquals(1, delivered?.attemptCount)
            assertEquals(asset.sha256, delivered?.sha256)
            assertEquals(asset.byteSize, delivered?.byteSize)
        } finally {
            configStore.save(originalConfig)
            file.delete()
        }
    }

    private fun writeTestPhoto(file: File, mediaId: String) {
        val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(242, 246, 248))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.rgb(20, 42, 52)
            canvas.drawRect(0f, 0f, PHOTO_WIDTH.toFloat(), 112f, paint)
            paint.color = Color.rgb(230, 157, 53)
            canvas.drawRect(0f, 112f, PHOTO_WIDTH.toFloat(), 126f, paint)
            paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            paint.textSize = 44f
            paint.color = Color.WHITE
            canvas.drawText("ANDROID HELMET", 34f, 72f, paint)
            paint.textSize = 34f
            paint.color = Color.rgb(20, 42, 52)
            canvas.drawText("H618 MEDIA ARCHIVE E2E", 34f, 190f, paint)
            paint.typeface = Typeface.DEFAULT
            paint.textSize = 23f
            canvas.drawText(mediaId.take(42), 34f, 235f, paint)
            paint.color = Color.rgb(42, 111, 151)
            canvas.drawRect(34f, 270f, 214f, 332f, paint)
            paint.color = Color.rgb(63, 139, 107)
            canvas.drawRect(230f, 270f, 410f, 332f, paint)
            paint.color = Color.rgb(202, 74, 67)
            canvas.drawRect(426f, 270f, 606f, 332f, paint)
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                    "failed to encode browser media test photo"
                }
                output.fd.sync()
            }
        } finally {
            bitmap.recycle()
        }
        check(file.isFile && file.length() > 0) { "browser media test photo was not created" }
    }

    companion object {
        private const val ARG_ENDPOINT = "backendEndpoint"
        private const val ARG_DEVICE_TOKEN = "deviceToken"
        private const val ARG_DEVICE_ID = "deviceId"
        private const val ARG_MEDIA_ID = "mediaId"
        private const val ARG_CREATED_AT = "createdAtEpochMillis"
        private const val PHOTO_WIDTH = 640
        private const val PHOTO_HEIGHT = 360
    }
}
