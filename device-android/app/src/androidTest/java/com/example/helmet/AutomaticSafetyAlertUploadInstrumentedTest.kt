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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticSafetyAlertUploadInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun foregroundServicePersistsAndAutomaticallyUploadsSimulatedSafetyAlerts() = runBlocking {
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val safetyStore = SafetyStore(HelmetDatabase.get(context))
        val testConfig = originalConfig.copy(
            revision = originalConfig.revision + 1,
            simulatorEnabled = true,
            backendBaseUrl = TEST_ENDPOINT,
            backendBearerToken = TEST_TOKEN,
            mqttBrokerUri = "",
            mqttClientCertificateAlias = "",
        )
        try {
            configStore.save(testConfig)
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
            assertCommonAlert(fall)
            assertEquals(0x0001, fall.uploaded.getJSONObject("sensorSnapshot").getInt("validFlags"))
            assertEquals(2_400, fall.uploaded.getJSONObject("sensorSnapshot").getInt("accelerationXMilliG"))

            val height = uploadAndAwait(
                deviceId = deviceId,
                safetyStore = safetyStore,
                input = SimulatedInput.HEIGHT_LIMIT,
                expectedType = "HEIGHT_LIMIT",
            )
            assertCommonAlert(height)
            val heightSnapshot = height.uploaded.getJSONObject("sensorSnapshot")
            assertEquals(0x0004, heightSnapshot.getInt("validFlags"))
            assertEquals(101_325L, heightSnapshot.getLong("pressurePascals"))
            assertEquals(2_200, heightSnapshot.getInt("altitudeMillimetres"))
            assertEquals("SIMULATOR", heightSnapshot.getString("detectionOrigin"))
            assertEquals(
                heightSnapshot.getLong("sampleReference"),
                JSONObject(height.local.sensorSnapshotJson).getLong("sampleReference"),
            )
        } finally {
            configStore.save(originalConfig)
            context.stopService(HelmetService.startIntent(context))
            delay(SERVICE_RESTART_DELAY_MILLIS)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
        }
    }

    private suspend fun uploadAndAwait(
        deviceId: String,
        safetyStore: SafetyStore,
        input: SimulatedInput,
        expectedType: String,
    ): UploadedAlert {
        val baselineAlertIds = getAlerts(deviceId).ids()
        Log.i(TEST_LOG_TAG, "sending simulated $expectedType to foreground service")
        sendSimulatedInput(input)
        var uploadedResult: JSONObject? = null
        withTimeout(AUTOMATIC_UPLOAD_TIMEOUT_MILLIS) {
            while (uploadedResult == null) {
                uploadedResult = getAlerts(deviceId).values()
                    .firstOrNull { alert ->
                        alert.getString("type") == expectedType &&
                            alert.getString("alertId") !in baselineAlertIds
                    }
                if (uploadedResult == null) delay(POLL_INTERVAL_MILLIS)
            }
        }
        val uploaded = checkNotNull(uploadedResult)
        val alertId = uploaded.getString("alertId")
        var deliveredResult: SafetyAlertRecord? = null
        withTimeout(AUTOMATIC_UPLOAD_TIMEOUT_MILLIS) {
            while (deliveredResult?.deliveryState != DeliveryState.DELIVERED) {
                deliveredResult = safetyStore.latestAlert(alertId)
                if (deliveredResult?.deliveryState != DeliveryState.DELIVERED) {
                    delay(POLL_INTERVAL_MILLIS)
                }
            }
        }
        return UploadedAlert(uploaded, checkNotNull(deliveredResult))
    }

    private fun assertCommonAlert(result: UploadedAlert) {
        assertEquals("HIGH", result.uploaded.getString("severity"))
        assertEquals("OPEN", result.uploaded.getString("workflowState"))
        assertTrue(result.uploaded.getBoolean("active"))
        assertTrue(result.uploaded.getBoolean("simulated"))
        assertTrue(result.uploaded.getBoolean("requiresAttention"))
        assertTrue(result.uploaded.getJSONObject("presentation").getBoolean("sound"))
        assertFalse(result.uploaded.getJSONObject("presentation").getBoolean("mapMarker"))
        assertEquals("NO_FIX", result.uploaded.getJSONObject("location").getString("fixType"))
        assertEquals(DeliveryState.DELIVERED, result.local.deliveryState)
        assertTrue(requireNotNull(result.local.sampleReference) > 0)
    }

    private suspend fun getAlerts(deviceId: String): JSONArray = withContext(Dispatchers.IO) {
        val connection = (
            URL("$TEST_ENDPOINT/v1/alerts?deviceId=$deviceId&limit=100").openConnection() as HttpURLConnection
            ).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            doInput = true
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
            setRequestProperty("X-Actor-Id", "automatic-safety-board")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            check(status in 200..299) { "backend HTTP $status: $text" }
            JSONObject(text).getJSONArray("alerts")
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

    private fun JSONArray.values(): List<JSONObject> =
        (0 until length()).map(::getJSONObject)

    private fun JSONArray.ids(): Set<String> =
        values().mapTo(mutableSetOf()) { value -> value.getString("alertId") }

    private data class UploadedAlert(
        val uploaded: JSONObject,
        val local: SafetyAlertRecord,
    )

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18083"
        private const val TEST_TOKEN = "automatic-safety-board-token"
        private const val TEST_LOG_TAG = "AutomaticSafetyTest"
        private const val SERVICE_START_TIMEOUT_MILLIS = 30_000L
        private const val AUTOMATIC_UPLOAD_TIMEOUT_MILLIS = 30_000L
        private const val SERVICE_RESTART_DELAY_MILLIS = 500L
        private const val SERVICE_READY_SETTLE_MILLIS = 1_000L
        private const val POLL_INTERVAL_MILLIS = 250L
    }
}
