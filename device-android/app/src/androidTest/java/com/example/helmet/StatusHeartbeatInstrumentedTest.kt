package com.example.helmet

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.service.runtime.HelmetService
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusHeartbeatInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun serviceRepublishesServerTimeAndMaintainsPeriodicContact() = runBlocking {
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        context.stopService(HelmetService.startIntent(context))
        delay(500)
        try {
            configStore.save(
                originalConfig.copy(
                    revision = originalConfig.revision + 1,
                    simulatorEnabled = true,
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = TEST_TOKEN,
                ),
            )
            check(
                context.getSharedPreferences("helmet_device_time", Context.MODE_PRIVATE)
                    .edit()
                    .clear()
                    .commit(),
            )
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))

            val synchronized = awaitDevice(deviceId, IMMEDIATE_CALIBRATION_TIMEOUT_MILLIS) { device ->
                val clock = device.getJSONObject("clock")
                clock.optString("source") == "SERVER" && clock.optBoolean("synchronized")
            }
            val firstContact = synchronized.getLong("lastContactAtEpochMillis")
            val firstSequence = synchronized.getLong("statusSequence")
            assertEquals("SERVER", synchronized.getJSONObject("clock").getString("source"))

            val heartbeat = awaitDevice(deviceId, HEARTBEAT_TIMEOUT_MILLIS) { device ->
                device.optLong("lastContactAtEpochMillis") > firstContact &&
                    device.optLong("statusSequence") > firstSequence
            }
            assertTrue(heartbeat.getLong("lastContactAtEpochMillis") > firstContact)
            assertTrue(heartbeat.getLong("statusSequence") > firstSequence)
            assertEquals("SERVER", heartbeat.getJSONObject("clock").getString("source"))
        } finally {
            context.stopService(HelmetService.startIntent(context))
            delay(500)
            configStore.save(originalConfig)
        }
    }

    private suspend fun awaitDevice(
        deviceId: String,
        timeoutMillis: Long,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var latest: JSONObject? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = runCatching { getDevice(deviceId) }.getOrNull() ?: latest
            if (latest != null && predicate(latest)) return latest
            delay(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("device status condition timed out; latest=${latest ?: "none"}")
    }

    private suspend fun getDevice(deviceId: String): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL("$TEST_ENDPOINT/v1/devices/overview").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
            setRequestProperty("X-Actor-Id", "heartbeat-board-test")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
        }
        try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader()
                .use { it.readText() }
            check(code in 200..299) { "backend HTTP $code: $text" }
            val devices = JSONObject(text).getJSONArray("devices")
            (0 until devices.length())
                .map { devices.getJSONObject(it) }
                .single { it.getString("deviceId") == deviceId }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val TEST_ENDPOINT = "http://127.0.0.1:18082"
        private const val TEST_TOKEN = "device-status-board-token"
        private const val IMMEDIATE_CALIBRATION_TIMEOUT_MILLIS = 20_000L
        private const val HEARTBEAT_TIMEOUT_MILLIS = 80_000L
        private const val POLL_INTERVAL_MILLIS = 500L
    }
}
