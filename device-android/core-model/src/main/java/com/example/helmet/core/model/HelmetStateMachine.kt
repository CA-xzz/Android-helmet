package com.example.helmet.core.model

class HelmetStateMachine(
    initialState: HelmetOperationalState = HelmetOperationalState.BOOTING,
) {
    private var currentState = initialState

    @Synchronized
    fun current(): HelmetOperationalState = currentState

    @Synchronized
    fun transitionTo(target: HelmetOperationalState): Boolean {
        if (target == currentState) return true
        if (target !in allowedTargets.getValue(currentState)) return false
        currentState = target
        return true
    }

    companion object {
        private val activeStates = setOf(
            HelmetOperationalState.IDLE,
            HelmetOperationalState.CALLING,
            HelmetOperationalState.IN_CALL,
            HelmetOperationalState.RECORDING,
            HelmetOperationalState.SOS,
        )

        private val allowedTargets = mapOf(
            HelmetOperationalState.BOOTING to setOf(
                HelmetOperationalState.SELF_TEST,
                HelmetOperationalState.FAULT,
            ),
            HelmetOperationalState.SELF_TEST to setOf(
                HelmetOperationalState.OFFLINE_READY,
                HelmetOperationalState.IDLE,
                HelmetOperationalState.FAULT,
            ),
            HelmetOperationalState.OFFLINE_READY to activeStates + setOf(
                HelmetOperationalState.FAULT,
                HelmetOperationalState.SHUTTING_DOWN,
            ),
            HelmetOperationalState.IDLE to activeStates + setOf(
                HelmetOperationalState.OFFLINE_READY,
                HelmetOperationalState.FAULT,
                HelmetOperationalState.SHUTTING_DOWN,
            ),
            HelmetOperationalState.CALLING to setOf(
                HelmetOperationalState.IN_CALL,
                HelmetOperationalState.IDLE,
                HelmetOperationalState.OFFLINE_READY,
                HelmetOperationalState.SOS,
                HelmetOperationalState.FAULT,
            ),
            HelmetOperationalState.IN_CALL to setOf(
                HelmetOperationalState.IDLE,
                HelmetOperationalState.OFFLINE_READY,
                HelmetOperationalState.SOS,
                HelmetOperationalState.FAULT,
            ),
            HelmetOperationalState.RECORDING to setOf(
                HelmetOperationalState.IDLE,
                HelmetOperationalState.OFFLINE_READY,
                HelmetOperationalState.SOS,
                HelmetOperationalState.FAULT,
            ),
            HelmetOperationalState.SOS to setOf(
                HelmetOperationalState.IDLE,
                HelmetOperationalState.OFFLINE_READY,
                HelmetOperationalState.IN_CALL,
                HelmetOperationalState.FAULT,
            ),
            HelmetOperationalState.FAULT to setOf(
                HelmetOperationalState.SELF_TEST,
                HelmetOperationalState.OFFLINE_READY,
                HelmetOperationalState.SHUTTING_DOWN,
            ),
            HelmetOperationalState.SHUTTING_DOWN to emptySet(),
        )
    }
}
