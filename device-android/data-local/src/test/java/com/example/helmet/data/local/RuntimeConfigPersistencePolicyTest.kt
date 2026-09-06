package com.example.helmet.data.local

import com.example.helmet.core.model.SafetyThresholdConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RuntimeConfigPersistencePolicyTest {
    @Test
    fun ordinarySaveRequiresExactlyNextRevision() {
        validateNextRuntimeConfigRevision(storedRevision = 5, incomingRevision = 6)

        expectIllegalState {
            validateNextRuntimeConfigRevision(storedRevision = 5, incomingRevision = 5)
        }
        expectIllegalState {
            validateNextRuntimeConfigRevision(storedRevision = 5, incomingRevision = 7)
        }
    }

    @Test
    fun exhaustedRevisionCannotBeAdvanced() {
        expectIllegalState {
            validateNextRuntimeConfigRevision(
                storedRevision = Long.MAX_VALUE,
                incomingRevision = Long.MAX_VALUE,
            )
        }
    }

    @Test
    fun changedThresholdValuesRequireStrictlyGreaterVersion() {
        val previous = SafetyThresholdConfig(version = 7)

        expectIllegalArgument {
            validateSafetyThresholdTransition(
                previous,
                previous.copy(heightThresholdMillimetres = 2_500),
            )
        }
        validateSafetyThresholdTransition(
            previous,
            previous.copy(version = 8, heightThresholdMillimetres = 2_500),
        )
    }

    @Test
    fun thresholdVersionCannotBeReusedOrRolledBack() {
        val previous = SafetyThresholdConfig(version = 7)

        validateSafetyThresholdTransition(previous, previous)
        validateSafetyThresholdTransition(previous, previous.copy(version = 8))
        expectIllegalArgument {
            validateSafetyThresholdTransition(previous, previous.copy(version = 6))
        }
    }

    @Test
    fun credentialFailurePreventsOrdinaryConfigurationCommit() {
        var configurationCommitted = false

        try {
            persistRuntimeConfiguration(
                writeCredentials = { throw IllegalStateException("credential write failed") },
                commitPreferences = {
                    configurationCommitted = true
                    true
                },
            )
            fail("credential failure must propagate")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertFalse(configurationCommitted)
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun expectIllegalState(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalStateException")
        } catch (_: IllegalStateException) {
            // Expected.
        }
    }

    @Test
    fun ordinaryConfigurationCommitsOnlyAfterCredentials() {
        val order = mutableListOf<String>()

        persistRuntimeConfiguration(
            writeCredentials = { order += "credentials" },
            commitPreferences = {
                order += "configuration"
                true
            },
        )

        assertEquals(listOf("credentials", "configuration"), order)
    }

    @Test
    fun credentialFailureRollsBackBothCredentialAndOrdinaryStores() {
        val order = mutableListOf<String>()

        try {
            persistRuntimeConfiguration(
                writeCredentials = {
                    order += "backend-written"
                    throw IllegalStateException("RTK credential write failed")
                },
                commitPreferences = {
                    order += "configuration-commit"
                    true
                },
                rollbackPreferences = { order += "configuration-rollback" },
                rollbackCredentials = { order += "credentials-rollback" },
            )
            fail("credential failure must propagate")
        } catch (_: IllegalStateException) {
            // Expected.
        }

        assertEquals(
            listOf("backend-written", "configuration-rollback", "credentials-rollback"),
            order,
        )
    }

    @Test
    fun ordinaryCommitFailureRollsBackEveryStoreAndPreservesRollbackDiagnostics() {
        val order = mutableListOf<String>()

        val failure = try {
            persistRuntimeConfiguration(
                writeCredentials = { order += "credentials-written" },
                commitPreferences = {
                    order += "configuration-commit"
                    false
                },
                rollbackPreferences = {
                    order += "configuration-rollback"
                    throw IllegalArgumentException("ordinary rollback failed")
                },
                rollbackCredentials = { order += "credentials-rollback" },
            )
            fail("commit failure must propagate")
            error("unreachable")
        } catch (error: IllegalStateException) {
            error
        }

        assertEquals(
            listOf(
                "credentials-written",
                "configuration-commit",
                "configuration-rollback",
                "credentials-rollback",
            ),
            order,
        )
        assertTrue(failure.suppressed.any { it is IllegalArgumentException })
    }
}
