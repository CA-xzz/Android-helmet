package com.example.helmet.hardware.api

import android.content.Context
import java.security.SecureRandom

internal data class ReliableSequenceRecovery(val next: Int, val issue: String?)

internal fun recoverReliableSequence(raw: Any?, randomValue: () -> Int): ReliableSequenceRecovery =
    if (raw is Int && raw in 0..0xFFFF) {
        ReliableSequenceRecovery(raw, null)
    } else {
        ReliableSequenceRecovery(
            next = randomValue() and 0xFFFF,
            issue = if (raw == null) null else "INVALID_PERSISTED_RELIABLE_SEQUENCE",
        )
    }

internal class ReliableSequenceAllocator(
    initialNext: Int,
    private val persistNext: (Int) -> Unit,
) {
    private var next = initialNext and 0xFFFF

    @Synchronized
    fun allocate(isPending: (Int) -> Boolean): Int {
        repeat(SEQUENCE_SPACE) {
            val candidate = next
            next = (next + 1) and 0xFFFF
            // Persist the next value before exposing the candidate. A process crash can skip a
            // sequence but cannot reuse the last issued reliable sequence.
            persistNext(next)
            if (!isPending(candidate)) return candidate
        }
        error("all HSL reliable sequences are pending")
    }

    companion object {
        private const val SEQUENCE_SPACE = 0x1_0000
    }
}

internal class PersistentReliableSequenceAllocator(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val storedValue = runCatching { preferences.all[KEY_NEXT] }
    private val recovery = if (storedValue.isFailure) {
        ReliableSequenceRecovery(
            SecureRandom().nextInt(0x1_0000),
            "RELIABLE_SEQUENCE_STORAGE_READ_FAILED",
        )
    } else {
        recoverReliableSequence(
            raw = storedValue.getOrNull(),
            randomValue = { SecureRandom().nextInt(0x1_0000) },
        )
    }
    val recoveryIssue: String? = recovery.issue
    private val allocator = ReliableSequenceAllocator(
        initialNext = recovery.next,
        persistNext = { next ->
            check(preferences.edit().putInt(KEY_NEXT, next).commit()) {
                "failed to persist HSL reliable sequence"
            }
        },
    )

    fun allocate(isPending: (Int) -> Boolean): Int = allocator.allocate(isPending)

    companion object {
        internal const val PREFERENCES_NAME = "helmet_hsl_reliable_sequence"
        internal const val KEY_NEXT = "next_sequence"
    }
}
