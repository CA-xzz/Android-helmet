package com.example.helmet.hardware.api

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform

internal data class HardwareEventQueueOffer(
    val accepted: Boolean,
    val overflowCount: Long,
)

/**
 * Binder callbacks cannot suspend. A channel makes rejection observable, while batching keeps all
 * events derived from one HSL frame atomic for acknowledgement purposes.
 */
internal class HardwareEventQueue(capacity: Int) {
    private val batches = Channel<List<HardwareEvent>>(capacity)
    private val overflows = AtomicLong()

    init {
        require(capacity > 0)
    }

    val events: Flow<HardwareEvent> = batches.receiveAsFlow().transform { batch ->
        batch.forEach { event -> emit(event) }
    }

    fun offer(batch: List<HardwareEvent>): HardwareEventQueueOffer {
        require(batch.isNotEmpty())
        val accepted = batches.trySend(batch).isSuccess
        return HardwareEventQueueOffer(
            accepted = accepted,
            overflowCount = if (accepted) overflows.get() else overflows.incrementAndGet(),
        )
    }
}

internal enum class ReliableInboundDisposition {
    DELIVERED,
    DUPLICATE,
    BACKPRESSURED,
}

internal data class ReliableInboundAdmissionResult(
    val disposition: ReliableInboundDisposition,
    val sendSuccessAcknowledgement: Boolean,
)

internal class ReliableInboundAdmission(
    private val alreadyAccepted: (type: Int, sequence: Int) -> Boolean,
    private val rememberAccepted: (type: Int, sequence: Int) -> Unit,
) {
    fun admit(type: Int, sequence: Int, offer: () -> Boolean): ReliableInboundAdmissionResult {
        if (alreadyAccepted(type, sequence)) {
            return ReliableInboundAdmissionResult(
                ReliableInboundDisposition.DUPLICATE,
                sendSuccessAcknowledgement = true,
            )
        }
        if (!offer()) {
            return ReliableInboundAdmissionResult(
                ReliableInboundDisposition.BACKPRESSURED,
                sendSuccessAcknowledgement = false,
            )
        }
        rememberAccepted(type, sequence)
        return ReliableInboundAdmissionResult(
            ReliableInboundDisposition.DELIVERED,
            sendSuccessAcknowledgement = true,
        )
    }
}
