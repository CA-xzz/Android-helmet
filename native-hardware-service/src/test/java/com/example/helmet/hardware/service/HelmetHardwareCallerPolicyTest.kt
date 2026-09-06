package com.example.helmet.hardware.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HelmetHardwareCallerPolicyTest {
    @Test
    fun onlyTheApplicationUidMayUseTheExportedSignatureService() {
        assertTrue(isInternalHardwareCaller(callingUid = 10_270, applicationUid = 10_270))
        assertFalse(isInternalHardwareCaller(callingUid = 10_271, applicationUid = 10_270))
        assertFalse(isInternalHardwareCaller(callingUid = 0, applicationUid = 10_270))
    }
}
