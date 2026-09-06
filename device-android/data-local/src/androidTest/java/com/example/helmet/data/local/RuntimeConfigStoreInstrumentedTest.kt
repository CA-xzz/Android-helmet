package com.example.helmet.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.RtkTransportMode
import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.model.CircleGeofence
import com.example.helmet.core.model.SafetyThresholdConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class RuntimeConfigStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var runtimeSnapshot: SharedPreferencesSnapshot
    private lateinit var backendCredentialSnapshot: SharedPreferencesSnapshot
    private lateinit var rtkCredentialSnapshot: SharedPreferencesSnapshot

    @Before
    fun snapshotPreferences() {
        context = ApplicationProvider.getApplicationContext()
        runtimeSnapshot = preferences(RuntimeConfigStore.PREFERENCES_NAME).snapshot()
        backendCredentialSnapshot = preferences(BackendCredentialStore.PREFERENCES_NAME).snapshot()
        rtkCredentialSnapshot = preferences(RtkCredentialStore.PREFERENCES_NAME).snapshot()
    }

    @After
    fun restorePreferences() {
        runAllRollbackActions(
            { preferences(RuntimeConfigStore.PREFERENCES_NAME).restore(runtimeSnapshot) },
            { preferences(BackendCredentialStore.PREFERENCES_NAME).restore(backendCredentialSnapshot) },
            { preferences(RtkCredentialStore.PREFERENCES_NAME).restore(rtkCredentialSnapshot) },
        )
    }

    private fun preferences(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Test
    fun safetyThresholdContentChangeRequiresNewVersionBeforeAnyStoreIsWritten() {
        assertTrue(preferences(RuntimeConfigStore.PREFERENCES_NAME).edit().clear().commit())
        val store = RuntimeConfigStore(context)
        val initial = store.update { current ->
            current.copy(
                safetyThresholds = SafetyThresholdConfig(
                    version = 5,
                    heightThresholdMillimetres = 2_500,
                ),
            )
        }

        val invalid = initial.copy(
            revision = initial.revision + 1,
            backendBaseUrl = "https://must-not-be-written.invalid",
            backendBearerToken = "must-not-be-written",
            safetyThresholds = initial.safetyThresholds.copy(heightThresholdMillimetres = 3_000),
        )
        expectIllegalArgument { store.save(invalid) }
        assertEquals(initial, RuntimeConfigStore(context).load())

        val upgraded = invalid.copy(
            safetyThresholds = invalid.safetyThresholds.copy(version = 6),
        )
        store.save(upgraded)
        assertEquals(upgraded, RuntimeConfigStore(context).load())

        expectIllegalArgument {
            store.save(
                upgraded.copy(
                    revision = upgraded.revision + 1,
                    safetyThresholds = upgraded.safetyThresholds.copy(version = 5),
                ),
            )
        }
        assertEquals(upgraded, RuntimeConfigStore(context).load())
    }

    @Test
    fun missingSimulatorPreferenceDefaultsToRealHardware() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences(RuntimeConfigStore.PREFERENCES_NAME, Context.MODE_PRIVATE)

        try {
            assertTrue(preferences.edit().remove("simulator_enabled").commit())
            assertFalse(RuntimeConfigStore(context).load().simulatorEnabled)
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun releaseHardwareModeCorrectionPreservesOtherConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        val original = store.load()
        val simulated = original.copy(
            revision = original.revision + 1,
            simulatorEnabled = true,
            backendBaseUrl = "https://example.invalid",
            backendBearerToken = "hardware-mode-test-token",
        )

        try {
            store.save(simulated)
            val persistence = store.persistRealHardwareMode(
                expectedHardwareDevicePath = simulated.hardwareDevicePath,
                expectedHardwareBaudRate = simulated.hardwareBaudRate,
            )

            val corrected = RuntimeConfigStore(context).load()
            assertFalse(corrected.simulatorEnabled)
            assertEquals(simulated.revision + 1, corrected.revision)
            assertTrue(persistence.changed)
            assertEquals(simulated.revision, persistence.previousRevision)
            assertEquals(simulated.revision + 1, persistence.revision)
            assertEquals(simulated.backendBaseUrl, corrected.backendBaseUrl)
            assertEquals(simulated.backendBearerToken, corrected.backendBearerToken)

            val idempotent = store.persistRealHardwareMode(
                expectedHardwareDevicePath = simulated.hardwareDevicePath,
                expectedHardwareBaudRate = simulated.hardwareBaudRate,
            )
            assertFalse(idempotent.changed)
            assertEquals(persistence.revision, idempotent.previousRevision)
            assertEquals(persistence.revision, idempotent.revision)
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun realHardwareModeCorrectionRejectsUnexpectedUartWithoutTouchingCredentials() {
        val store = RuntimeConfigStore(context)
        val original = store.load()
        val simulated = original.copy(
            revision = original.revision + 1,
            simulatorEnabled = true,
            backendBearerToken = "hardware-mode-preserved-token",
            rtk = original.rtk.copy(password = "hardware-mode-preserved-rtk-password"),
        )

        try {
            store.save(simulated)
            val backendBefore = preferences(BackendCredentialStore.PREFERENCES_NAME).snapshot()
            val rtkBefore = preferences(RtkCredentialStore.PREFERENCES_NAME).snapshot()

            expectIllegalState {
                store.persistRealHardwareMode(
                    expectedHardwareDevicePath = "/dev/ttyAS99",
                    expectedHardwareBaudRate = simulated.hardwareBaudRate,
                )
            }
            assertEquals(simulated, RuntimeConfigStore(context).load())

            val persistence = store.persistRealHardwareMode(
                expectedHardwareDevicePath = simulated.hardwareDevicePath,
                expectedHardwareBaudRate = simulated.hardwareBaudRate,
            )
            assertTrue(persistence.changed)
            assertEquals(simulated.revision + 1, persistence.revision)
            assertEquals(
                backendBefore,
                preferences(BackendCredentialStore.PREFERENCES_NAME).snapshot(),
            )
            assertEquals(
                rtkBefore,
                preferences(RtkCredentialStore.PREFERENCES_NAME).snapshot(),
            )
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun configPersistsAcrossStoreInstances() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        assertTrue(preferences(RuntimeConfigStore.PREFERENCES_NAME).edit().clear().commit())
        try {
            val expected = store.update { current ->
                current.copy(
                    simulatorEnabled = false,
                    hardwareDevicePath = "/dev/ttyAS2",
                    hardwareBaudRate = 115_200,
                    personId = "person-42",
                    backendBaseUrl = "https://example.invalid",
                    backendBearerToken = "test-token",
                    mqttBrokerUri = "ssl://example.invalid:8883",
                    mqttClientCertificateAlias = "helmet-test-certificate",
                    rtk = RtkRuntimeConfig(
                        enabled = true,
                        ntripUrl = "https://ntrip.example.invalid/MOUNT",
                        username = "helmet-42",
                        password = "ntrip-secret",
                        transportMode = RtkTransportMode.DIRECT_UART4,
                        directBaudRate = 460_800,
                    ),
                    localIntercom = LocalIntercomRuntimeConfig(
                        enabled = true,
                        fallbackWhenInternetUnavailable = true,
                        groupId = 42,
                        channel = 7,
                        keySlot = 3,
                    ),
                    geofences = listOf(CircleGeofence("yard", 31.2, 121.4, 100.0, 10.0, 3)),
                    safetyThresholds = SafetyThresholdConfig(
                        version = 7,
                        heightThresholdMillimetres = 3_000,
                        heightHysteresisMillimetres = 300,
                    ),
                )
            }
            assertEquals(expected, RuntimeConfigStore(context).load())
            assertFalse(
                context.getSharedPreferences(
                    BackendCredentialStore.LEGACY_PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ).contains(BackendCredentialStore.LEGACY_TOKEN_KEY),
            )
            val encryptedPreferences = context.getSharedPreferences(
                BackendCredentialStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            assertTrue(encryptedPreferences.all.isNotEmpty())
            assertFalse(encryptedPreferences.all.values.any { value -> value.toString().contains("test-token") })
            val encryptedRtkPreferences = context.getSharedPreferences(
                RtkCredentialStore.PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            )
            assertTrue(encryptedRtkPreferences.all.isNotEmpty())
            assertFalse(encryptedRtkPreferences.all.values.any { value ->
                value.toString().contains("ntrip-secret")
            })
            assertFalse(
                context.getSharedPreferences(
                    BackendCredentialStore.LEGACY_PREFERENCES_NAME,
                    Context.MODE_PRIVATE,
                ).all.values.any { value -> value.toString().contains("ntrip-secret") },
            )
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun mqttPersistenceRequiresACompletePairAndHttpCommandBackend() {
        clearRuntimeConfigurationStores()
        val store = RuntimeConfigStore(context)
        val initial = store.load()
        val invalidConfigurations = listOf(
            initial.copy(
                revision = initial.revision + 1,
                backendBaseUrl = "https://backend.invalid",
                backendBearerToken = "backend-token",
                mqttBrokerUri = "ssl://mqtt.invalid:8883",
            ),
            initial.copy(
                revision = initial.revision + 1,
                backendBaseUrl = "https://backend.invalid",
                backendBearerToken = "backend-token",
                mqttClientCertificateAlias = "helmet-device",
            ),
            initial.copy(
                revision = initial.revision + 1,
                mqttBrokerUri = "ssl://mqtt.invalid:8883",
                mqttClientCertificateAlias = "helmet-device",
            ),
            initial.copy(
                revision = initial.revision + 1,
                backendBaseUrl = "https://backend.invalid",
                mqttBrokerUri = "ssl://mqtt.invalid:8883",
                mqttClientCertificateAlias = "helmet-device",
            ),
            initial.copy(
                revision = initial.revision + 1,
                mqttBrokerUri = " ",
                mqttClientCertificateAlias = "\t",
            ),
        )

        invalidConfigurations.forEach { invalid ->
            expectIllegalArgument { store.save(invalid) }
            assertEquals(initial, store.load())
        }
        expectIllegalArgument {
            store.update { current ->
                current.copy(
                    mqttBrokerUri = "ssl://mqtt.invalid:8883",
                    mqttClientCertificateAlias = "helmet-device",
                )
            }
        }
        assertEquals(initial, store.load())

        val valid = store.update { current ->
            current.copy(
                backendBaseUrl = "https://backend.invalid",
                backendBearerToken = "backend-token",
                mqttBrokerUri = "ssl://mqtt.invalid:8883",
                mqttClientCertificateAlias = "helmet-device",
            )
        }
        assertEquals(valid, store.load())
    }

    @Test
    fun plaintextCredentialIsMigratedOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        val legacyPreferences = context.getSharedPreferences(
            BackendCredentialStore.LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        try {
            store.update { current -> current.copy(backendBearerToken = "") }
            assertTrue(
                legacyPreferences.edit()
                    .putString(BackendCredentialStore.LEGACY_TOKEN_KEY, "legacy-test-token")
                    .commit(),
            )
            assertEquals("legacy-test-token", RuntimeConfigStore(context).load().backendBearerToken)
            assertFalse(legacyPreferences.contains(BackendCredentialStore.LEGACY_TOKEN_KEY))
            assertEquals("legacy-test-token", RuntimeConfigStore(context).load().backendBearerToken)
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun corruptedPreferenceTypesAndValuesRecoverPerField() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        val preferences = context.getSharedPreferences(RuntimeConfigStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        try {
            store.update { current ->
                current.copy(
                    backendBaseUrl = "https://preserved.example.invalid",
                )
            }
            assertTrue(
                preferences.edit()
                    .putString("revision", "wrong-type")
                    .putString("simulator_enabled", "wrong-type")
                    .putInt("person_id", 7)
                    .putInt("hardware_baud_rate", -1)
                    .putString(
                        "geofences_json",
                        """[
                            {"geofenceId":"valid","centerLatitude":31.2,"centerLongitude":121.4,"radiusMeters":50.0},
                            {"geofenceId":"invalid"}
                        ]""".trimIndent(),
                    )
                    .putString(
                        "safety_thresholds_json",
                        """{"electricCalibrationSamples":5,"heightConfirmationSamples":0}""",
                    )
                    .commit(),
            )

            val recovered = RuntimeConfigStore(context).loadWithDiagnostics()

            assertEquals(1, recovered.config.revision)
            assertFalse(recovered.config.simulatorEnabled)
            assertEquals(null, recovered.config.personId)
            assertEquals(115_200, recovered.config.hardwareBaudRate)
            assertEquals("https://preserved.example.invalid", recovered.config.backendBaseUrl)
            assertEquals(listOf("valid"), recovered.config.geofences.map(CircleGeofence::geofenceId))
            assertEquals(5, recovered.config.safetyThresholds.electricCalibrationSamples)
            assertEquals(3, recovered.config.safetyThresholds.heightConfirmationSamples)
            assertTrue(recovered.issues.any { it.field == "revision" && it.kind == RuntimeConfigIssueKind.TYPE_MISMATCH })
            assertTrue(recovered.issues.any { it.field == "hardware_baud_rate" && it.kind == RuntimeConfigIssueKind.INVALID_VALUE })
            assertTrue(recovered.issues.any { it.field == "geofences_json[1]" })
            assertTrue(recovered.issues.any { it.field.endsWith("heightConfirmationSamples") })
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun malformedJsonFallsBackWithoutDiscardingOtherConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        val preferences = context.getSharedPreferences(RuntimeConfigStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
        try {
            store.update { current ->
                current.copy(backendBaseUrl = "https://preserved.example.invalid")
            }
            assertTrue(
                preferences.edit()
                    .putString("geofences_json", "{")
                    .putString("safety_thresholds_json", "[not-an-object]")
                    .commit(),
            )

            val recovered = RuntimeConfigStore(context).loadWithDiagnostics()

            assertEquals("https://preserved.example.invalid", recovered.config.backendBaseUrl)
            assertTrue(recovered.config.geofences.isEmpty())
            assertEquals(SafetyThresholdConfig(), recovered.config.safetyThresholds)
            assertTrue(recovered.issues.count { it.kind == RuntimeConfigIssueKind.INVALID_JSON } >= 2)
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun credentialCorruptionIsExplicitAndCredentialsAreNotUsed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = RuntimeConfigStore(context)
        val backendPreferences = context.getSharedPreferences(
            BackendCredentialStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        val rtkPreferences = context.getSharedPreferences(
            RtkCredentialStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        try {
            store.update { current ->
                current.copy(
                    backendBearerToken = "credential-corruption-test",
                    rtk = current.rtk.copy(password = "rtk-corruption-test"),
                )
            }
            assertTrue(backendPreferences.edit().putString("iv", "not-valid-base64%%%").commit())
            assertTrue(rtkPreferences.edit().putString("ciphertext", "not-valid-base64%%%").commit())

            val recovered = RuntimeConfigStore(context).loadWithDiagnostics()

            assertEquals("", recovered.config.backendBearerToken)
            assertEquals("", recovered.config.rtk.password)
            assertTrue(
                recovered.issues.any {
                    it.field == "backend_bearer_token" && it.kind == RuntimeConfigIssueKind.CREDENTIAL_CORRUPTED
                },
            )
            assertTrue(
                recovered.issues.any {
                    it.field == "rtk_password" && it.kind == RuntimeConfigIssueKind.CREDENTIAL_CORRUPTED
                },
            )
            assertFalse(recovered.issues.toString().contains("credential-corruption-test"))
            assertFalse(recovered.issues.toString().contains("rtk-corruption-test"))
        } finally {
            restorePreferences()
        }
    }

    @Test
    fun staleSnapshotCannotOverwriteRealHardwareModeCorrection() {
        clearRuntimeConfigurationStores()
        val store = RuntimeConfigStore(context)
        val simulated = store.update { current ->
            current.copy(
                simulatorEnabled = true,
                backendBaseUrl = "https://before-correction.invalid",
            )
        }
        val staleUiSnapshot = store.load()

        val correction = store.persistRealHardwareMode(
            expectedHardwareDevicePath = simulated.hardwareDevicePath,
            expectedHardwareBaudRate = simulated.hardwareBaudRate,
        )
        val corrected = store.load()
        assertFalse(corrected.simulatorEnabled)

        expectIllegalState {
            store.save(
                staleUiSnapshot.copy(
                    revision = staleUiSnapshot.revision + 1,
                    backendBaseUrl = "https://stale-ui.invalid",
                ),
            )
        }
        assertEquals(corrected, store.load())

        val updated = store.update { current ->
            current.copy(backendBaseUrl = "https://fresh-ui.invalid")
        }
        assertEquals(correction.revision + 1, updated.revision)
        assertFalse(updated.simulatorEnabled)
        assertEquals("https://fresh-ui.invalid", updated.backendBaseUrl)
    }

    @Test
    fun concurrentLoadsObserveMatchingPreferencesAndCredentials() {
        clearRuntimeConfigurationStores()
        val store = RuntimeConfigStore(context)
        fun pairedUpdate(): RuntimeConfig = store.update { current ->
            val nextRevision = current.revision + 1
            current.copy(
                backendBaseUrl = "https://revision-$nextRevision.invalid",
                backendBearerToken = "token-$nextRevision",
                rtk = current.rtk.copy(
                    username = "revision-$nextRevision",
                    password = "rtk-$nextRevision",
                ),
            )
        }
        pairedUpdate()

        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        val writerFinished = AtomicBoolean(false)
        val mismatches = Collections.synchronizedList(mutableListOf<String>())
        try {
            val writer = executor.submit {
                start.await()
                try {
                    repeat(12) { pairedUpdate() }
                } finally {
                    writerFinished.set(true)
                }
            }
            val reader = executor.submit {
                start.await()
                var reads = 0
                while (!writerFinished.get() || reads < 30) {
                    val loaded = RuntimeConfigStore(context).load()
                    val revision = loaded.revision
                    if (
                        loaded.backendBaseUrl != "https://revision-$revision.invalid" ||
                        loaded.backendBearerToken != "token-$revision" ||
                        loaded.rtk.username != "revision-$revision" ||
                        loaded.rtk.password != "rtk-$revision"
                    ) {
                        mismatches += "inconsistent revision $revision"
                    }
                    reads += 1
                }
            }
            start.countDown()
            writer.get()
            reader.get()
        } finally {
            executor.shutdownNow()
        }

        assertTrue(mismatches.toString(), mismatches.isEmpty())
    }

    @Test
    fun concurrentUpdatesRetainBothChangesAndAdvanceDistinctRevisions() {
        clearRuntimeConfigurationStores()
        val store = RuntimeConfigStore(context)
        store.update { current ->
            current.copy(
                backendBaseUrl = "https://initial-backend.invalid",
                backendBearerToken = "initial-token",
            )
        }
        val initialRevision = store.load().revision
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val backendUpdate = executor.submit<RuntimeConfig> {
                start.await()
                store.update { current ->
                    current.copy(backendBaseUrl = "https://concurrent-backend.invalid")
                }
            }
            val mqttUpdate = executor.submit<RuntimeConfig> {
                start.await()
                store.update { current ->
                    current.copy(
                        mqttBrokerUri = "ssl://concurrent-mqtt.invalid:8883",
                        mqttClientCertificateAlias = "concurrent-certificate",
                    )
                }
            }
            start.countDown()
            val revisions = setOf(backendUpdate.get().revision, mqttUpdate.get().revision)

            assertEquals(setOf(initialRevision + 1, initialRevision + 2), revisions)
        } finally {
            executor.shutdownNow()
        }

        val loaded = store.load()
        assertEquals("https://concurrent-backend.invalid", loaded.backendBaseUrl)
        assertEquals("initial-token", loaded.backendBearerToken)
        assertEquals("ssl://concurrent-mqtt.invalid:8883", loaded.mqttBrokerUri)
        assertEquals("concurrent-certificate", loaded.mqttClientCertificateAlias)
        assertEquals(initialRevision + 2, loaded.revision)
    }

    private fun clearRuntimeConfigurationStores() {
        assertTrue(preferences(RuntimeConfigStore.PREFERENCES_NAME).edit().clear().commit())
        assertTrue(preferences(BackendCredentialStore.PREFERENCES_NAME).edit().clear().commit())
        assertTrue(preferences(RtkCredentialStore.PREFERENCES_NAME).edit().clear().commit())
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
}
