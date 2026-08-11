package com.example.helmet.service.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStatusPayloadTest {
    @Test
    fun payloadContainsTrustedLocationBatteryAndNullableCall() {
        val payload = DeviceStatusPayload(
            messageId = "status-1",
            deviceId = "device-1",
            statusSequence = 7,
            occurredAtEpochMillis = 2_000,
            operationalState = "IDLE",
            networkState = "VALIDATED",
            hardwareMode = "UART",
            appVersion = "0.2.0 (2)",
            cameraAvailable = true,
            simulated = false,
            activeCallId = null,
            battery = DeviceBatteryStatus(true, 68, 3_920),
            location = DeviceLocationStatus(31.2304, 121.4737, 2.5f, "STANDARD", 1_900),
            rtk = DeviceRtkStatus("STREAMING", 12, 4_096, "RTK_FIXED", null),
            localIntercom = DeviceLocalIntercomStatus(
                "READY", 3, "VENDOR_NARROWBAND", -98, 15, 145, 0, null,
            ),
            time = DeviceTimeStatus(DeviceTimeSource.SERVER, true, 25, 500, 2),
        ).toJson()

        assertEquals(1, payload.getInt("schemaVersion"))
        assertTrue(payload.isNull("personId"))
        assertTrue(payload.isNull("activeCallId"))
        assertEquals(7L, payload.getLong("statusSequence"))
        assertEquals(68, payload.getJSONObject("battery").getInt("percent"))
        assertEquals(3_920, payload.getJSONObject("battery").getInt("voltageMillivolts"))
        assertFalse(payload.getJSONObject("location").getBoolean("isMock"))
        assertEquals(12L, payload.getJSONObject("rtk").getLong("correctionFrames"))
        assertEquals(3, payload.getJSONObject("localIntercom").getInt("peerCount"))
        assertEquals("SERVER", payload.getJSONObject("time").getString("source"))
        assertTrue(payload.getJSONObject("time").getBoolean("synchronized"))
    }

    @Test
    fun absentBatteryCannotCarryMeasurements() {
        assertThrows(IllegalArgumentException::class.java) { DeviceBatteryStatus(false, 50, null) }
        val battery = DeviceBatteryStatus(false, null, null)
        assertNull(battery.percent)
    }

    @Test
    fun backendUrlRequiresHttpsExceptLoopback() {
        assertEquals(
            "https://helmet.example.test",
            HttpDeviceStatusClient.validateAndNormalizeBaseUrl("https://helmet.example.test/"),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            HttpDeviceStatusClient.validateAndNormalizeBaseUrl("http://127.0.0.1:18080"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HttpDeviceStatusClient.validateAndNormalizeBaseUrl("http://helmet.example.test")
        }
    }

    @Test
    fun acknowledgementRequiresMatchingMessageAndServerTime() {
        val receipt = HttpDeviceStatusClient.parseReceipt(
            """{"messageId":"status-1","deduplicated":false,"serverReceivedAtEpochMillis":2000}""",
            "status-1",
        )
        assertEquals("status-1", receipt.messageId)
        assertFalse(receipt.deduplicated)
        assertEquals(2_000L, receipt.serverReceivedAtEpochMillis)

        assertThrows(DeviceStatusUploadException::class.java) {
            HttpDeviceStatusClient.parseReceipt(
                """{"messageId":"other","deduplicated":false,"serverReceivedAtEpochMillis":2000}""",
                "status-1",
            )
        }
        assertThrows(DeviceStatusUploadException::class.java) {
            HttpDeviceStatusClient.parseReceipt(
                """{"messageId":"status-1","deduplicated":false}""",
                "status-1",
            )
        }
    }
}
