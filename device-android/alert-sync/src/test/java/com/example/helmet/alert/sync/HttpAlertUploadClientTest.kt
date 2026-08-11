package com.example.helmet.alert.sync

import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class HttpAlertUploadClientTest {
    @Test
    fun endpointRequiresHttpsExceptForLoopbackTests() {
        assertEquals(
            "https://alerts.example.test",
            HttpAlertUploadClient.validateAndNormalizeBaseUrl("https://alerts.example.test/"),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            HttpAlertUploadClient.validateAndNormalizeBaseUrl("http://127.0.0.1:18080"),
        )
        assertThrows(IllegalArgumentException::class.java) {
            HttpAlertUploadClient.validateAndNormalizeBaseUrl("http://alerts.example.test")
        }
    }

    @Test
    fun requestContainsSnapshotLocationAndEvidence() {
        val json = with(HttpAlertUploadClient) { alert().toJson() }

        assertEquals("message-1", json.getString("messageId"))
        assertEquals(920, json.getJSONObject("sensorSnapshot").getInt("electricFieldMilliVolts"))
        assertEquals(31.2, json.getJSONObject("location").getDouble("latitude"), 0.0)
        assertEquals("photo-1", json.getJSONObject("evidence").getString("mediaAssetId"))
    }

    private fun alert() = SafetyAlertRecord(
        messageId = "message-1",
        alertId = "alert-1",
        deviceId = "device-1",
        alarmType = "NEAR_ELECTRIC",
        severity = EventSeverity.CRITICAL,
        active = true,
        configVersion = 2,
        sampleReference = 7,
        monotonicMillis = 900,
        occurredAtEpochMillis = 1_000,
        localActions = 7,
        sensorFaults = 0,
        simulated = false,
        sensorSnapshotJson = "{\"electricFieldMilliVolts\":920}",
        latitude = 31.2,
        longitude = 121.4,
        horizontalAccuracyMeters = 2f,
        locationFixType = "STANDARD",
        evidenceAssetId = "photo-1",
        deliveryState = DeliveryState.PENDING,
        attemptCount = 0,
    )
}
