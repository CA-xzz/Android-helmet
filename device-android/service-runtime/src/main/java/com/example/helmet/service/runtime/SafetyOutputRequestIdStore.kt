package com.example.helmet.service.runtime

import android.content.Context

internal class SafetyOutputRequestIdStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)

    fun getOrAllocate(alarmId: Long): Long = synchronized(processLock) {
        require(alarmId > 0) { "safety output alarm ID must be positive" }
        val snapshot = preferences.all
        val mappingKey = mappingKey(alarmId)
        snapshot[mappingKey]?.let { raw ->
            return@synchronized requireValidRequestId(raw, "stored safety output request ID")
        }
        val next = snapshot[NEXT_REQUEST_ID_KEY]?.let { raw ->
            requireValidRequestId(raw, "safety output request ID counter")
        } ?: MIN_REQUEST_ID
        val inUse = snapshot.asSequence()
            .filter { (key, _) -> key.startsWith(MAPPING_KEY_PREFIX) }
            .map { (_, raw) -> requireValidRequestId(raw, "stored safety output request ID") }
            .toHashSet()
        val allocated = nextAvailableRequestId(next, inUse)
        val following = incrementRequestId(allocated)
        check(
            preferences.edit()
                .putLong(mappingKey, allocated)
                .putLong(NEXT_REQUEST_ID_KEY, following)
                .commit(),
        ) { "failed to persist safety output request ID" }
        allocated
    }

    fun release(alarmId: Long, requestId: Long): Boolean = synchronized(processLock) {
        require(alarmId > 0)
        require(requestId in MIN_REQUEST_ID..MAX_REQUEST_ID)
        val key = mappingKey(alarmId)
        val raw = preferences.all[key] ?: return@synchronized true
        if (requireValidRequestId(raw, "stored safety output request ID") != requestId) {
            return@synchronized false
        }
        preferences.edit().remove(key).commit()
    }

    private fun mappingKey(alarmId: Long): String = "$MAPPING_KEY_PREFIX$alarmId"

    companion object {
        private const val PREFERENCES_NAME = "helmet_safety_output_request_ids"
        private const val NEXT_REQUEST_ID_KEY = "next_request_id"
        private const val MAPPING_KEY_PREFIX = "alarm:"
        private const val MIN_REQUEST_ID = 1L
        private const val MAX_REQUEST_ID = 0xFFFF_FFFFL
        private val processLock = Any()
    }
}

internal fun nextAvailableRequestId(start: Long, inUse: Set<Long>): Long {
    require(start in 1..0xFFFF_FFFFL)
    require(inUse.all { it in 1..0xFFFF_FFFFL })
    var candidate = start
    repeat(inUse.size + 1) {
        if (candidate !in inUse) return candidate
        candidate = incrementRequestId(candidate)
    }
    error("safety output request ID space is exhausted")
}

private fun incrementRequestId(value: Long): Long = if (value == 0xFFFF_FFFFL) 1 else value + 1

private fun requireValidRequestId(raw: Any?, label: String): Long {
    val value = raw as? Long ?: error("$label has an invalid stored type")
    require(value in 1..0xFFFF_FFFFL) { "$label is outside the u32 range" }
    return value
}
