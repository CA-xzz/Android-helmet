package com.example.helmet.service.runtime

internal data class SafetyAlarmPersistenceResult<T>(
    val persisted: Boolean,
    val value: T? = null,
)

internal suspend fun <T> persistAndAcknowledgeSafetyAlarm(
    sequence: Int?,
    persist: suspend () -> T,
    acknowledge: suspend (acknowledgedSequence: Int, resultCode: Int) -> Unit,
): SafetyAlarmPersistenceResult<T> {
    val persisted = runCatching { persist() }
    if (persisted.isFailure) {
        sequence?.let { runCatching { acknowledge(it, SAFETY_ALARM_RESULT_INTERNAL_ERROR) } }
        return SafetyAlarmPersistenceResult(persisted = false)
    }
    sequence?.let { runCatching { acknowledge(it, SAFETY_ALARM_RESULT_SUCCESS) } }
    return SafetyAlarmPersistenceResult(persisted = true, value = persisted.getOrThrow())
}

internal const val SAFETY_ALARM_RESULT_SUCCESS = 0
internal const val SAFETY_ALARM_RESULT_INTERNAL_ERROR = 7
