package com.example.helmet.service.runtime

import com.example.helmet.data.local.DurableIdentityConflictException
import com.example.helmet.hardware.api.HardwareAcknowledgementResult

internal data class SafetyAlarmPersistenceResult<T>(
    val persisted: Boolean,
    val value: T? = null,
    val error: Throwable? = null,
)

internal suspend fun <T, A : Any> persistAndAcknowledgeSafetyAlarm(
    acknowledgement: A?,
    persist: suspend () -> T,
    acknowledge: suspend (acknowledgement: A, resultCode: Int) -> Unit,
): SafetyAlarmPersistenceResult<T> {
    val persisted = runCatching { persist() }
    if (persisted.isFailure) {
        val error = requireNotNull(persisted.exceptionOrNull())
        if (error is DurableIdentityConflictException) {
            acknowledgement?.let {
                runCatching {
                    acknowledge(it, HardwareAcknowledgementResult.FIELD_OUT_OF_RANGE.wireValue)
                }
            }
        }
        // Transient Room/I/O failures intentionally send no ACK so the module retries. A reused
        // durable business identity with different content is a protocol conflict and is rejected.
        return SafetyAlarmPersistenceResult(
            persisted = false,
            error = error,
        )
    }
    acknowledgement?.let { runCatching { acknowledge(it, SAFETY_ALARM_RESULT_SUCCESS) } }
    return SafetyAlarmPersistenceResult(persisted = true, value = persisted.getOrThrow())
}

internal suspend fun <T, A : Any> persistAndAcknowledgeSafetySample(
    acknowledgement: A?,
    persist: suspend () -> T,
    acknowledge: suspend (acknowledgement: A, resultCode: Int) -> Unit,
): SafetyAlarmPersistenceResult<T> = persistAndAcknowledgeSafetyAlarm(
    acknowledgement = acknowledgement,
    persist = persist,
    acknowledge = acknowledge,
)

internal val SAFETY_ALARM_RESULT_SUCCESS = HardwareAcknowledgementResult.SUCCESS.wireValue
