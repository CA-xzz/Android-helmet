package com.example.helmet

import org.junit.Assert.assertThrows
import org.junit.Test

class VariantSerialDevicePathPolicyTest {
    @Test
    fun releaseVariantRejectsAdditionalDevicePathsForEveryRuntimeFlag() {
        listOf(false, true).forEach { productionBuild ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalConfigurationPolicy.normalizeHardwareDevicePath(
                    "/dev/pts/17",
                    productionBuild = productionBuild,
                )
            }
        }
    }
}
