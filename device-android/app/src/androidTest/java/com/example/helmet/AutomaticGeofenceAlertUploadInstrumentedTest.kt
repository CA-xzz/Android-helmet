package com.example.helmet

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.core.model.CircleGeofence
import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.hardware.api.BoundHardwareGateway
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.RuntimeStatus
import java.io.Closeable
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AutomaticGeofenceAlertUploadInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun uartNmeaExitAndReturnArePersistedAndAutomaticallyUploaded() = runBlocking {
        val configStore = RuntimeConfigStore(context)
        val originalConfig = configStore.load()
        val deviceId = DeviceIdentityStore(context).getOrCreateDeviceId()
        val safetyStore = SafetyStore(HelmetDatabase.get(context))
        val geofenceId = "board-uart-fixture-${originalConfig.revision + 1}"
        val alertId = stableGeofenceAlertId(deviceId, geofenceId)
        val testConfig = originalConfig.copy(
            revision = originalConfig.revision + 1,
            simulatorEnabled = false,
            hardwareDevicePath = TEST_UART_PATH,
            hardwareBaudRate = 115_200,
            backendBaseUrl = TEST_ENDPOINT,
            backendBearerToken = TEST_TOKEN,
            mqttBrokerUri = "",
            mqttClientCertificateAlias = "",
            geofences = listOf(
                CircleGeofence(
                    geofenceId = geofenceId,
                    centerLatitude = CENTER_LATITUDE,
                    centerLongitude = CENTER_LONGITUDE,
                    radiusMeters = 100.0,
                    hysteresisMeters = 10.0,
                    confirmationSamples = 2,
                ),
            ),
        )
        var fixture: UartFixture? = null
        var heartbeatJob: Job? = null
        try {
            val activeFixture = withContext(Dispatchers.IO) {
                UartFixture(TEST_UART_BRIDGE_HOST, TEST_UART_BRIDGE_PORT)
            }
            fixture = activeFixture
            configStore.save(testConfig)
            context.stopService(HelmetService.startIntent(context))
            delay(SERVICE_RESTART_DELAY_MILLIS)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
            withTimeout(SERVICE_START_TIMEOUT_MILLIS) {
                RuntimeStatus.snapshot.first { snapshot ->
                    snapshot.configRevision == testConfig.revision &&
                        snapshot.hardwareMode == "UART" &&
                        snapshot.hardwareLinkState == "AWAITING_HEARTBEAT" &&
                        snapshot.activeGeofenceCount == 1
                }
            }

            heartbeatJob = launch(Dispatchers.IO) {
                var sequence = 1
                while (isActive) {
                    activeFixture.sendHeartbeat(sequence++)
                    delay(HEARTBEAT_INTERVAL_MILLIS)
                }
            }
            withTimeout(HARDWARE_CONNECT_TIMEOUT_MILLIS) {
                RuntimeStatus.snapshot.first { snapshot ->
                    snapshot.configRevision == testConfig.revision &&
                        snapshot.hardwareConnected &&
                        snapshot.hardwareLinkState == "CONNECTED"
                }
            }
            delay(SAFETY_CONFIG_SETTLE_MILLIS)
            activeFixture.sendAcknowledgement(testConfig.safetyThresholds.version, sequence = 100)

            sendConfirmedPosition(activeFixture, INSIDE_LATITUDE, sequence = 200)
            sendConfirmedPosition(activeFixture, OUTSIDE_LATITUDE, sequence = 210)

            val exitBackend = awaitBackendAlert(alertId, active = true)
            val exitLocal = awaitLocalAlert(safetyStore, alertId, active = true)
            assertEquals("GEOFENCE", exitBackend.getString("type"))
            assertEquals("HIGH", exitBackend.getString("severity"))
            assertFalse(exitBackend.getBoolean("simulated"))
            assertTrue(exitBackend.getBoolean("requiresAttention"))
            assertTrue(exitBackend.getJSONObject("presentation").getBoolean("sound"))
            assertTrue(exitBackend.getJSONObject("presentation").getBoolean("mapMarker"))
            assertEquals("RTK_FIXED", exitBackend.getJSONObject("location").getString("fixType"))
            assertEquals(OUTSIDE_LATITUDE, exitBackend.getJSONObject("location").getDouble("latitude"), 0.000_001)
            assertEquals(CENTER_LONGITUDE, exitBackend.getJSONObject("location").getDouble("longitude"), 0.000_001)
            assertEquals(DeliveryState.DELIVERED, exitLocal.deliveryState)
            assertEquals("RTK_FIXED", exitLocal.locationFixType)
            val exitSnapshot = exitBackend.getJSONObject("sensorSnapshot")
            assertEquals(geofenceId, exitSnapshot.getString("geofenceId"))
            assertEquals("EXIT", exitSnapshot.getString("transition"))
            assertTrue(exitSnapshot.getDouble("distanceMeters") > 110.0)

            sendConfirmedPosition(activeFixture, INSIDE_LATITUDE, sequence = 220)

            val returnBackend = awaitBackendAlert(alertId, active = false)
            val returnLocal = awaitLocalAlert(safetyStore, alertId, active = false)
            assertEquals("INFO", returnBackend.getString("severity"))
            assertEquals("OPEN", returnBackend.getString("workflowState"))
            assertFalse(returnBackend.getBoolean("requiresAttention"))
            assertEquals(DeliveryState.DELIVERED, returnLocal.deliveryState)
            assertFalse(returnLocal.active)
            val returnSnapshot = returnBackend.getJSONObject("sensorSnapshot")
            assertEquals(geofenceId, returnSnapshot.getString("geofenceId"))
            assertEquals("ENTER", returnSnapshot.getString("transition"))
            assertTrue(returnSnapshot.getDouble("distanceMeters") < 90.0)
            val kinds = returnBackend.getJSONArray("events")
                .let { events -> (0 until events.length()).map { events.getJSONObject(it).getString("kind") } }
            assertEquals(listOf("ACTIVATED", "CLEARED"), kinds)
        } finally {
            heartbeatJob?.cancelAndJoin()
            fixture?.close()
            context.stopService(HelmetService.startIntent(context))
            configStore.save(originalConfig)
            delay(SERVICE_RESTART_DELAY_MILLIS)
            ContextCompat.startForegroundService(context, HelmetService.startIntent(context))
        }
    }

    private suspend fun sendConfirmedPosition(fixture: UartFixture, latitude: Double, sequence: Int) {
        fixture.sendNmea(gga(latitude, seconds = sequence), sequence)
        delay(NMEA_SAMPLE_INTERVAL_MILLIS)
        fixture.sendNmea(gga(latitude, seconds = sequence + 1), sequence + 1)
        delay(NMEA_PROCESS_SETTLE_MILLIS)
    }

    private suspend fun awaitBackendAlert(alertId: String, active: Boolean): JSONObject {
        var result: JSONObject? = null
        withTimeout(AUTOMATIC_UPLOAD_TIMEOUT_MILLIS) {
            while (result == null) {
                val response = getAlert(alertId)
                if (response != null && response.getBoolean("active") == active) result = response
                if (result == null) delay(POLL_INTERVAL_MILLIS)
            }
        }
        return checkNotNull(result)
    }

    private suspend fun awaitLocalAlert(
        safetyStore: SafetyStore,
        alertId: String,
        active: Boolean,
    ): SafetyAlertRecord {
        var result: SafetyAlertRecord? = null
        withTimeout(AUTOMATIC_UPLOAD_TIMEOUT_MILLIS) {
            while (result == null) {
                val candidate = safetyStore.latestAlert(alertId)
                if (candidate?.active == active && candidate.deliveryState == DeliveryState.DELIVERED) {
                    result = candidate
                }
                if (result == null) delay(POLL_INTERVAL_MILLIS)
            }
        }
        return checkNotNull(result)
    }

    private suspend fun getAlert(alertId: String): JSONObject? = withContext(Dispatchers.IO) {
        val connection = (URL("$TEST_ENDPOINT/v1/alerts/$alertId").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5_000
            readTimeout = 10_000
            doInput = true
            setRequestProperty("Authorization", "Bearer $TEST_TOKEN")
            setRequestProperty("X-Actor-Id", "automatic-geofence-board")
            setRequestProperty("X-Actor-Role", "DISPATCHER")
        }
        try {
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                .bufferedReader().use { it.readText() }
            when (status) {
                404 -> null
                in 200..299 -> JSONObject(text)
                else -> error("backend HTTP $status: $text")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun gga(latitude: Double, seconds: Int): String {
        val latitudeMinutes = (latitude - 30.0) * 60.0
        val latitudeField = "30${String.format(Locale.US, "%07.4f", latitudeMinutes)}"
        val body = "GPGGA,1235${String.format(Locale.US, "%02d", seconds % 60)}," +
            "$latitudeField,N,11400.0000,E,4,12,0.7,35.0,M,0.0,M,0.8,0042"
        val checksum = body.toByteArray(StandardCharsets.US_ASCII)
            .fold(0) { current, byte -> current xor (byte.toInt() and 0xFF) }
        return "\$$body*${checksum.toString(16).uppercase().padStart(2, '0')}\r\n"
    }

    private fun stableGeofenceAlertId(deviceId: String, geofenceId: String): String =
        "geofence-${UUID.nameUUIDFromBytes("$deviceId\n$geofenceId".toByteArray(Charsets.UTF_8))}"

    private class UartFixture(host: String, port: Int) : Closeable {
        private val socket = Socket(host, port).apply { tcpNoDelay = true }
        private val output = socket.getOutputStream()

        fun sendHeartbeat(sequence: Int) {
            val payload = ByteArray(8).also { bytes ->
                bytes[4] = 0
                bytes[5] = 1
                bytes[6] = 0
                bytes[7] = 0
            }
            sendFrame(type = 0x01, flags = 0, sequence = sequence, payload = payload)
        }

        fun sendAcknowledgement(acknowledgedSequence: Int, sequence: Int) {
            val payload = byteArrayOf(
                (acknowledgedSequence and 0xFF).toByte(),
                ((acknowledgedSequence ushr 8) and 0xFF).toByte(),
                0,
            )
            sendFrame(type = 0x7F, flags = 0x02, sequence = sequence, payload = payload)
        }

        fun sendNmea(sentence: String, sequence: Int) {
            sendFrame(
                type = 0x31,
                flags = 0,
                sequence = sequence,
                payload = sentence.toByteArray(StandardCharsets.US_ASCII),
            )
        }

        private fun sendFrame(type: Int, flags: Int, sequence: Int, payload: ByteArray) {
            val bytes = ByteArray(11 + payload.size)
            bytes[0] = 0xA5.toByte()
            bytes[1] = 0x5A
            bytes[2] = 1
            bytes[3] = flags.toByte()
            bytes[4] = type.toByte()
            putU16Le(bytes, 5, sequence and 0xFFFF)
            putU16Le(bytes, 7, payload.size)
            payload.copyInto(bytes, destinationOffset = 9)
            putU16Le(bytes, bytes.size - 2, crc16Ccitt(bytes, offset = 2, length = 7 + payload.size))
            synchronized(output) {
                output.write(bytes)
                output.flush()
            }
        }

        override fun close() = socket.close()

        private fun putU16Le(bytes: ByteArray, offset: Int, value: Int) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        private fun crc16Ccitt(bytes: ByteArray, offset: Int, length: Int): Int {
            var crc = 0xFFFF
            for (index in offset until offset + length) {
                crc = crc xor ((bytes[index].toInt() and 0xFF) shl 8)
                repeat(8) {
                    crc = if (crc and 0x8000 != 0) {
                        ((crc shl 1) xor 0x1021) and 0xFFFF
                    } else {
                        (crc shl 1) and 0xFFFF
                    }
                }
            }
            return crc
        }
    }

    companion object {
        private val bootstrapContext: Context = InstrumentationRegistry.getInstrumentation().targetContext
        private lateinit var bootstrapConnection: ServiceConnection

        @JvmStatic
        @BeforeClass
        fun bindHardwareServiceProcess() {
            InstrumentationRegistry.getInstrumentation().startActivitySync(
                Intent().setComponent(ComponentName(PACKAGE_NAME, "$PACKAGE_NAME.MainActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            SystemClock.sleep(ACTIVITY_BOOTSTRAP_DELAY_MILLIS)
            bootstrapContext.stopService(HelmetService.startIntent(bootstrapContext))
            SystemClock.sleep(SERVICE_RESTART_DELAY_MILLIS)
            val connected = CountDownLatch(1)
            bootstrapConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
            val intent = Intent().addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setComponent(
                ComponentName(PACKAGE_NAME, BoundHardwareGateway.HARDWARE_SERVICE_CLASS),
            )
            check(bootstrapContext.bindService(intent, bootstrapConnection, Context.BIND_AUTO_CREATE)) {
                "hardware service bootstrap bind returned false"
            }
            check(connected.await(HARDWARE_CONNECT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "hardware service bootstrap bind timed out"
            }
        }

        @JvmStatic
        @AfterClass
        fun unbindHardwareServiceProcess() {
            runCatching { bootstrapContext.unbindService(bootstrapConnection) }
        }

        private const val TEST_ENDPOINT = "http://127.0.0.1:18083"
        private const val TEST_TOKEN = "automatic-geofence-board-token"
        private const val PACKAGE_NAME = "com.example.helmet"
        private const val TEST_UART_PATH = "/dev/ttyUSB99"
        private const val TEST_UART_BRIDGE_HOST = "127.0.0.1"
        private const val TEST_UART_BRIDGE_PORT = 18_084
        private const val CENTER_LATITUDE = 30.0
        private const val CENTER_LONGITUDE = 114.0
        private const val INSIDE_LATITUDE = 30.000_1
        private const val OUTSIDE_LATITUDE = 30.002
        private const val SERVICE_START_TIMEOUT_MILLIS = 30_000L
        private const val HARDWARE_CONNECT_TIMEOUT_MILLIS = 10_000L
        private const val AUTOMATIC_UPLOAD_TIMEOUT_MILLIS = 30_000L
        private const val ACTIVITY_BOOTSTRAP_DELAY_MILLIS = 500L
        private const val SERVICE_RESTART_DELAY_MILLIS = 500L
        private const val HEARTBEAT_INTERVAL_MILLIS = 1_000L
        private const val SAFETY_CONFIG_SETTLE_MILLIS = 100L
        private const val NMEA_SAMPLE_INTERVAL_MILLIS = 150L
        private const val NMEA_PROCESS_SETTLE_MILLIS = 500L
        private const val POLL_INTERVAL_MILLIS = 250L
    }
}
