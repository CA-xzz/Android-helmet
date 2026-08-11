package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyAlertWorkerInstrumentedTest {
    @Test
    fun workerUploadsRichAlertAndBackendRetainsPresentationAndWorkflow() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val store = SafetyStore(HelmetDatabase.get(context))
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val nonce = UUID.randomUUID().toString()
        val alert = SafetyAlertRecord(
            messageId = "message-$nonce",
            alertId = "alert-$nonce",
            deviceId = deviceId,
            alarmType = "NEAR_ELECTRIC",
            severity = EventSeverity.CRITICAL,
            active = true,
            configVersion = 3,
            sampleReference = 77,
            monotonicMillis = 12_345,
            occurredAtEpochMillis = System.currentTimeMillis(),
            localActions = 7,
            sensorFaults = 0,
            simulated = true,
            sensorSnapshotJson = JSONObject(
                mapOf(
                    "electricFieldMilliVolts" to 920,
                    "electricFieldBaselineMilliVolts" to 100,
                    "electricFieldExposureMilliVoltMillis" to 82_000,
                ),
            ).toString(),
            latitude = 31.2304,
            longitude = 121.4737,
            horizontalAccuracyMeters = 2f,
            locationFixType = "STANDARD",
            evidenceAssetId = null,
            deliveryState = DeliveryState.PENDING,
            attemptCount = 0,
        )
        assertTrue(store.recordAlert(alert))
        try {
            configStore.save(
                originalConfig.copy(
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                ),
            )
            val result = TestListenableWorkerBuilder<SafetyAlertWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertEquals(DeliveryState.DELIVERED, store.findAlert(alert.messageId)?.deliveryState)

            val stored = getJson("/v1/alerts/${alert.alertId}")
            assertEquals(alert.alertId, stored.getString("alertId"))
            assertEquals("OPEN", stored.getString("workflowState"))
            assertTrue(stored.getBoolean("requiresAttention"))
            assertTrue(stored.getJSONObject("presentation").getBoolean("sound"))
            assertTrue(stored.getJSONObject("presentation").getBoolean("mapMarker"))
            assertTrue(stored.getBoolean("simulated"))
            assertFalse(stored.has("finalHardwareEvidence"))
            assertEquals(alert.messageId, stored.getJSONObject("evidence").getString("relatedEventId"))
        } finally {
            configStore.save(originalConfig)
        }
    }

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(TEST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
            setRequestProperty("X-Actor-Id", "dispatcher-stage6")
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

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18080"
        private const val TEST_TOKEN = "stage3-board-integration-token"
    }
}
