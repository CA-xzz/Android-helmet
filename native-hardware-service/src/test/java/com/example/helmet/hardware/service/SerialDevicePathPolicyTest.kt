package com.example.helmet.hardware.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SerialDevicePathPolicyTest {
    @Test
    fun productionPolicyOnlyAllowsDeclaredSerialNodeFamilies() {
        listOf(
            "/dev/ttyAS2",
            "/dev/ttyS0",
            "/dev/ttyUSB99",
            "/dev/ttyACM123",
        ).forEach { path ->
            assertTrue(path, SerialDevicePathPolicy.isAllowed(path, allowTestPty = false))
        }

        listOf(
            "/dev/pts/0",
            "/dev/ttyAS",
            "/dev/ttyUSB1000",
            "/dev/ttyAS2/../mem",
            "/data/local/tmp/ttyAS2",
            "/dev/socket/helmet",
        ).forEach { path ->
            assertFalse(path, SerialDevicePathPolicy.isAllowed(path, allowTestPty = false))
        }
    }
}
