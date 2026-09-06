package com.example.helmet.service.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalPromptPolicyTest {
    @Test
    fun startupPromptReportsBatteryOrExplicitAbsence() {
        assertEquals("设备开机，当前电量为百分之76", startupBatteryPrompt(true, 76))
        assertEquals("设备开机，当前电量未知", startupBatteryPrompt(true, null))
        assertEquals("设备开机，未检测到电池", startupBatteryPrompt(false, null))
    }

    @Test
    fun networkPromptOnlyReportsInitialOrValidatedBoundaryChanges() {
        assertEquals("组网失败", networkPrompt(null, false))
        assertNull(networkPrompt(false, false))
        assertEquals("组网成功", networkPrompt(false, true))
        assertNull(networkPrompt(true, true))
        assertEquals("组网失败", networkPrompt(true, false))
    }

    @Test
    fun batteryThresholdsPlayOnceAndResetAfterCharging() {
        val tracker = BatteryPromptTracker()

        assertNull(tracker.update(true, 21))
        assertEquals(20, tracker.update(true, 20))
        assertNull(tracker.update(true, 19))
        assertEquals(10, tracker.update(true, 10))
        assertEquals(5, tracker.update(true, 5))
        assertNull(tracker.update(true, 4))
        assertNull(tracker.update(false, null))
        assertNull(tracker.update(true, 80))
        assertEquals(20, tracker.update(true, 20))
    }

    @Test
    fun restoredBatteryThresholdDoesNotRepeatAfterProcessRestart() {
        val tracker = BatteryPromptTracker(initialPromptedThreshold = 10)

        assertNull(tracker.update(true, 8))
        assertEquals(5, tracker.update(true, 5))
    }

    @Test
    fun uploadReceiptPromptIsLimitedToActiveOneKeyAlarm() {
        assertEquals(true, shouldAnnounceSafetyAlertUploaded("SOS", active = true))
        assertEquals(false, shouldAnnounceSafetyAlertUploaded("SOS", active = false))
        assertEquals(false, shouldAnnounceSafetyAlertUploaded("FALL", active = true))
    }

    @Test
    fun motionAlarmPromptsIdentifyEachDetectedState() {
        assertEquals("检测到跌落报警", safetyAlarmPrompt("FALL", simulated = false))
        assertEquals("检测到撞击报警", safetyAlarmPrompt("IMPACT", simulated = false))
        assertEquals("检测到剧烈晃动报警", safetyAlarmPrompt("VIOLENT_SHAKE", simulated = false))
        assertEquals("检测到长时间静止报警", safetyAlarmPrompt("INACTIVITY", simulated = false))
        assertEquals("检测到模拟跌落报警", safetyAlarmPrompt("FALL", simulated = true))
    }

    @Test
    fun nearElectricAlarmUsesSpecificVoicePrompt() {
        assertEquals("检测到近电报警", safetyAlarmPrompt("NEAR_ELECTRIC", simulated = false))
        assertEquals("检测到模拟近电报警", safetyAlarmPrompt("NEAR_ELECTRIC", simulated = true))
    }

    @Test
    fun heightAlarmUsesSpecificVoicePrompt() {
        assertEquals("检测到高度报警", safetyAlarmPrompt("HEIGHT_LIMIT", simulated = false))
        assertEquals("检测到模拟高度报警", safetyAlarmPrompt("HEIGHT_LIMIT", simulated = true))
    }
}
