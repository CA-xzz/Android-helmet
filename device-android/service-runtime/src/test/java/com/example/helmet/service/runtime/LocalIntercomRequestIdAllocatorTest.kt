package com.example.helmet.service.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LocalIntercomRequestIdAllocatorTest {
    @Test
    fun processRestartContinuesFromPersistedNextId() {
        var persisted = 0L
        val first = LocalIntercomRequestIdAllocator(41) { persisted = it }
        assertEquals(41L, first.allocate())

        val restarted = LocalIntercomRequestIdAllocator(persisted) { persisted = it }
        assertEquals(42L, restarted.allocate())
    }

    @Test
    fun wrapsToOneAndPersistsBeforeReturning() {
        val stored = mutableListOf<Long>()
        val allocator = LocalIntercomRequestIdAllocator(
            LocalIntercomRequestIdAllocator.MAX_REQUEST_ID,
            stored::add,
        )

        assertEquals(LocalIntercomRequestIdAllocator.MAX_REQUEST_ID, allocator.allocate())
        assertEquals(1L, stored.single())
        assertEquals(1L, allocator.allocate())
    }

    @Test
    fun persistenceFailurePreventsIdIssue() {
        val allocator = LocalIntercomRequestIdAllocator(7) { error("commit failed") }
        assertThrows(IllegalStateException::class.java) { allocator.allocate() }
    }
}
