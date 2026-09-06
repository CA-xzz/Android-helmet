package com.example.helmet.core.model

import org.junit.Assert.assertThrows
import org.junit.Test

class SafetyModelsTest {
    @Test
    fun alertRejectsInvalidOrFabricatedLocationEvidence() {
        assertThrows(IllegalArgumentException::class.java) {
            alert().copy(latitude = 91.0, longitude = 114.0, locationFixType = "STANDARD")
        }
        assertThrows(IllegalArgumentException::class.java) {
            alert().copy(
                latitude = 30.0,
                longitude = 114.0,
                horizontalAccuracyMeters = Float.NaN,
                locationFixType = "STANDARD",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            alert().copy(locationFixType = "STANDARD")
        }
    }

    private fun alert() = SafetyAlertRecord(
        messageId = "message-1",
        alertId = "alert-1",
        deviceId = "device-1",
        alarmType = "FALL",
        severity = EventSeverity.CRITICAL,
        active = true,
        configVersion = 1,
        sampleReference = 1,
        monotonicMillis = 100,
        occurredAtEpochMillis = 1_000,
        localActions = 7,
        sensorFaults = 0,
        simulated = false,
        sensorSnapshotJson = "{}",
        latitude = null,
        longitude = null,
        horizontalAccuracyMeters = null,
        locationFixType = "NO_FIX",
        evidenceAssetId = null,
        deliveryState = DeliveryState.PENDING,
        attemptCount = 0,
    )
}
