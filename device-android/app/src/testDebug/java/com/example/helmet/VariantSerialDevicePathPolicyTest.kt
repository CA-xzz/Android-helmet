package com.example.helmet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class VariantSerialDevicePathPolicyTest {
    @Test
    fun debugBuildAllowsOnlyCanonicalNumericAdditionalDevicePaths() {
        assertEquals(
            "/dev/pts/17",
            LocalConfigurationPolicy.normalizeHardwareDevicePath(
                "/dev/pts/17",
                productionBuild = false,
            ),
        )
        listOf(
            "/dev/pts/",
            "/dev/pts/-1",
            "/dev/pts/000000",
            "/dev/pts/1/../2",
            "/dev/ptmx",
        ).forEach { path ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalConfigurationPolicy.normalizeHardwareDevicePath(path, productionBuild = false)
            }
        }
    }
}
