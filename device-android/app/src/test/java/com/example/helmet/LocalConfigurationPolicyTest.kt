package com.example.helmet

import android.Manifest
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.data.local.RuntimeConfigStore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalConfigurationPolicyTest {
    @Test
    fun productionBuildNeverExposesOrEnablesSimulation() {
        assertFalse(LocalConfigurationPolicy.simulatorControlsVisible(productionBuild = true))
        assertFalse(
            LocalConfigurationPolicy.effectiveSimulatorEnabled(
                productionBuild = true,
                requested = true,
            ),
        )
    }

    @Test
    fun debugSimulationStillRequiresAnExplicitRequest() {
        assertTrue(LocalConfigurationPolicy.simulatorControlsVisible(productionBuild = false))
        assertFalse(LocalConfigurationPolicy.effectiveSimulatorEnabled(false, requested = false))
        assertTrue(LocalConfigurationPolicy.effectiveSimulatorEnabled(false, requested = true))
    }

    @Test
    fun permissionPolicyIncludesAudioWithoutAConnectedCamera() {
        assertEquals(
            setOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            LocalConfigurationPolicy.requiredRuntimePermissions(
                cameraAvailable = false,
                locationProviderAvailable = true,
                bluetoothAvailable = false,
            ),
        )
    }

    @Test
    fun permissionPolicyIncludesOptionalCameraAndBluetoothCapabilities() {
        assertEquals(
            setOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.BLUETOOTH_CONNECT,
            ),
            LocalConfigurationPolicy.requiredRuntimePermissions(
                cameraAvailable = true,
                locationProviderAvailable = false,
                bluetoothAvailable = true,
            ),
        )
    }

    @Test
    fun backendProvisioningAcceptsHttpsAndExactLoopbackHttpOnly() {
        assertEquals(
            "https://helmet.example.test",
            LocalConfigurationPolicy.normalizeBackendBaseUrl(
                " HTTPS://HELMET.EXAMPLE.TEST/ ",
                productionBuild = true,
            ),
        )
        assertEquals(
            "http://127.0.0.1:18080",
            LocalConfigurationPolicy.normalizeBackendBaseUrl(
                "http://127.0.0.1:18080/",
                productionBuild = false,
            ),
        )
        assertEquals(
            "http://[::1]:18080",
            LocalConfigurationPolicy.normalizeBackendBaseUrl(
                "http://[::1]:18080",
                productionBuild = false,
            ),
        )
        listOf(
            "http://127.0.0.1:18080" to true,
            "http://127.0.0.2:18080" to false,
            "http://192.0.2.1" to false,
            "https://user:secret@helmet.example.test" to true,
            "https://helmet.example.test/api" to true,
            "https://helmet.example.test?token=secret" to true,
            "https://helmet.example.test#fragment" to true,
        ).forEach { (raw, productionBuild) ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalConfigurationPolicy.normalizeBackendBaseUrl(raw, productionBuild)
            }
        }
    }

    @Test
    fun ntripProvisioningRequiresTlsOrExactDebugLoopbackAndOneMountPoint() {
        assertEquals(
            "https://caster.example.test:8443/MOUNT_1",
            LocalConfigurationPolicy.normalizeNtripUrl(
                "HTTPS://CASTER.EXAMPLE.TEST:8443/MOUNT_1",
                productionBuild = true,
            ),
        )
        assertEquals(
            "http://localhost:2101/MOUNT",
            LocalConfigurationPolicy.normalizeNtripUrl(
                "http://localhost:2101/MOUNT",
                productionBuild = false,
            ),
        )
        listOf(
            "http://localhost:2101/MOUNT" to true,
            "http://127.0.0.2:2101/MOUNT" to false,
            "https://user:secret@caster.example.test/MOUNT" to true,
            "https://caster.example.test/MOUNT?token=secret" to true,
            "https://caster.example.test/group/MOUNT" to true,
            "https://caster.example.test/" to true,
        ).forEach { (raw, productionBuild) ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalConfigurationPolicy.normalizeNtripUrl(raw, productionBuild)
            }
        }
    }

    @Test
    fun mqttHardwareAndSafetyProvisioningRejectUnsupportedValues() {
        assertEquals(
            "ssl://mqtt.example.test:8883" to "helmet-device",
            LocalConfigurationPolicy.normalizeMqttConfiguration(
                "SSL://MQTT.EXAMPLE.TEST:8883",
                "helmet-device",
            ),
        )
        listOf(
            "tcp://127.0.0.1:1883",
            "ssl://mqtt.example.test:1883",
            "ssl://user@mqtt.example.test:8883",
            "ssl://mqtt.example.test:8883/path",
        ).forEach { uri ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalConfigurationPolicy.normalizeMqttConfiguration(uri, "helmet-device")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.normalizeMqttConfiguration("ssl://mqtt.example.test:8883", "bad alias")
        }

        assertEquals(
            "/dev/ttyAS2",
            LocalConfigurationPolicy.normalizeHardwareDevicePath(" /dev/ttyAS2 ", productionBuild = true),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.normalizeHardwareDevicePath("/dev/pts/17", productionBuild = true)
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.normalizeHardwareDevicePath("/dev/ttyAS4", productionBuild = true)
        }
        assertEquals(
            "/dev/ttyAS4",
            LocalConfigurationPolicy.normalizeHardwareDevicePath("/dev/ttyAS4", productionBuild = false),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.normalizeHardwareDevicePath("/dev/ttyAS2/../mem", productionBuild = false)
        }
        assertEquals(115_200, LocalConfigurationPolicy.validateHardwareBaudRate(115_200))
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.validateHardwareBaudRate(123_456)
        }

        val defaults = RuntimeConfig().safetyThresholds
        assertEquals(
            defaults,
            LocalConfigurationPolicy.parseCompleteSafetyThresholds(
                RuntimeConfigStore.safetyThresholdsToJson(defaults),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.parseCompleteSafetyThresholds(
                JSONObject(RuntimeConfigStore.safetyThresholdsToJson(defaults))
                    .put("electricPresentThresholdMilliVolts", 900)
                    .toString(),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.parseCompleteSafetyThresholds(
                JSONObject(RuntimeConfigStore.safetyThresholdsToJson(defaults))
                    .put("misspelledThreshold", 1)
                    .toString(),
            )
        }
    }

    @Test
    fun releaseStartupPersistentlyRemovesStaleDebugAndIncompleteCredentialState() {
        val stale = RuntimeConfig(
            revision = 7,
            simulatorEnabled = true,
            hardwareDevicePath = "/dev/pts/17",
            hardwareBaudRate = 123_456,
            backendBaseUrl = "http://127.0.0.1:18080",
            backendBearerToken = "debug-backend-secret",
            mqttBrokerUri = "tcp://127.0.0.1:1883",
            mqttClientCertificateAlias = "debug-client",
            rtk = RtkRuntimeConfig(
                enabled = true,
                ntripUrl = "http://127.0.0.1:2101/MOUNT",
                username = "debug-user",
                password = "debug-rtk-secret",
            ),
        )
        val saves = mutableListOf<RuntimeConfig>()

        val result = LocalConfigurationPolicy.sanitizeAndPersistRuntimeConfiguration(
            productionBuild = true,
            load = { stale },
            save = saves::add,
        )

        assertTrue(result.persisted)
        assertEquals(1, saves.size)
        assertEquals(8, result.config.revision)
        assertFalse(result.config.simulatorEnabled)
        assertEquals("", result.config.backendBaseUrl)
        assertEquals("", result.config.backendBearerToken)
        assertEquals("", result.config.mqttBrokerUri)
        assertEquals("", result.config.mqttClientCertificateAlias)
        assertEquals(RtkRuntimeConfig(), result.config.rtk)
        assertEquals("/dev/ttyAS2", result.config.hardwareDevicePath)
        assertEquals(115_200, result.config.hardwareBaudRate)
        assertEquals(result.config, saves.single())
        assertTrue(result.changedFields.contains("backendBearerToken"))
        assertTrue(result.changedFields.contains("rtk.password"))
        assertFalse(result.changedFields.any { it.contains("secret", ignoreCase = true) })
    }

    @Test
    fun releaseStartupSanitizationIsIdempotentAndDebugDoesNotRewrite() {
        val valid = RuntimeConfig(
            revision = 11,
            backendBaseUrl = "https://helmet.example.test",
            backendBearerToken = "opaque-token",
            mqttBrokerUri = "ssl://mqtt.example.test:8883",
            mqttClientCertificateAlias = "helmet-device",
            rtk = RtkRuntimeConfig(
                enabled = true,
                ntripUrl = "https://caster.example.test/MOUNT",
                username = "helmet",
                password = "opaque-password",
            ),
        )
        var saves = 0
        val releaseResult = LocalConfigurationPolicy.sanitizeAndPersistRuntimeConfiguration(
            productionBuild = true,
            load = { valid },
            save = { saves += 1 },
        )
        val debugResult = LocalConfigurationPolicy.sanitizeAndPersistRuntimeConfiguration(
            productionBuild = false,
            load = { valid.copy(simulatorEnabled = true) },
            save = { saves += 1 },
        )

        assertFalse(releaseResult.persisted)
        assertTrue(releaseResult.changedFields.isEmpty())
        assertFalse(debugResult.persisted)
        assertTrue(debugResult.config.simulatorEnabled)
        assertEquals(0, saves)
    }

    @Test
    fun mqttRequiresHttpCommandBackendAndReleaseSanitizationClearsItWithoutOne() {
        val mqttWithoutBackend = RuntimeConfig(
            revision = 17,
            mqttBrokerUri = "ssl://mqtt.example.test:8883",
            mqttClientCertificateAlias = "helmet-device",
        )

        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.requireHttpBackendForMqtt(mqttWithoutBackend)
        }
        LocalConfigurationPolicy.requireHttpBackendForMqtt(
            mqttWithoutBackend.copy(
                backendBaseUrl = "https://backend.example.test",
                backendBearerToken = "device-token",
            ),
        )

        val sanitized = LocalConfigurationPolicy.sanitizeReleaseRuntimeConfiguration(mqttWithoutBackend)
        assertEquals(18, sanitized.config.revision)
        assertEquals("", sanitized.config.mqttBrokerUri)
        assertEquals("", sanitized.config.mqttClientCertificateAlias)
        assertTrue(sanitized.changedFields.contains("mqttBrokerUri"))
        assertTrue(sanitized.changedFields.contains("mqttClientCertificateAlias"))
    }

    @Test
    fun clearingBackendAlsoClearsMqttAndPreservesUnrelatedConfiguration() {
        val configured = RuntimeConfig(
            revision = 23,
            personId = "person-23",
            backendBaseUrl = "https://backend.example.test",
            backendBearerToken = "device-token",
            mqttBrokerUri = "ssl://mqtt.example.test:8883",
            mqttClientCertificateAlias = "helmet-device",
        )

        val cleared = LocalConfigurationPolicy.clearBackendAndMqttConfiguration(configured)

        assertEquals("", cleared.backendBaseUrl)
        assertEquals("", cleared.backendBearerToken)
        assertEquals("", cleared.mqttBrokerUri)
        assertEquals("", cleared.mqttClientCertificateAlias)
        assertEquals(configured.revision, cleared.revision)
        assertEquals(configured.personId, cleared.personId)
    }

    @Test
    fun sanitizationWriteFailurePropagatesInsteadOfRunningWithRejectedState() {
        val failure = assertThrows(IllegalStateException::class.java) {
            LocalConfigurationPolicy.sanitizeAndPersistRuntimeConfiguration(
                productionBuild = true,
                load = { RuntimeConfig(simulatorEnabled = true) },
                save = { throw IllegalStateException("write failed") },
            )
        }
        assertEquals("write failed", failure.message)
    }

    @Test
    fun changedConfigurationFailsClosedWhenRevisionIsExhausted() {
        var saves = 0
        val failure = assertThrows(IllegalStateException::class.java) {
            LocalConfigurationPolicy.sanitizeAndPersistRuntimeConfiguration(
                productionBuild = true,
                load = {
                    RuntimeConfig(
                        revision = Long.MAX_VALUE,
                        simulatorEnabled = true,
                    )
                },
                save = { saves += 1 },
            )
        }

        assertEquals("runtime configuration revision is exhausted", failure.message)
        assertEquals(0, saves)
        assertEquals(Long.MAX_VALUE, LocalConfigurationPolicy.nextRevision(Long.MAX_VALUE - 1))
        assertThrows(IllegalStateException::class.java) {
            LocalConfigurationPolicy.nextRevision(Long.MAX_VALUE)
        }
    }

    @Test
    fun credentialsRejectHeaderInjectionAndEmptyProvisioning() {
        assertEquals("opaque-token", LocalConfigurationPolicy.validateBearerToken("opaque-token"))
        assertEquals("user", LocalConfigurationPolicy.validateNtripUsername(" user "))
        assertEquals("password", LocalConfigurationPolicy.validateNtripPassword("password"))
        listOf("", "token\r\nInjected: value").forEach { token ->
            assertThrows(IllegalArgumentException::class.java) {
                LocalConfigurationPolicy.validateBearerToken(token)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.validateNtripUsername("user:name")
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalConfigurationPolicy.validateNtripPassword("password\nnext")
        }
    }
}
