package com.example.helmet.hardware.service

import org.junit.Assert.assertFalse
import org.junit.Test

class VariantSerialDevicePathPolicyTest {
    @Test
    fun releaseVariantRejectsAdditionalDevicePathsEvenWhenRequested() {
        assertFalse(
            SerialDevicePathPolicy.isAllowed(
                "/dev/pts/17",
                allowTestPty = true,
            ),
        )
    }
}
