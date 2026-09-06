package com.example.helmet.service.runtime

import com.example.helmet.hardware.api.HardwareEvent
import kotlinx.coroutines.CancellationException

internal suspend fun processHardwareEventSafely(
    event: HardwareEvent,
    process: suspend (HardwareEvent) -> Unit,
    onFailure: suspend (HardwareEvent, Throwable) -> Unit,
) {
    try {
        process(event)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onFailure(event, error)
    }
}
