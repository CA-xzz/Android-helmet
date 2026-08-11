package com.example.helmet

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.service.runtime.CrashCapture
import com.example.helmet.service.runtime.HelmetService
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeRecoveryInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recoveryRequestIsPersistedWithBoundedMetadata() = runBlocking {
        val source = "instrumentation-${UUID.randomUUID()}"
        val eventStore = EventStore(HelmetDatabase.get(context))
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        val commandOutput = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "am start-foreground-service --user 0 " +
                    "-a ${HelmetService.ACTION_RECOVERY} " +
                    "-n com.example.helmet/com.example.helmet.service.runtime.HelmetService " +
                    "--es ${HelmetService.EXTRA_RECOVERY_SOURCE} $source " +
                    "--ei ${HelmetService.EXTRA_RECOVERY_ATTEMPT} 7",
            ),
        ).bufferedReader().use { it.readText() }
        check("Starting service" in commandOutput) { commandOutput }

        val deadline = SystemClock.elapsedRealtime() + 10_000
        var event = eventStore.observeRecent(limit = 500).first().firstOrNull { candidate ->
            candidate.eventType == "RUNTIME_RECOVERY_REQUESTED" &&
                JSONObject(candidate.payloadJson).optString("source") == source
        }
        while (event == null && SystemClock.elapsedRealtime() < deadline) {
            delay(100)
            event = eventStore.observeRecent(limit = 500).first().firstOrNull { candidate ->
                candidate.eventType == "RUNTIME_RECOVERY_REQUESTED" &&
                    JSONObject(candidate.payloadJson).optString("source") == source
            }
        }
        val persistedEvent = checkNotNull(event) { "recovery event not persisted for source=$source" }
        val payload = JSONObject(persistedEvent.payloadJson)
        assertEquals(EventSeverity.MEDIUM, persistedEvent.severity)
        assertEquals(source, payload.getString("source"))
        assertEquals(7, payload.getInt("attempt"))
        assertEquals(false, payload.getBoolean("retry"))
        assertEquals(false, payload.getBoolean("redelivery"))
    }

    @Test
    fun crashCaptureInstallationIsIdempotent() {
        val eventStore = EventStore(HelmetDatabase.get(context))
        CrashCapture.install(eventStore)
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashCapture.install(eventStore)
        val second = Thread.getDefaultUncaughtExceptionHandler()

        assertSame(first, second)
    }
}
