package com.example.helmet.service.runtime

import android.content.Context
import java.security.SecureRandom

internal class LocalIntercomRequestIdAllocator(
    initialNext: Long,
    private val persistNext: (Long) -> Unit,
) {
    private var next = initialNext.takeIf { it in 1..MAX_REQUEST_ID } ?: 1L

    @Synchronized
    fun allocate(): Long {
        val value = next
        next = if (value == MAX_REQUEST_ID) 1L else value + 1L
        persistNext(next)
        return value
    }

    companion object {
        const val MAX_REQUEST_ID = 0xFFFF_FFFFL
    }
}

internal class PersistentLocalIntercomRequestIdAllocator(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val stored = runCatching { preferences.all[KEY_NEXT] }
    private val recovered = stored.getOrNull()
    val recoveryIssue: String? = when {
        stored.isFailure -> "LOCAL_INTERCOM_REQUEST_ID_READ_FAILED"
        recovered == null -> null
        recovered !is Long || recovered !in 1..LocalIntercomRequestIdAllocator.MAX_REQUEST_ID ->
            "INVALID_LOCAL_INTERCOM_REQUEST_ID"
        else -> null
    }
    private val allocator = LocalIntercomRequestIdAllocator(
        initialNext = (recovered as? Long)
            ?.takeIf { it in 1..LocalIntercomRequestIdAllocator.MAX_REQUEST_ID }
            ?: (SecureRandom().nextInt().toLong() and 0xFFFF_FFFFL).coerceAtLeast(1L),
        persistNext = { next ->
            check(preferences.edit().putLong(KEY_NEXT, next).commit()) {
                "failed to persist local intercom request ID"
            }
        },
    )

    fun allocate(): Long = allocator.allocate()

    companion object {
        internal const val PREFERENCES_NAME = "helmet_local_intercom_request_ids"
        internal const val KEY_NEXT = "next_request_id"
    }
}
