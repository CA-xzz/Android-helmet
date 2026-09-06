package com.example.helmet.service.runtime

import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SafetyAlarmEventPayloadTest {
    @Test
    fun retryPayloadDependsOnlyOnStableSourceFrameNotCurrentLocation() {
        val event = HardwareEvent.Alarm(
            monotonicMillis = 10,
            alarmType = "NEAR_ELECTRIC",
            severity = "HIGH",
            simulated = false,
            active = true,
            alarmId = 77,
            configVersion = 2,
            sampleReference = 9,
            localActions = 7,
            sensorFaults = 0,
            origin = HardwareAlarmOrigin.EXTERNAL_MODULE,
        )

        val firstWithoutFix = safetyAlarmEventPayloadFields(event, "device:77", "device:77:ACTIVE:9")
        val retryAfterFix = safetyAlarmEventPayloadFields(event, "device:77", "device:77:ACTIVE:9")

        assertEquals(firstWithoutFix, retryAfterFix)
        assertFalse(firstWithoutFix.containsKey("locationFixType"))
        assertFalse(firstWithoutFix.containsKey("latitude"))
        assertFalse(firstWithoutFix.containsKey("longitude"))
    }
}
