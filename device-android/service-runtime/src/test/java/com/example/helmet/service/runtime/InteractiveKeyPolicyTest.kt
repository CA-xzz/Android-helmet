package com.example.helmet.service.runtime

import com.example.helmet.core.model.HelmetOperationalState
import com.example.helmet.hardware.api.HardwareOperationalState
import com.example.helmet.hardware.api.SimulatedInput
import org.junit.Assert.assertEquals
import org.junit.Test

class InteractiveKeyPolicyTest {
    @Test
    fun callAndRecordingCannotStartAcrossBusyModes() {
        assertEquals(
            InteractiveKeyAction.REJECT,
            decideInteractiveKeyAction(
                SimulatedInput.RECORD_LONG,
                HelmetOperationalState.IN_CALL,
                videoRecording = false,
                voiceRecording = false,
                hasActiveInternetCall = true,
                useLocalIntercom = false,
            ).action,
        )
        assertEquals(
            InteractiveKeyAction.REJECT,
            decideInteractiveKeyAction(
                SimulatedInput.CALL,
                HelmetOperationalState.RECORDING,
                videoRecording = true,
                voiceRecording = false,
                hasActiveInternetCall = false,
                useLocalIntercom = false,
            ).action,
        )
        assertEquals(
            InteractiveKeyAction.REJECT,
            decideInteractiveKeyAction(
                SimulatedInput.RECORD_LONG,
                HelmetOperationalState.SOS,
                videoRecording = false,
                voiceRecording = false,
                hasActiveInternetCall = false,
                useLocalIntercom = false,
            ).action,
        )
    }

    @Test
    fun callAndLegacyContextKeyEndAnActiveInternetCall() {
        listOf(SimulatedInput.CALL, SimulatedInput.PHOTO_SHORT).forEach { input ->
            assertEquals(
                InteractiveKeyAction.CALL_END,
                decideInteractiveKeyAction(
                    input,
                    HelmetOperationalState.IN_CALL,
                    videoRecording = false,
                    voiceRecording = false,
                    hasActiveInternetCall = true,
                    useLocalIntercom = false,
                ).action,
            )
        }
    }

    @Test
    fun sosIsAlwaysRoutedToThePersistentEmergencyPath() {
        HelmetOperationalState.entries.forEach { state ->
            assertEquals(
                InteractiveKeyAction.SOS,
                decideInteractiveKeyAction(
                    SimulatedInput.SOS,
                    state,
                    videoRecording = state == HelmetOperationalState.RECORDING,
                    voiceRecording = false,
                    hasActiveInternetCall = state == HelmetOperationalState.IN_CALL,
                    useLocalIntercom = false,
                ).action,
            )
        }
    }

    @Test
    fun allRuntimeStatesHaveStableHeartbeatMappings() {
        assertEquals(HardwareOperationalState.INITIALIZING, HelmetOperationalState.BOOTING.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.INITIALIZING, HelmetOperationalState.SELF_TEST.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.OFFLINE_READY, HelmetOperationalState.OFFLINE_READY.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.IDLE, HelmetOperationalState.IDLE.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.CALLING, HelmetOperationalState.CALLING.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.IN_CALL, HelmetOperationalState.IN_CALL.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.RECORDING, HelmetOperationalState.RECORDING.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.SOS, HelmetOperationalState.SOS.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.FAULT, HelmetOperationalState.FAULT.toHardwareOperationalState())
        assertEquals(HardwareOperationalState.SHUTTING_DOWN, HelmetOperationalState.SHUTTING_DOWN.toHardwareOperationalState())
    }
}
