package com.example.helmet.feature.camera

import android.content.Context
import android.hardware.camera2.CameraManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

@RunWith(AndroidJUnit4::class)
class CameraCapabilityInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: HelmetDatabase
    private lateinit var controller: Camera2MediaController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, HelmetDatabase::class.java).build()
        controller = Camera2MediaController(context, MediaStore(database), "test-device")
    }

    @After
    fun tearDown() {
        controller.close()
        database.close()
    }

    @Test
    fun capabilityReportMatchesCameraManager() {
        val manager = context.getSystemService(CameraManager::class.java)
        val capabilities = controller.inspect()

        assertEquals(manager.cameraIdList.size, capabilities.cameraCount)
        if (capabilities.cameraCount == 0) {
            assertNull(capabilities.selectedCameraId)
            assertNull(capabilities.maximumJpegSize)
            assertNull(capabilities.selectedVideoSize)
        } else if (capabilities.selectedCameraId != null) {
            assertNotNull(capabilities.maximumJpegSize)
            assertNotNull(capabilities.selectedVideoSize)
        }
    }

    @Test
    fun noCameraFailureDoesNotCreatePartialMedia() = runBlocking {
        if (controller.inspect().cameraCount != 0) return@runBlocking

        val failure = runCatching { controller.capturePhoto() }.exceptionOrNull()

        assertTrue(failure is CameraOperationException)
        assertTrue(failure.toString().contains("no camera"))
        val mediaRoot = context.filesDir.resolve("media")
        assertTrue(mediaRoot.walkTopDown().none { file -> file.isFile && file.name.endsWith(".partial") })
    }
}
