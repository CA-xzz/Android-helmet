package com.example.helmet.service.runtime

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.core.model.CallState
import com.example.helmet.core.model.HelmetOperationalState
import com.example.helmet.core.model.StreamState
import com.example.helmet.data.local.CallStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.hardware.api.SimulatedInput
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HardwareCallToggleInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun foregroundServiceRecoversFirstCallKeyAndSecondKeyEndsIt() = runBlocking {
        val configStore = RuntimeConfigStore(context)
        val previousConfig = configStore.load()
        val calls = CallStore(HelmetDatabase.get(context))
        assertNull(calls.active())
        try {
            val testConfig = configStore.update { current ->
                current.copy(
                    simulatorEnabled = true,
                    backendBaseUrl = "",
                    backendBearerToken = "",
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                    localIntercom = current.localIntercom.copy(enabled = false),
                )
            }
            val firstBaseline = restartService()
            awaitRuntime(testConfig.revision, firstBaseline)

            sendSimulatedInput(SimulatedInput.CALL)
            val requested = withTimeout(CALL_STATE_TIMEOUT_MILLIS) {
                var active = calls.active()
                while (active?.state != CallState.REQUESTED) {
                    delay(POLL_INTERVAL_MILLIS)
                    active = calls.active()
                }
                checkNotNull(active)
            }
            assertEquals(StreamState.IDLE, RuntimeStatus.snapshot.value.streamState)
            assertFalse(RuntimeStatus.snapshot.value.streamAudioEnabled)
            assertFalse(RuntimeStatus.snapshot.value.streamVideoEnabled)

            val recoveryBaseline = restartService()
            awaitRuntime(testConfig.revision, recoveryBaseline)
            withTimeout(CALL_STATE_TIMEOUT_MILLIS) {
                RuntimeStatus.snapshot.first { snapshot ->
                    snapshot.activeCallId == requested.callId &&
                        snapshot.state == HelmetOperationalState.CALLING
                }
            }

            sendSimulatedInput(SimulatedInput.CALL)
            withTimeout(CALL_STATE_TIMEOUT_MILLIS) {
                while (calls.find(requested.callId)?.state != CallState.ENDED) {
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
            assertNull(calls.active())
            assertEquals(StreamState.IDLE, RuntimeStatus.snapshot.value.streamState)
        } finally {
            withContext(NonCancellable) {
                context.stopService(HelmetService.startIntent(context))
                delay(SERVICE_RESTART_DELAY_MILLIS)
                configStore.update { current -> previousConfig.copy(revision = current.revision) }
            }
        }
    }

    private suspend fun restartService(): Int {
        val persistedEventBaseline = RuntimeStatus.snapshot.value.persistedEventCount
        context.stopService(HelmetService.startIntent(context))
        delay(SERVICE_RESTART_DELAY_MILLIS)
        startHelmetService()
        return persistedEventBaseline
    }

    private fun startHelmetService() {
        val output = executeShellCommand(
            "am start-foreground-service --user 0 " +
                "-n ${context.packageName}/${HelmetService::class.java.name}",
        )
        check("Starting service" in output) { "service start failed: $output" }
    }

    private fun sendSimulatedInput(input: SimulatedInput) {
        val output = executeShellCommand(
            "am start-foreground-service --user 0 " +
                "-a ${HelmetService.ACTION_SIMULATE} " +
                "-n ${context.packageName}/${HelmetService::class.java.name} " +
                "--es ${HelmetService.EXTRA_SIMULATED_INPUT} ${input.name}",
        )
        check("Starting service" in output) { "simulation service intent failed: $output" }
    }

    private fun executeShellCommand(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    private suspend fun awaitRuntime(configRevision: Long, persistedEventBaseline: Int) {
        withTimeout(SERVICE_START_TIMEOUT_MILLIS) {
            RuntimeStatus.snapshot.first { snapshot ->
                snapshot.configRevision == configRevision &&
                    snapshot.hardwareMode == "SIMULATED" &&
                    snapshot.hardwareConnected &&
                    snapshot.persistedEventCount > persistedEventBaseline
            }
        }
    }

    companion object {
        private const val SERVICE_START_TIMEOUT_MILLIS = 30_000L
        private const val CALL_STATE_TIMEOUT_MILLIS = 15_000L
        private const val SERVICE_RESTART_DELAY_MILLIS = 500L
        private const val POLL_INTERVAL_MILLIS = 100L
    }
}
