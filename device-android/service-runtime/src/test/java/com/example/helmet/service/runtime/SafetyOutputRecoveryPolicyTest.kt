package com.example.helmet.service.runtime

import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyOutputRecoveryPolicyTest {
    @Test
    fun desiredStateRoundTripsWithStableAlarmAndOutputIdentity() {
        val original = state(active = true)

        val decoded = SafetyOutputDesiredState.decode(original.encode())

        assertEquals(original, decoded)
        assertEquals(original.alarmId, decoded?.toHardwareEvent()?.alarmId)
        assertEquals(original.localActions, decoded?.toHardwareEvent()?.localActions)
    }

    @Test
    fun corruptOrUnsupportedRecoveryStateIsRejected() {
        assertNull(SafetyOutputDesiredState.decode("1|bad"))
        assertNull(SafetyOutputDesiredState.decode(state().encode().replace("NEAR_ELECTRIC", "FALL")))
    }

    @Test
    fun onlyRealAndroidLatchedAlarmsWithActionsAreRecoverable() {
        val alarm = state().toHardwareEvent()

        assertEquals(state(), alarm.toRecoverableSafetyOutputState())
        assertNull(alarm.copy(origin = HardwareAlarmOrigin.EXTERNAL_MODULE).toRecoverableSafetyOutputState())
        assertNull(alarm.copy(simulated = true).toRecoverableSafetyOutputState())
        assertNull(alarm.copy(localActions = 0).toRecoverableSafetyOutputState())
        assertNull(alarm.copy(alarmType = "IMPACT").toRecoverableSafetyOutputState())
    }

    @Test
    fun latestRoomAlertRequiresExplicitAndroidDetectionOrigin() {
        val alert = alert(active = true)

        assertEquals(state(), alert.toRecoverableSafetyOutputState("ANDROID_DETECTION"))
        assertNull(alert.toRecoverableSafetyOutputState("EXTERNAL_MODULE"))
        assertNull(alert.copy(active = false, alarmType = "FALL").toRecoverableSafetyOutputState("ANDROID_DETECTION"))
    }

    @Test
    fun replayGateRetriesFailuresButSuppressesConfirmedStateUntilNextModuleSession() {
        val gate = SafetyOutputReplayGate()
        val active = state(active = true).toHardwareEvent()
        val clear = state(active = false).toHardwareEvent()

        assertTrue(gate.pending(false, false, 1, listOf(active)).isEmpty())
        assertTrue(gate.pending(true, true, 1, listOf(active)).isEmpty())
        assertEquals(listOf(active), gate.pending(true, false, 1, listOf(active)))

        // A failed send is not recorded, so the same state remains eligible in this session.
        assertEquals(listOf(active), gate.pending(true, false, 1, listOf(active)))
        assertTrue(gate.recordConfirmed(1, active))
        assertTrue(gate.pending(true, false, 1, listOf(active)).isEmpty())

        // Clear is a distinct desired state for the same alarm and must still be sent.
        assertEquals(listOf(clear), gate.pending(true, false, 1, listOf(clear)))
        assertEquals(listOf(active), gate.pending(true, false, 2, listOf(active)))
    }

    @Test
    fun replayRetryDelayUsesBoundedExponentialBackoff() {
        assertEquals(1_000, safetyOutputReplayRetryDelayMillis(0))
        assertEquals(2_000, safetyOutputReplayRetryDelayMillis(1))
        assertEquals(30_000, safetyOutputReplayRetryDelayMillis(10))
    }

    @Test
    fun persistedClearWinsCrashBeforeRoomAndRoomClearRepairsAnOlderPersistedActive() {
        val active = state(active = true)
        val clear = state(active = false).copy(sampleReference = 10, monotonicMillis = 11)

        assertEquals(clear, reconcileSafetyOutputDesiredState(clear, active))
        assertEquals(clear, reconcileSafetyOutputDesiredState(active, clear))
        assertEquals(active, reconcileSafetyOutputDesiredState(active, active))
    }

    @Test
    fun unusableCheckpointCanTurnOrphanedPersistedOutputIntoFailSafeClear() {
        val active = state(active = true)

        val clear = active.toFailSafeClear(replacementConfigVersion = 4)

        assertFalse(clear.active)
        assertEquals("INFO", clear.severity)
        assertEquals(4, clear.configVersion)
        assertEquals(active.alarmId, clear.alarmId)
        assertEquals(active.sampleReference, clear.sampleReference)
        assertEquals(active.monotonicMillis, clear.monotonicMillis)
        assertEquals(active.localActions, clear.localActions)
    }

    @Test
    fun configurationChangeTerminatesRealOrSimulatedAndroidEpisodeWithStableIdentity() {
        val active = alert(active = true)

        val clear = active.toDetectionEpisodeTermination(
            detectionOrigin = "ANDROID_DETECTION",
            replacementConfigVersion = 4,
            replacementSampleReference = 10,
            replacementMonotonicMillis = 11,
        )
        val simulatedClear = active.copy(simulated = true)
            .toDetectionEpisodeTermination("ANDROID_DETECTION", replacementConfigVersion = 4)

        assertEquals(77L, clear?.alarmId)
        assertFalse(requireNotNull(clear).active)
        assertEquals(4, clear.configVersion)
        assertEquals(10L, clear.sampleReference)
        assertEquals(11L, clear.monotonicMillis)
        assertTrue(requireNotNull(simulatedClear).simulated)
        assertNull(active.toDetectionEpisodeTermination("EXTERNAL_MODULE", 4))
        assertNull(active.copy(active = false).toDetectionEpisodeTermination("ANDROID_DETECTION", 4))
        assertTrue(safetyEpisodeTerminationReferenceAdvances(9, 10))
        assertFalse(safetyEpisodeTerminationReferenceAdvances(9, 9))
        assertFalse(safetyEpisodeTerminationReferenceAdvances(9, 8))
    }

    private fun state(active: Boolean = true) = SafetyOutputDesiredState(
        alarmId = 77,
        alarmType = "NEAR_ELECTRIC",
        severity = "HIGH",
        active = active,
        configVersion = 3,
        sampleReference = 9,
        monotonicMillis = 10,
        localActions = 7,
        sensorFaults = 0,
    )

    private fun alert(active: Boolean) = SafetyAlertRecord(
        messageId = "device:77:${if (active) "ACTIVE" else "CLEARED"}:9",
        alertId = "device:77",
        deviceId = "device",
        alarmType = "NEAR_ELECTRIC",
        severity = EventSeverity.HIGH,
        active = active,
        configVersion = 3,
        sampleReference = 9,
        monotonicMillis = 10,
        occurredAtEpochMillis = 100,
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
