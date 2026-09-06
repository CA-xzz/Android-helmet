package com.example.helmet

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.RuntimeStatus
import com.example.helmet.testfixture.PersistentPreferencesTestGuard
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticSafetyAlertUploadInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val backendBearerToken = requireNonBlankBoardTestArgument(
        InstrumentationRegistry.getArguments().getString(BACKEND_BEARER_TOKEN_ARGUMENT),
        BACKEND_BEARER_TOKEN_ARGUMENT,
    )

    @Test
    fun foregroundServicePersistsAndAutomaticallyUploadsSimulatedSafetyAlerts() = runBlocking {
        PersistentPreferencesTestGuard.restoreStale(context, RECOVERY_EVIDENCE_PREFERENCES)
        val configStore = RuntimeConfigStore(context)
        val configGuard = PersistentPreferencesTestGuard.capture(
            context,
            RECOVERY_EVIDENCE_PREFERENCES,
            CONFIG_PREFERENCE_FILES,
        )
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val safetyStore = SafetyStore(HelmetDatabase.get(context))
        try {
            val testConfig = configStore.update { current ->
                current.copy(
                    simulatorEnabled = true,
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = backendBearerToken,
                    mqttBrokerUri = "",
                    mqttClientCertificateAlias = "",
                )
            }
            context.stopService(HelmetService.startIntent(context))
            delay(SERVICE_RESTART_DELAY_MILLIS)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            withTimeout(SERVICE_START_TIMEOUT_MILLIS) {
                RuntimeStatus.snapshot.first { snapshot ->
                    snapshot.configRevision == testConfig.revision &&
                        snapshot.hardwareConnected &&
                        snapshot.hardwareMode == "SIMULATED" &&
                        snapshot.lastEventType == "RUNTIME_STARTED"
                }
            }
            delay(SERVICE_READY_SETTLE_MILLIS)
            withTimeout(AUTOMATIC_UPLOAD_TIMEOUT_MILLIS) {
                while (safetyStore.pendingAlertCount() != 0) delay(POLL_INTERVAL_MILLIS)
            }
            val fall = uploadAndAwait(
                deviceId = deviceId,
                safetyStore = safetyStore,
                input = SimulatedInput.FALL,
                expectedType = "FALL",
            )
            assertCommonAlert(fall, expectedSeverity = "CRITICAL")
            assertEquals(0x0001, fall.uploaded.getJSONObject("sensorSnapshot").getInt("validFlags"))
            assertEquals(2_400, fall.uploaded.getJSONObject("sensorSnapshot").getInt("accelerationXMilliG"))
            assertEquals(
                "ANDROID_DETECTION",
                fall.uploaded.getJSONObject("sensorSnapshot").getString("detectionOrigin"),
            )

            val electric = uploadAndAwait(
                deviceId = deviceId,
                safetyStore = safetyStore,
                input = SimulatedInput.NEAR_ELECTRIC,
                expectedType = "NEAR_ELECTRIC",
            )
            assertCommonAlert(electric, expectedSeverity = "CRITICAL")
            val electricSnapshot = electric.uploaded.getJSONObject("sensorSnapshot")
            assertEquals(0x0002, electricSnapshot.getInt("validFlags"))
            assertEquals(920, electricSnapshot.getInt("electricFieldMilliVolts"))
            assertEquals("ANDROID_DETECTION", electricSnapshot.getString("detectionOrigin"))
            assertEquals(
                electricSnapshot.getLong("sampleReference"),
                JSONObject(electric.local.sensorSnapshotJson).getLong("sampleReference"),
            )

            val height = uploadAndAwait(
                deviceId = deviceId,
                safetyStore = safetyStore,
                input = SimulatedInput.HEIGHT_LIMIT,
                expectedType = "HEIGHT_LIMIT",
            )
            assertCommonAlert(height, expectedSeverity = "HIGH")
            val heightSnapshot = height.uploaded.getJSONObject("sensorSnapshot")
            assertEquals(0x0010, heightSnapshot.getInt("validFlags"))
            assertEquals(101_285L, heightSnapshot.getLong("pressurePascals"))
            assertTrue(heightSnapshot.isNull("altitudeMillimetres"))
            assertEquals("ANDROID_DETECTION", heightSnapshot.getString("detectionOrigin"))
            assertEquals(
                heightSnapshot.getLong("sampleReference"),
                JSONObject(height.local.sensorSnapshotJson).getLong("sampleReference"),
            )
        } finally {
            withContext(NonCancellable) {
                context.stopService(HelmetService.startIntent(context))
                delay(SERVICE_RESTART_DELAY_MILLIS)
                try {
                    configGuard.restore()
                } finally {
                    ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
                }
            }
        }
    }

    private suspend fun uploadAndAwait(
        deviceId: String,
        safetyStore: SafetyStore,
        input: SimulatedInput,
        expectedType: String,
    ): UploadedAlert {
        val baselineMessageIds = safetyStore.latestActiveAlerts(deviceId)
            .mapTo(mutableSetOf(), SafetyAlertRecord::messageId)
        Log.i(TEST_LOG_TAG, "sending simulated $expectedType to foreground service")
        sendSimulatedInput(input)
        var deliveredResult: SafetyAlertRecord? = null
        withTimeout(AUTOMATIC_UPLOAD_TIMEOUT_MILLIS) {
            while (deliveredResult?.deliveryState != DeliveryState.DELIVERED) {
                deliveredResult = safetyStore.latestActiveAlerts(deviceId)
                    .firstOrNull { alert ->
                        alert.alarmType == expectedType &&
                            alert.simulated &&
                            alert.messageId !in baselineMessageIds
                    }
                if (deliveredResult?.deliveryState != DeliveryState.DELIVERED) {
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        }
        val local = checkNotNull(deliveredResult)
        val uploaded = getAlert(local.alertId)
        assertEquals(local.alertId, uploaded.getString("alertId"))
        assertEquals(local.alarmType, uploaded.getString("type"))
        return UploadedAlert(uploaded, local)
    }

    private fun assertCommonAlert(result: UploadedAlert, expectedSeverity: String) {
        assertEquals(expectedSeverity, result.uploaded.getString("severity"))
        assertTrue(result.uploaded.getBoolean("active"))
        assertTrue(result.uploaded.getBoolean("simulated"))
        assertEquals("ACTIVATED", result.uploaded.getJSONArray("events").getJSONObject(0).getString("kind"))
        assertEquals("NO_FIX", result.uploaded.getJSONObject("location").getString("fixType"))
        assertEquals(DeliveryState.DELIVERED, result.local.deliveryState)
        assertTrue(requireNotNull(result.local.sampleReference) > 0)
    }

    private suspend fun getAlert(alertId: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (
            URL("$TEST_ENDPOINT/v1/alerts/$alertId").openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            doInput = true
            setRequestProperty("Authorization", "Bearer $backendBearerToken")
            setRequestProperty("X-Actor-Id", "automatic-safety-board")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            check(status in 200..299) { "backend HTTP $status: $text" }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun sendSimulatedInput(input: SimulatedInput) {
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am start-foreground-service " +
                    "-a ${HelmetService.ACTION_SIMULATE} " +
                    "-n ${context.packageName}/com.example.helmet.service.runtime.HelmetService " +
                    "--es ${HelmetService.EXTRA_SIMULATED_INPUT} ${input.name}",
            ),
        ).bufferedReader().use { it.readText() }
        check("Starting service" in output) { "simulation service intent failed: $output" }
    }

    private data class UploadedAlert(
        val uploaded: JSONObject,
        val local: SafetyAlertRecord,
    )

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18083"
        private const val BACKEND_BEARER_TOKEN_ARGUMENT = "backendBearerToken"
        private const val TEST_LOG_TAG = "AutomaticSafetyTest"
        private const val RECOVERY_EVIDENCE_PREFERENCES = "automatic_safety_test_recovery"
        private const val SERVICE_START_TIMEOUT_MILLIS = 30_000L
        private const val AUTOMATIC_UPLOAD_TIMEOUT_MILLIS = 30_000L
        private const val SERVICE_RESTART_DELAY_MILLIS = 500L
        private const val SERVICE_READY_SETTLE_MILLIS = 1_000L
        private const val POLL_INTERVAL_MILLIS = 250L
        private val CONFIG_PREFERENCE_FILES = listOf(
            "helmet_runtime_config",
            "helmet_backend_credentials",
            "helmet_rtk_credentials",
        )
    }
}
