package com.example.helmet.core.protocol

data class HslRetryBatch(
    val retryFrames: List<HslFrame>,
    val timedOutSequences: List<Int>,
)

class HslReliableCommandTracker(
    private val ackTimeoutMillis: Long = 200,
    private val maximumRetries: Int = 3,
) {
    private data class Pending(
        val frame: HslFrame,
        val retryCount: Int,
        val deadlineMillis: Long,
    )

    private val pending = linkedMapOf<Int, Pending>()

    fun track(frame: HslFrame, nowMillis: Long) {
        require(frame.flags and HslFlags.ACK_REQUIRED != 0) { "frame does not require ACK" }
        require(frame.sequence !in pending) { "sequence is already pending" }
        pending[frame.sequence] = Pending(frame, retryCount = 0, deadlineMillis = nowMillis + ackTimeoutMillis)
    }

    fun acknowledge(sequence: Int): Boolean = pending.remove(sequence) != null

    fun poll(nowMillis: Long): HslRetryBatch {
        val retries = mutableListOf<HslFrame>()
        val timedOut = mutableListOf<Int>()
        val dueSequences = pending.values
            .filter { it.deadlineMillis <= nowMillis }
            .map { it.frame.sequence }

        dueSequences.forEach { sequence ->
            val item = pending[sequence] ?: return@forEach
            if (item.retryCount >= maximumRetries) {
                pending.remove(sequence)
                timedOut += sequence
            } else {
                pending[sequence] = item.copy(
                    retryCount = item.retryCount + 1,
                    deadlineMillis = nowMillis + ackTimeoutMillis,
                )
                retries += item.frame
            }
        }
        return HslRetryBatch(retries, timedOut)
    }

    fun pendingCount(): Int = pending.size
}

class HslHeartbeatMonitor(
    private val degradedAfterMillis: Long = 3_000,
    private val faultAfterMillis: Long = 5_000,
) {
    private var lastHeartbeatMillis: Long? = null
    private var monitoringSinceMillis: Long? = null

    fun onLinkStarted(nowMillis: Long) {
        monitoringSinceMillis = nowMillis
        lastHeartbeatMillis = null
    }

    fun onHeartbeat(nowMillis: Long) {
        if (monitoringSinceMillis == null) monitoringSinceMillis = nowMillis
        lastHeartbeatMillis = nowMillis
    }

    fun reset() {
        monitoringSinceMillis = null
        lastHeartbeatMillis = null
    }

    fun state(nowMillis: Long): HslLinkState {
        val reference = lastHeartbeatMillis ?: monitoringSinceMillis ?: return HslLinkState.DISCONNECTED
        val elapsed = (nowMillis - reference).coerceAtLeast(0)
        return when {
            elapsed >= faultAfterMillis -> HslLinkState.FAULT
            elapsed >= degradedAfterMillis -> HslLinkState.DEGRADED
            else -> HslLinkState.CONNECTED
        }
    }
}

class HslDuplicateWindow(private val capacity: Int = 64) {
    private val keys = LinkedHashSet<Long>()

    init {
        require(capacity > 0)
    }

    fun accept(type: Int, sequence: Int): Boolean {
        require(type in 0..0xFF)
        require(sequence in 0..0xFFFF)
        val key = (type.toLong() shl 16) or sequence.toLong()
        if (!keys.add(key)) return false
        if (keys.size > capacity) {
            val oldest = keys.iterator().next()
            keys.remove(oldest)
        }
        return true
    }
}
