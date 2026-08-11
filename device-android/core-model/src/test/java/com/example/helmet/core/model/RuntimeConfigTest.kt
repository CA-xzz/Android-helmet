package com.example.helmet.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeConfigTest {
    @Test
    fun optionalPersonBindingUsesBackendIdentifierFormat() {
        assertNull(RuntimeConfig().personId)
        assertEquals("worker-42:night", RuntimeConfig(personId = "worker-42:night").personId)
        assertThrows(IllegalArgumentException::class.java) { RuntimeConfig(personId = "worker 42") }
        assertThrows(IllegalArgumentException::class.java) { RuntimeConfig(personId = "x".repeat(129)) }
    }
}
