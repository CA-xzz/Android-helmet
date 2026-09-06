package com.example.helmet.service.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SafetyOutputRequestIdStoreTest {
    @Test
    fun allocationAvoidsIdsAlreadyMappedToCollidingLegacyAlarmHashes() {
        val firstAlarm = 1L
        val secondAlarm = 0x1_0000_0000L
        val firstLegacyHash = (firstAlarm xor (firstAlarm ushr 32)) and 0xFFFF_FFFFL
        val secondLegacyHash = (secondAlarm xor (secondAlarm ushr 32)) and 0xFFFF_FFFFL
        assertEquals(firstLegacyHash, secondLegacyHash)

        val first = nextAvailableRequestId(start = 1, inUse = emptySet())
        val second = nextAvailableRequestId(start = first, inUse = setOf(first))

        assertNotEquals(first, second)
        assertEquals(1, first)
        assertEquals(2, second)
    }

    @Test
    fun allocationWrapsAndSkipsPendingIds() {
        assertEquals(
            2,
            nextAvailableRequestId(
                start = 0xFFFF_FFFFL,
                inUse = setOf(0xFFFF_FFFFL, 1L),
            ),
        )
    }
}
