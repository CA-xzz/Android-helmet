package com.example.helmet.service.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.helmet.data.local.RuntimeConfigStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceStatusWorkerInstrumentedTest {
    @Test
    fun outboxSurvivesRecreationAndQuarantinesIncompleteSnapshot() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "helmet_device_status_outbox_test_${UUID.randomUUID()}"
        val outbox = DeviceStatusOutbox(context, preferencesName)
        val messageId = "status-persistence-${UUID.randomUUID()}"
        val payload = testPayload(outbox, messageId)
        outbox.replace(payload)
        try {
            val recovered = DeviceStatusOutbox(context, preferencesName).current()
            assertEquals(messageId, recovered?.messageId)
            assertTrue(requireNotNull(recovered).generation > 0)

            val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            assertTrue(
                preferences.edit()
                    .putString(DeviceStatusOutbox.KEY_MESSAGE_ID, "incomplete")
                    .remove(DeviceStatusOutbox.KEY_JSON)
                    .putLong(DeviceStatusOutbox.KEY_SNAPSHOT_GENERATION, recovered.generation + 1)
                    .commit(),
            )
            assertNull(DeviceStatusOutbox(context, preferencesName).current())
            assertFalse(preferences.contains(DeviceStatusOutbox.KEY_MESSAGE_ID))
            assertFalse(preferences.contains(DeviceStatusOutbox.KEY_JSON))
            assertFalse(preferences.contains(DeviceStatusOutbox.KEY_SNAPSHOT_GENERATION))
        } finally {
            assertTrue(
                context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit(),
            )
        }
    }

    @Test
    fun workerUploadsLatestStatusAndClearsOutboxAfterMatchingAcknowledgement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val configSnapshot = capturePreferenceFilesForRecoveryTest(context, CONFIG_PREFERENCE_FILES)
        val deviceId = "status-test-device-${UUID.randomUUID()}"
        val messageId = "status-${UUID.randomUUID()}"
        val occurredAt = System.currentTimeMillis()
        val outboxPreferences = context.getSharedPreferences(DeviceStatusOutbox.PREFERENCES, Context.MODE_PRIVATE)
        val timePreferences = context.getSharedPreferences(DEVICE_TIME_PREFERENCES, Context.MODE_PRIVATE)
        val outboxSnapshot = outboxPreferences.snapshotForTest()
        val timeSnapshot = timePreferences.snapshotForTest()
        val outbox = DeviceStatusOutbox(context)
        try {
            val payload = DeviceStatusPayload(
                messageId = messageId,
                deviceId = deviceId,
                personId = "person-board-status",
                statusSequence = outbox.nextSequence(),
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
            outbox.replace(payload)
            configStore.saveForInstrumentationTest(
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
            assertEquals("person-board-status", stored.getString("personId"))
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
            withContext(NonCancellable) {
                try {
                    restorePreferenceFilesForRecoveryTest(context, configSnapshot)
                } finally {
                    try {
                        outboxPreferences.restoreForTest(outboxSnapshot)
                    } finally {
                        timePreferences.restoreForTest(timeSnapshot)
                    }
                }
            }
        }
    }

    private fun testPayload(outbox: DeviceStatusOutbox, messageId: String): DeviceStatusPayload = DeviceStatusPayload(
        messageId = messageId,
        deviceId = "status-test-device-${UUID.randomUUID()}",
        statusSequence = outbox.nextSequence(),
        occurredAtEpochMillis = System.currentTimeMillis(),
        operationalState = "IDLE",
        networkState = "UNKNOWN",
        hardwareMode = "UART",
        appVersion = "instrumented-test",
        cameraAvailable = false,
        simulated = false,
        activeCallId = null,
        battery = DeviceBatteryStatus(false, null, null),
        location = DeviceLocationStatus(null, null, null, "NO_FIX", null),
        time = DeviceTimeStatus(DeviceTimeSource.SYSTEM, false, null, null, null),
    )

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
        private const val DEVICE_TIME_PREFERENCES = "helmet_device_time"
        private const val TEST_ENDPOINT = "http://127.0.0.1:18082"
        private const val TEST_TOKEN = "device-status-board-token"
        private val CONFIG_PREFERENCE_FILES = listOf(
            "helmet_runtime_config",
            "helmet_backend_credentials",
            "helmet_rtk_credentials",
        )
    }
}

private fun android.content.SharedPreferences.snapshotForTest(): Map<String, Any> =
    all.mapValues { (_, value) ->
        when (value) {
            is String, is Int, is Long, is Float, is Boolean -> value
            is Set<*> -> value.map { item -> requireNotNull(item as? String) }.toSet()
            else -> error("unsupported test preference type: ${value?.javaClass?.name ?: "null"}")
        }
    }

private fun android.content.SharedPreferences.restoreForTest(snapshot: Map<String, Any>) {
    val editor = edit().clear()
    snapshot.forEach { (key, value) ->
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, (value as Set<String>).toSet())
            }
            else -> error("unsupported test preference type: ${value.javaClass.name}")
        }
    }
    check(editor.commit()) { "failed to restore test preference snapshot" }
}
