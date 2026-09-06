package com.example.helmet

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.MediaStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.testfixture.PersistentPreferencesTestGuard
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraMissingProductionPathInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun servicePersistsPhotoAndVideoFailuresWithoutCreatingMedia() = runBlocking {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        assumeTrue("this test requires a board without an enumerated camera", cameraManager.cameraIdList.isEmpty())
        PersistentPreferencesTestGuard.restoreStale(context, RECOVERY_EVIDENCE_PREFERENCES)
        val stateGuard = PersistentPreferencesTestGuard.capture(
            context,
            RECOVERY_EVIDENCE_PREFERENCES,
            listOf(RUNTIME_CONFIG_PREFERENCES),
        )
        val configStore = RuntimeConfigStore(context)
        val eventStore = EventStore(HelmetDatabase.get(context))
        val mediaStore = MediaStore(HelmetDatabase.get(context))
        val initialAssetIds = mediaStore.all().mapTo(mutableSetOf()) { it.assetId }
        val startupBaseline = eventStore.observeRecent(EVENT_SCAN_LIMIT).first().mapTo(mutableSetOf()) { it.messageId }
        try {
            context.stopService(HelmetService.startIntent(context))
            delay(SERVICE_RESTART_DELAY_MILLIS)
            configStore.update { current -> current.copy(simulatorEnabled = true) }
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            awaitNewEvent(eventStore, "RUNTIME_STARTED", startupBaseline) { event ->
                JSONObject(event.payloadJson).optString("hardwareMode") == "SIMULATED"
            }

            val photoBaseline = eventStore.observeRecent(EVENT_SCAN_LIMIT).first().mapTo(mutableSetOf()) { it.messageId }
            sendSimulatedInput(SimulatedInput.PHOTO_SHORT)
            val photoFailure = awaitNewEvent(eventStore, "PHOTO_CAPTURE_FAILED", photoBaseline)
            assertEquals(EventSeverity.MEDIUM, photoFailure.severity)
            assertTrue(JSONObject(photoFailure.payloadJson).getString("errorType").endsWith("CameraOperationException"))

            val videoBaseline = eventStore.observeRecent(EVENT_SCAN_LIMIT).first().mapTo(mutableSetOf()) { it.messageId }
            sendSimulatedInput(SimulatedInput.RECORD_LONG)
            val videoFailure = awaitNewEvent(eventStore, "VIDEO_RECORDING_START_FAILED", videoBaseline)
            assertEquals(EventSeverity.MEDIUM, videoFailure.severity)
            assertTrue(JSONObject(videoFailure.payloadJson).getString("errorType").endsWith("CameraOperationException"))
            assertEquals(initialAssetIds, mediaStore.all().mapTo(mutableSetOf()) { it.assetId })
        } finally {
            withContext(NonCancellable) {
                context.stopService(HelmetService.startIntent(context))
                delay(SERVICE_RESTART_DELAY_MILLIS)
                try {
                    stateGuard.restore()
                } finally {
                    ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
                }
            }
        }
    }

    private suspend fun awaitNewEvent(
        eventStore: EventStore,
        eventType: String,
        baselineMessageIds: Set<String>,
        predicate: (com.example.helmet.core.model.HelmetEvent) -> Boolean = { true },
    ): com.example.helmet.core.model.HelmetEvent {
        val deadline = SystemClock.elapsedRealtime() + EVENT_TIMEOUT_MILLIS
        while (SystemClock.elapsedRealtime() < deadline) {
            eventStore.observeRecent(EVENT_SCAN_LIMIT).first().firstOrNull { event ->
                event.eventType == eventType && event.messageId !in baselineMessageIds && predicate(event)
            }?.let { return it }
            delay(EVENT_POLL_MILLIS)
        }
        val recentTypes = eventStore.observeRecent(20).first().joinToString(",") { it.eventType }
        throw AssertionError("timed out waiting for $eventType; recent=$recentTypes")
    }

    private fun sendSimulatedInput(input: SimulatedInput) {
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start-foreground-service --user 0 " +
                    "-a ${HelmetService.ACTION_SIMULATE} " +
                    "-n com.example.helmet/com.example.helmet.service.runtime.HelmetService " +
                    "--es ${HelmetService.EXTRA_SIMULATED_INPUT} ${input.name}",
            ),
        ).bufferedReader().use { it.readText() }
        check("Starting service" in output) { output }
    }

    companion object {
        private const val RUNTIME_CONFIG_PREFERENCES = "helmet_runtime_config"
        private const val RECOVERY_EVIDENCE_PREFERENCES = "camera_missing_path_test_recovery"
        private const val SERVICE_RESTART_DELAY_MILLIS = 1_000L
        private const val EVENT_TIMEOUT_MILLIS = 15_000L
        private const val EVENT_POLL_MILLIS = 100L
        private const val EVENT_SCAN_LIMIT = 500
    }
}
