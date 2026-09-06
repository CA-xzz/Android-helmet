package com.example.helmet.hardware.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReliableSequenceAllocatorTest {
    @Test
    fun persistsBeforeIssuingAndSkipsPendingSequencesAcrossWrap() {
        val persisted = mutableListOf<Int>()
        val allocator = ReliableSequenceAllocator(0xFFFF, persisted::add)

        assertEquals(1, allocator.allocate { it == 0xFFFF || it == 0 })
        assertEquals(listOf(0, 1, 2), persisted)
    }

    @Test
    fun restartedAllocatorBeginsAtPersistedNextSequence() {
        var persisted = -1
        val first = ReliableSequenceAllocator(41) { persisted = it }
        assertEquals(41, first.allocate { false })

        val restarted = ReliableSequenceAllocator(persisted) { persisted = it }
        assertEquals(42, restarted.allocate { false })
    }

    @Test
    fun refusesAllocationWhenEverySequenceIsPending() {
        val allocator = ReliableSequenceAllocator(0) { }
        assertThrows(IllegalStateException::class.java) {
            allocator.allocate { true }
        }
    }

    @Test
    fun corruptedStoredTypeOrRangeUsesBoundedRandomRecovery() {
        assertEquals(
            ReliableSequenceRecovery(123, "INVALID_PERSISTED_RELIABLE_SEQUENCE"),
            recoverReliableSequence("wrong-type") { 123 },
        )
        assertEquals(
            ReliableSequenceRecovery(456, "INVALID_PERSISTED_RELIABLE_SEQUENCE"),
            recoverReliableSequence(70_000) { 456 },
        )
        assertEquals(ReliableSequenceRecovery(7, null), recoverReliableSequence(7) { 999 })
    }

    @Test
    fun persistenceFailurePreventsSequenceFromBeingIssued() {
        val allocator = ReliableSequenceAllocator(9) { throw IllegalStateException("commit failed") }

        assertThrows(IllegalStateException::class.java) { allocator.allocate { false } }
    }
}
