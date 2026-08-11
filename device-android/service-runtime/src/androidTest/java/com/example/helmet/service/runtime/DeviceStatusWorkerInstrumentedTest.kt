package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.RuntimeConfigStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceStatusWorkerInstrumentedTest {
    @Test
    fun workerUploadsLatestStatusAndClearsOutboxAfterMatchingAcknowledgement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val messageId = "status-${UUID.randomUUID()}"
        val occurredAt = System.currentTimeMillis()
        val payload = DeviceStatusPayload(
            messageId = messageId,
            deviceId = deviceId,
            statusSequence = outboxSequence(context),
            occurredAtEpochMillis = occurredAt,
            operationalState = "IDLE",
            networkState = "VALIDATED",
            hardwareMode = "SIMULATED",
            appVersion = "0.2.0-instrumented-test",
            cameraAvailable = false,
            simulated = true,
            activeCallId = null,
            battery = DeviceBatteryStatus(false, null, null),
            location = DeviceLocationStatus(null, null, null, "NO_FIX", null),
            time = DeviceTimeStatus(DeviceTimeSource.SYSTEM, false, null, null, null),
        )
        val outbox = DeviceStatusOutbox(context)
        outbox.replace(payload)
        try {
            configStore.save(
                originalConfig.copy(backendBaseUrl = TEST_ENDPOINT, backendBearerToken = TEST_TOKEN),
            )
            val result = TestListenableWorkerBuilder<DeviceStatusWorker>(context).build().doWork()
            assertEquals(ListenableWorker.Result.success().javaClass, result.javaClass)
            assertNull(outbox.current())

            val devices = getJson("/v1/devices/overview").getJSONArray("devices")
            val stored = (0 until devices.length())
                .map { devices.getJSONObject(it) }
                .single { it.getString("deviceId") == deviceId }
            assertEquals("IDLE", stored.getString("operationalState"))
            assertEquals("SIMULATED", stored.getString("hardwareMode"))
            assertTrue(stored.getBoolean("simulated"))
            assertTrue(stored.getJSONObject("battery").getBoolean("reported"))
            assertEquals("NO_FIX", stored.getJSONObject("location").getString("fixType"))
            val clock = stored.getJSONObject("clock")
            assertTrue(clock.getBoolean("reported"))
            assertTrue(clock.getBoolean("includesTransportDelay"))
            assertEquals(
                stored.getLong("lastContactAtEpochMillis"),
                clock.getLong("serverReceivedAtEpochMillis"),
            )
            assertEquals(
                occurredAt - clock.getLong("serverReceivedAtEpochMillis"),
                clock.getLong("observedOffsetMillis"),
            )
            val calibrated = DeviceTimeAuthorityProvider.get(context).read()
            assertEquals(DeviceTimeSource.SERVER, calibrated.source)
            assertTrue(calibrated.synchronized)
        } finally {
            outbox.clearIf(messageId)
            configStore.save(originalConfig)
        }
    }

    private fun outboxSequence(context: Context): Long = DeviceStatusOutbox(context).nextSequence()

    private suspend fun getJson(path: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(TEST_ENDPOINT + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
            setRequestProperty("X-Actor-Id", "dispatcher-status-test")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
        }
        try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            check(code in 200..299) { "backend HTTP $code: $text" }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18082"
        private const val TEST_TOKEN = "device-status-board-token"
    }
}
