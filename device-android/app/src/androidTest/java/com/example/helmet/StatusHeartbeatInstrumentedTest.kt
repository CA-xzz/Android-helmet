package com.example.helmet

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.testfixture.PersistentPreferencesTestGuard
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private val backendBearerToken = requireNonBlankBoardTestArgument(
        InstrumentationRegistry.getArguments().getString(BACKEND_BEARER_TOKEN_ARGUMENT),
        BACKEND_BEARER_TOKEN_ARGUMENT,
    )

    @Test
    fun serviceRepublishesServerTimeAndMaintainsPeriodicContact() = runBlocking {
        PersistentPreferencesTestGuard.restoreStale(context, RECOVERY_EVIDENCE_PREFERENCES)
        val configStore = RuntimeConfigStore(context)
        val stateGuard = PersistentPreferencesTestGuard.capture(
            context,
            RECOVERY_EVIDENCE_PREFERENCES,
            STATE_PREFERENCE_FILES,
        )
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val timePreferences = context.getSharedPreferences(DEVICE_TIME_PREFERENCES, Context.MODE_PRIVATE)
        try {
            configStore.update { current ->
                current.copy(
                    simulatorEnabled = true,
                    personId = TEST_PERSON_ID,
                    backendBaseUrl = TEST_ENDPOINT,
                    backendBearerToken = backendBearerToken,
                )
            }
            check(
                timePreferences.edit().clear().commit(),
            )
            context.stopService(HelmetService.startIntent(context))
            delay(500)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))

            val synchronized = awaitDevice(deviceId, IMMEDIATE_CALIBRATION_TIMEOUT_MILLIS) { device ->
                val clock = device.getJSONObject("clock")
                device.optString("personId") == TEST_PERSON_ID &&
                    clock.optString("source") == "SERVER" && clock.optBoolean("synchronized")
            }
            val firstContact = synchronized.getLong("lastContactAtEpochMillis")
            val firstSequence = synchronized.getLong("statusSequence")
            assertEquals("SERVER", synchronized.getJSONObject("clock").getString("source"))
            assertEquals(TEST_PERSON_ID, synchronized.getString("personId"))
            assertFirmwareCurrent(synchronized)

            val heartbeat = awaitDevice(deviceId, HEARTBEAT_TIMEOUT_MILLIS) { device ->
                device.optLong("lastContactAtEpochMillis") > firstContact &&
                    device.optLong("statusSequence") > firstSequence
            }
            assertTrue(heartbeat.getLong("lastContactAtEpochMillis") > firstContact)
            assertTrue(heartbeat.getLong("statusSequence") > firstSequence)
            assertEquals("SERVER", heartbeat.getJSONObject("clock").getString("source"))
            assertEquals(TEST_PERSON_ID, heartbeat.getString("personId"))
            assertFirmwareCurrent(heartbeat)
        } finally {
            withContext(NonCancellable) {
                context.stopService(HelmetService.startIntent(context))
                delay(500)
                try {
                    stateGuard.restore()
                } finally {
                    ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
                }
            }
        }
    }

    private fun assertFirmwareCurrent(device: JSONObject) {
        val expected = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        val firmware = device.getJSONObject("firmware")
        assertEquals(expected, firmware.getString("reportedVersion"))
        assertEquals(expected, firmware.getString("requiredVersion"))
        assertEquals(false, firmware.getBoolean("updateRequired"))
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
            setRequestProperty("Authorization", "Bearer $backendBearerToken")
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
        private const val BACKEND_BEARER_TOKEN_ARGUMENT = "backendBearerToken"
        private const val TEST_PERSON_ID = "person-board-heartbeat"
        private const val DEVICE_TIME_PREFERENCES = "helmet_device_time"
        private const val DEVICE_STATUS_PREFERENCES = "helmet_device_status_outbox"
        private const val RECOVERY_EVIDENCE_PREFERENCES = "status_heartbeat_test_recovery"
        private const val IMMEDIATE_CALIBRATION_TIMEOUT_MILLIS = 20_000L
        private const val HEARTBEAT_TIMEOUT_MILLIS = 80_000L
        private const val POLL_INTERVAL_MILLIS = 500L
        private val STATE_PREFERENCE_FILES = listOf(
            "helmet_runtime_config",
            "helmet_backend_credentials",
            "helmet_rtk_credentials",
            DEVICE_TIME_PREFERENCES,
            DEVICE_STATUS_PREFERENCES,
        )
    }
}
