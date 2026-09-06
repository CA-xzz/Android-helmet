package com.example.helmet.hardware.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantSerialDevicePathPolicyTest {
    @Test
    fun debugVariantAllowsOnlyCanonicalNumericAdditionalDevicePaths() {
        listOf("/dev/pts/0", "/dev/pts/12", "/dev/pts/99999").forEach { path ->
            assertTrue(path, SerialDevicePathPolicy.isAllowed(path, allowTestPty = true))
        }

        listOf(
            "/dev/pts/",
            "/dev/pts/-1",
            "/dev/pts/000000",
            "/dev/pts/1/../2",
            "/dev/pts/1\n",
            "/dev/ptmx",
        ).forEach { path ->
            assertFalse(path, SerialDevicePathPolicy.isAllowed(path, allowTestPty = true))
        }
    }
}
