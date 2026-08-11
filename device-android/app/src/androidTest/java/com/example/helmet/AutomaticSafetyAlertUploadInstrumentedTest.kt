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
    fun foregroundServicePersistsAndAutomaticallyUploadsSimulatedFall() = runBlocking {
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
            val baselineAlertIds = getAlerts(deviceId).ids()

            Log.i(TEST_LOG_TAG, "sending simulated fall to foreground service")
            sendSimulatedInput(SimulatedInput.FALL)
            var uploadedResult: JSONObject? = null
            withTimeout(AUTOMATIC_UPLOAD_TIMEOUT_MILLIS) {
                while (uploadedResult == null) {
                    uploadedResult = getAlerts(deviceId).values()
                        .firstOrNull { alert ->
                            alert.getString("type") == "FALL" &&
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
            val local = checkNotNull(deliveredResult)

            assertEquals("HIGH", uploaded.getString("severity"))
            assertEquals("OPEN", uploaded.getString("workflowState"))
            assertTrue(uploaded.getBoolean("active"))
            assertTrue(uploaded.getBoolean("simulated"))
            assertTrue(uploaded.getBoolean("requiresAttention"))
            assertTrue(uploaded.getJSONObject("presentation").getBoolean("sound"))
            assertFalse(uploaded.getJSONObject("presentation").getBoolean("mapMarker"))
            assertEquals("NO_FIX", uploaded.getJSONObject("location").getString("fixType"))
            assertEquals(DeliveryState.DELIVERED, local.deliveryState)
            assertTrue(requireNotNull(local.sampleReference) > 0)
        } finally {
            configStore.save(originalConfig)
            context.stopService(HelmetService.startIntent(context))
            delay(SERVICE_RESTART_DELAY_MILLIS)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
        }
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
