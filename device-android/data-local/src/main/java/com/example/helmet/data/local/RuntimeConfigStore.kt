package com.example.helmet.data.local

import android.content.Context
import com.example.helmet.core.model.CircleGeofence
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.RtkTransportMode
import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.model.SafetyThresholdConfig
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeConfigIssueKind {
    STORAGE_READ_FAILED,
    TYPE_MISMATCH,
    INVALID_VALUE,
    INVALID_JSON,
    CREDENTIAL_CORRUPTED,
    CREDENTIAL_MIGRATION_FAILED,
}

data class RuntimeConfigIssue(
    val field: String,
    val kind: RuntimeConfigIssueKind,
    val errorType: String? = null,
)

data class RuntimeConfigLoadResult(
    val config: RuntimeConfig,
    val issues: List<RuntimeConfigIssue>,
)

data class RealHardwareModePersistenceResult(
    val previousRevision: Long,
    val revision: Long,
    val changed: Boolean,
    val hardwareDevicePath: String,
    val hardwareBaudRate: Int,
)

class RuntimeConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val credentialStore = BackendCredentialStore(context.applicationContext)
    private val rtkCredentialStore = RtkCredentialStore(context.applicationContext)

    fun load(): RuntimeConfig = loadWithDiagnostics().config

    fun loadWithDiagnostics(): RuntimeConfigLoadResult = synchronized(RUNTIME_CONFIG_LOCK) {
        loadWithDiagnosticsLocked()
    }

    private fun loadWithDiagnosticsLocked(): RuntimeConfigLoadResult {
        val issues = mutableListOf<RuntimeConfigIssue>()
        val values = runCatching { preferences.all }
            .getOrElse { error ->
                issues += RuntimeConfigIssue(
                    field = "runtime_preferences",
                    kind = RuntimeConfigIssueKind.STORAGE_READ_FAILED,
                    errorType = error.javaClass.name,
                )
                emptyMap()
            }
        val reader = RecoveringPreferences(values, issues::add)
        val defaults = RuntimeConfig()
        val revision = reader.long("revision", defaults.revision).validOrDefault(
            field = "revision",
            default = defaults.revision,
            issues = issues,
        ) { it >= 1 }
        val hardwareDevicePath = reader.string(
            "hardware_device_path",
            defaults.hardwareDevicePath,
        ).validOrDefault(
            field = "hardware_device_path",
            default = defaults.hardwareDevicePath,
            issues = issues,
        ) { it.isNotBlank() && it.length <= 256 && '\r' !in it && '\n' !in it }
        val hardwareBaudRate = reader.int(
            "hardware_baud_rate",
            defaults.hardwareBaudRate,
        ).validOrDefault(
            field = "hardware_baud_rate",
            default = defaults.hardwareBaudRate,
            issues = issues,
        ) { it in 1_200..4_000_000 }
        val personId = reader.optionalString("person_id")?.let { stored ->
            runCatching { defaults.copy(personId = stored).personId }
                .getOrElse {
                    issues += RuntimeConfigIssue("person_id", RuntimeConfigIssueKind.INVALID_VALUE)
                    defaults.personId
                }
        }
        val backendBaseUrl = reader.string("backend_base_url", defaults.backendBaseUrl)
            .safeConfigurationString("backend_base_url", 8_192, defaults.backendBaseUrl, issues)
        val mqttBrokerUri = reader.string("mqtt_broker_uri", defaults.mqttBrokerUri)
            .safeConfigurationString("mqtt_broker_uri", 2_048, defaults.mqttBrokerUri, issues)
        val mqttCertificateAlias = reader.string(
            "mqtt_client_certificate_alias",
            defaults.mqttClientCertificateAlias,
        ).safeConfigurationString(
            "mqtt_client_certificate_alias",
            256,
            defaults.mqttClientCertificateAlias,
            issues,
        )
        val rtkPassword = readRtkPassword(issues)
            .safeConfigurationString("rtk_password", 512, "", issues)
        val rtkTransportMode = reader.string("rtk_transport_mode", defaults.rtk.transportMode.name).let { stored ->
            runCatching { RtkTransportMode.valueOf(stored) }.getOrElse {
                issues += RuntimeConfigIssue("rtk_transport_mode", RuntimeConfigIssueKind.INVALID_VALUE)
                defaults.rtk.transportMode
            }
        }
        val directRtkPath = reader.string("rtk_direct_device_path", defaults.rtk.directDevicePath)
            .validOrDefault("rtk_direct_device_path", defaults.rtk.directDevicePath, issues) {
                it == "/dev/ttyAS4"
            }
        val directRtkBaudRate = reader.int("rtk_direct_baud_rate", defaults.rtk.directBaudRate)
            .validOrDefault("rtk_direct_baud_rate", defaults.rtk.directBaudRate, issues) {
                it in RtkRuntimeConfig.SUPPORTED_DIRECT_RTK_BAUD_RATES
            }
        val rtk = RtkRuntimeConfig(
            enabled = reader.boolean("rtk_enabled", defaults.rtk.enabled),
            ntripUrl = reader.string("ntrip_url", defaults.rtk.ntripUrl)
                .safeConfigurationString("ntrip_url", 2_048, defaults.rtk.ntripUrl, issues),
            username = reader.string("ntrip_username", defaults.rtk.username)
                .validOrDefault("ntrip_username", defaults.rtk.username, issues) {
                    it.length <= 256 && ':' !in it && '\r' !in it && '\n' !in it
                },
            password = rtkPassword,
            transportMode = rtkTransportMode,
            directDevicePath = directRtkPath,
            directBaudRate = directRtkBaudRate,
        )
        val localIntercom = LocalIntercomRuntimeConfig(
            enabled = reader.boolean("local_intercom_enabled", defaults.localIntercom.enabled),
            fallbackWhenInternetUnavailable = reader.boolean(
                "local_intercom_fallback_when_offline",
                defaults.localIntercom.fallbackWhenInternetUnavailable,
            ),
            groupId = reader.int("local_intercom_group_id", defaults.localIntercom.groupId)
                .validOrDefault("local_intercom_group_id", defaults.localIntercom.groupId, issues) {
                    it in 1..0xFFFF
                },
            channel = reader.int("local_intercom_channel", defaults.localIntercom.channel)
                .validOrDefault("local_intercom_channel", defaults.localIntercom.channel, issues) {
                    it in 0..0xFF
                },
            keySlot = reader.int("local_intercom_key_slot", defaults.localIntercom.keySlot)
                .validOrDefault("local_intercom_key_slot", defaults.localIntercom.keySlot, issues) {
                    it in 1..0xFF
                },
        )
        val geofences = recoverGeofences(reader.string("geofences_json", "[]"), issues)
        val safetyThresholds = recoverSafetyThresholds(
            reader.string("safety_thresholds_json", "{}"),
            issues,
        )
        return RuntimeConfigLoadResult(
            config = RuntimeConfig(
                revision = revision,
                simulatorEnabled = reader.boolean("simulator_enabled", defaults.simulatorEnabled),
                hardwareDevicePath = hardwareDevicePath,
                hardwareBaudRate = hardwareBaudRate,
                personId = personId,
                backendBaseUrl = backendBaseUrl,
                backendBearerToken = loadBackendBearerToken(reader, issues),
                mqttBrokerUri = mqttBrokerUri,
                mqttClientCertificateAlias = mqttCertificateAlias,
                rtk = rtk,
                localIntercom = localIntercom,
                geofences = geofences,
                safetyThresholds = safetyThresholds,
            ),
            issues = issues.distinct(),
        )
    }

    fun save(config: RuntimeConfig) = synchronized(RUNTIME_CONFIG_LOCK) {
        validateNextRuntimeConfigRevision(
            storedRevision = storedRevisionLocked(),
            incomingRevision = config.revision,
        )
        saveLocked(config)
    }

    /**
     * Applies one configuration change to the latest stored value and assigns its next revision.
     * The transform must not assign a revision itself.
     */
    fun update(transform: (RuntimeConfig) -> RuntimeConfig): RuntimeConfig =
        synchronized(RUNTIME_CONFIG_LOCK) {
            val current = loadWithDiagnosticsLocked().config
            val transformed = transform(current)
            check(transformed.revision == current.revision) {
                "runtime configuration update must not assign a revision"
            }
            val nextRevision = current.revision.nextRuntimeConfigRevision()
            validateNextRuntimeConfigRevision(
                storedRevision = current.revision,
                incomingRevision = nextRevision,
            )
            val updated = transformed.copy(revision = nextRevision)
            saveLocked(updated)
            updated
        }

    private fun storedRevisionLocked(): Long {
        val defaults = RuntimeConfig()
        val raw = runCatching { preferences.all["revision"] }.getOrNull()
        return (raw as? Long)?.takeIf { it >= 1 } ?: defaults.revision
    }

    private fun Long.nextRuntimeConfigRevision(): Long {
        check(this in 1 until Long.MAX_VALUE) {
            "runtime configuration revision is exhausted"
        }
        return this + 1
    }

    private fun saveLocked(config: RuntimeConfig) {
        validateCommandTransportConfiguration(config)
        val previousThresholds = recoverSafetyThresholds(
            raw = runCatching {
                preferences.all["safety_thresholds_json"] as? String ?: "{}"
            }.getOrDefault("{}"),
            issues = mutableListOf(),
        )
        validateSafetyThresholdTransition(previousThresholds, config.safetyThresholds)
        val geofencesJson = geofencesToJson(config.geofences)
        val safetyThresholdsJson = safetyThresholdsToJson(config.safetyThresholds)
        // Capture every store before changing any of them. Encrypted preference records can be
        // restored byte-for-byte because a save does not replace their Android Keystore keys.
        val preferencesSnapshot = preferences.snapshot()
        val backendCredentialSnapshot = credentialStore.snapshot()
        val rtkCredentialSnapshot = rtkCredentialStore.snapshot()
        val editor = preferences.edit()
            .putLong("revision", config.revision)
            .putBoolean("simulator_enabled", config.simulatorEnabled)
            .putString("hardware_device_path", config.hardwareDevicePath)
            .putInt("hardware_baud_rate", config.hardwareBaudRate)
            .putString("person_id", config.personId)
            .putString("backend_base_url", config.backendBaseUrl)
            .remove(BackendCredentialStore.LEGACY_TOKEN_KEY)
            .putString("mqtt_broker_uri", config.mqttBrokerUri)
            .putString("mqtt_client_certificate_alias", config.mqttClientCertificateAlias)
            .putBoolean("rtk_enabled", config.rtk.enabled)
            .putString("rtk_transport_mode", config.rtk.transportMode.name)
            .putString("rtk_direct_device_path", config.rtk.directDevicePath)
            .putInt("rtk_direct_baud_rate", config.rtk.directBaudRate)
            .putString("ntrip_url", config.rtk.ntripUrl)
            .putString("ntrip_username", config.rtk.username)
            .putBoolean("local_intercom_enabled", config.localIntercom.enabled)
            .putBoolean(
                "local_intercom_fallback_when_offline",
                config.localIntercom.fallbackWhenInternetUnavailable,
            )
            .putInt("local_intercom_group_id", config.localIntercom.groupId)
            .putInt("local_intercom_channel", config.localIntercom.channel)
            .putInt("local_intercom_key_slot", config.localIntercom.keySlot)
            .putString("geofences_json", geofencesJson)
            .putString("safety_thresholds_json", safetyThresholdsJson)
        persistRuntimeConfiguration(
            writeCredentials = {
                credentialStore.write(config.backendBearerToken)
                rtkCredentialStore.writePassword(config.rtk.password)
            },
            commitPreferences = editor::commit,
            rollbackCredentials = {
                runAllRollbackActions(
                    { rtkCredentialStore.restore(rtkCredentialSnapshot) },
                    { credentialStore.restore(backendCredentialSnapshot) },
                )
            },
            rollbackPreferences = { preferences.restore(preferencesSnapshot) },
        )
    }

    /**
     * Persists the transition from simulation to the already provisioned UART configuration.
     *
     * This deliberately reads and writes only the hardware-mode fields. In particular, it does
     * not load, migrate, rewrite, or snapshot either credential store. The expected UART values
     * make a board-side mode switch fail closed if the installed configuration is not the one the
     * caller intends to activate.
     */
    fun persistRealHardwareMode(
        expectedHardwareDevicePath: String,
        expectedHardwareBaudRate: Int,
    ): RealHardwareModePersistenceResult = synchronized(RUNTIME_CONFIG_LOCK) {
        require(
            expectedHardwareDevicePath.isNotBlank() &&
                expectedHardwareDevicePath.length <= 256 &&
                '\r' !in expectedHardwareDevicePath &&
                '\n' !in expectedHardwareDevicePath,
        ) { "expected hardware device path is invalid" }
        require(expectedHardwareBaudRate in 1_200..4_000_000) {
            "expected hardware baud rate is invalid"
        }

        val defaults = RuntimeConfig()
        val previousRevision = preferences.getLong("revision", defaults.revision)
        check(previousRevision >= 1) { "runtime configuration revision is invalid" }
        val simulatorEnabled = preferences.getBoolean(
            "simulator_enabled",
            defaults.simulatorEnabled,
        )
        val simulatorModeIsExplicit = preferences.contains("simulator_enabled")
        val hardwareDevicePath = preferences.getString(
            "hardware_device_path",
            defaults.hardwareDevicePath,
        ).orEmpty()
        val hardwareBaudRate = preferences.getInt(
            "hardware_baud_rate",
            defaults.hardwareBaudRate,
        )
        check(hardwareDevicePath == expectedHardwareDevicePath) {
            "stored hardware device path does not match the expected configuration"
        }
        check(hardwareBaudRate == expectedHardwareBaudRate) {
            "stored hardware baud rate does not match the expected configuration"
        }

        if (!simulatorEnabled && simulatorModeIsExplicit) {
            return@synchronized RealHardwareModePersistenceResult(
                previousRevision = previousRevision,
                revision = previousRevision,
                changed = false,
                hardwareDevicePath = hardwareDevicePath,
                hardwareBaudRate = hardwareBaudRate,
            )
        }

        check(previousRevision < Long.MAX_VALUE) {
            "runtime configuration revision is exhausted"
        }
        val revision = previousRevision + 1
        check(
            preferences.edit()
                .putLong("revision", revision)
                .putBoolean("simulator_enabled", false)
                .commit(),
        ) { "failed to persist real hardware mode" }
        check(preferences.getLong("revision", -1L) == revision) {
            "real hardware mode revision was not persisted"
        }
        check(!preferences.getBoolean("simulator_enabled", true)) {
            "real hardware mode was not persisted"
        }
        RealHardwareModePersistenceResult(
            previousRevision = previousRevision,
            revision = revision,
            changed = true,
            hardwareDevicePath = hardwareDevicePath,
            hardwareBaudRate = hardwareBaudRate,
        )
    }

    private fun loadBackendBearerToken(
        reader: RecoveringPreferences,
        issues: MutableList<RuntimeConfigIssue>,
    ): String {
        val encrypted = credentialStore.read()
        if (encrypted.state == CredentialReadState.CORRUPTED) {
            issues += RuntimeConfigIssue(
                "backend_bearer_token",
                RuntimeConfigIssueKind.CREDENTIAL_CORRUPTED,
                encrypted.errorType,
            )
        }
        val legacy = reader.string(BackendCredentialStore.LEGACY_TOKEN_KEY, "")
        if (legacy.isBlank()) return encrypted.value
        val effective = encrypted.value.ifBlank { legacy }
        val migrated = runCatching {
            if (encrypted.value.isBlank()) credentialStore.write(legacy)
            check(preferences.edit().remove(BackendCredentialStore.LEGACY_TOKEN_KEY).commit())
        }
        if (migrated.isFailure) {
            issues += RuntimeConfigIssue(
                "backend_bearer_token",
                RuntimeConfigIssueKind.CREDENTIAL_MIGRATION_FAILED,
                migrated.exceptionOrNull()?.javaClass?.name,
            )
        }
        return effective
    }

    private fun readRtkPassword(issues: MutableList<RuntimeConfigIssue>): String {
        val result = rtkCredentialStore.readPassword()
        if (result.state == CredentialReadState.CORRUPTED) {
            issues += RuntimeConfigIssue(
                "rtk_password",
                RuntimeConfigIssueKind.CREDENTIAL_CORRUPTED,
                result.errorType,
            )
        }
        return result.value
    }

    companion object {
        internal const val PREFERENCES_NAME = "helmet_runtime_config"

        fun parseGeofences(raw: String): List<CircleGeofence> {
            val array = JSONArray(raw.ifBlank { "[]" })
            return buildList {
                for (index in 0 until array.length()) {
                    val value = array.getJSONObject(index)
                    add(
                        CircleGeofence(
                            geofenceId = value.getString("geofenceId"),
                            centerLatitude = value.getDouble("centerLatitude"),
                            centerLongitude = value.getDouble("centerLongitude"),
                            radiusMeters = value.getDouble("radiusMeters"),
                            hysteresisMeters = value.optDouble("hysteresisMeters", 5.0),
                            confirmationSamples = value.optInt("confirmationSamples", 2),
                        ),
                    )
                }
            }.also { geofences ->
                require(geofences.map(CircleGeofence::geofenceId).toSet().size == geofences.size) {
                    "duplicate geofence ID"
                }
            }
        }

        fun geofencesToJson(geofences: Collection<CircleGeofence>): String =
            JSONArray().also { array ->
                geofences.forEach { geofence ->
                    array.put(
                        JSONObject()
                            .put("geofenceId", geofence.geofenceId)
                            .put("centerLatitude", geofence.centerLatitude)
                            .put("centerLongitude", geofence.centerLongitude)
                            .put("radiusMeters", geofence.radiusMeters)
                            .put("hysteresisMeters", geofence.hysteresisMeters)
                            .put("confirmationSamples", geofence.confirmationSamples),
                    )
                }
            }.toString()

        fun parseSafetyThresholds(raw: String): SafetyThresholdConfig {
            val defaults = SafetyThresholdConfig()
            val value = JSONObject(raw.ifBlank { "{}" })
            return SafetyThresholdConfig(
                version = value.optInt("version", defaults.version),
                freeFallThresholdMilliG = value.optInt("freeFallThresholdMilliG", defaults.freeFallThresholdMilliG),
                freeFallMinimumMillis = value.optLong("freeFallMinimumMillis", defaults.freeFallMinimumMillis),
                fallImpactThresholdMilliG = value.optInt("fallImpactThresholdMilliG", defaults.fallImpactThresholdMilliG),
                impactThresholdMilliG = value.optInt("impactThresholdMilliG", defaults.impactThresholdMilliG),
                fallImpactWindowMillis = value.optLong("fallImpactWindowMillis", defaults.fallImpactWindowMillis),
                motionCooldownMillis = value.optLong("motionCooldownMillis", defaults.motionCooldownMillis),
                shakeAccelerationThresholdMilliG = value.optInt(
                    "shakeAccelerationThresholdMilliG",
                    defaults.shakeAccelerationThresholdMilliG,
                ),
                shakeGyroThresholdMilliDegreesPerSecond = value.optInt(
                    "shakeGyroThresholdMilliDegreesPerSecond",
                    defaults.shakeGyroThresholdMilliDegreesPerSecond,
                ),
                shakeDirectionChanges = value.optInt("shakeDirectionChanges", defaults.shakeDirectionChanges),
                shakeWindowMillis = value.optLong("shakeWindowMillis", defaults.shakeWindowMillis),
                inactivityAccelerationToleranceMilliG = value.optInt(
                    "inactivityAccelerationToleranceMilliG",
                    defaults.inactivityAccelerationToleranceMilliG,
                ),
                inactivityGyroToleranceMilliDegreesPerSecond = value.optInt(
                    "inactivityGyroToleranceMilliDegreesPerSecond",
                    defaults.inactivityGyroToleranceMilliDegreesPerSecond,
                ),
                inactivityMinimumMillis = value.optLong(
                    "inactivityMinimumMillis",
                    defaults.inactivityMinimumMillis,
                ),
                electricCalibrationSamples = value.optInt(
                    "electricCalibrationSamples",
                    defaults.electricCalibrationSamples,
                ),
                electricCalibrationStabilityMilliVolts = value.optInt(
                    "electricCalibrationStabilityMilliVolts",
                    defaults.electricCalibrationStabilityMilliVolts,
                ),
                electricPresentThresholdMilliVolts = value.optInt(
                    "electricPresentThresholdMilliVolts",
                    defaults.electricPresentThresholdMilliVolts,
                ),
                electricHighThresholdMilliVolts = value.optInt(
                    "electricHighThresholdMilliVolts",
                    defaults.electricHighThresholdMilliVolts,
                ),
                electricCriticalThresholdMilliVolts = value.optInt(
                    "electricCriticalThresholdMilliVolts",
                    defaults.electricCriticalThresholdMilliVolts,
                ),
                electricHysteresisMilliVolts = value.optInt(
                    "electricHysteresisMilliVolts",
                    defaults.electricHysteresisMilliVolts,
                ),
                electricConfirmationSamples = value.optInt(
                    "electricConfirmationSamples",
                    defaults.electricConfirmationSamples,
                ),
                electricMinimumMilliVolts = value.optInt(
                    "electricMinimumMilliVolts",
                    defaults.electricMinimumMilliVolts,
                ),
                electricMaximumMilliVolts = value.optInt(
                    "electricMaximumMilliVolts",
                    defaults.electricMaximumMilliVolts,
                ),
                heightCalibrationSamples = value.optInt(
                    "heightCalibrationSamples",
                    defaults.heightCalibrationSamples,
                ),
                heightCalibrationStabilityMillimetres = value.optInt(
                    "heightCalibrationStabilityMillimetres",
                    defaults.heightCalibrationStabilityMillimetres,
                ),
                heightThresholdMillimetres = value.optInt(
                    "heightThresholdMillimetres",
                    defaults.heightThresholdMillimetres,
                ),
                heightHysteresisMillimetres = value.optInt(
                    "heightHysteresisMillimetres",
                    defaults.heightHysteresisMillimetres,
                ),
                heightConfirmationSamples = value.optInt(
                    "heightConfirmationSamples",
                    defaults.heightConfirmationSamples,
                ),
                heightConfirmationMillis = value.optLong(
                    "heightConfirmationMillis",
                    defaults.heightConfirmationMillis,
                ),
                heightDriftStabilityMillis = value.optLong(
                    "heightDriftStabilityMillis",
                    defaults.heightDriftStabilityMillis,
                ),
                heightDriftMaximumRateMillimetresPerSecond = value.optInt(
                    "heightDriftMaximumRateMillimetresPerSecond",
                    defaults.heightDriftMaximumRateMillimetresPerSecond,
                ),
                heightDriftWindowMillimetres = value.optInt(
                    "heightDriftWindowMillimetres",
                    defaults.heightDriftWindowMillimetres,
                ),
                heightBaselineAdjustmentDivisor = value.optInt(
                    "heightBaselineAdjustmentDivisor",
                    defaults.heightBaselineAdjustmentDivisor,
                ),
                heightMinimumMillimetres = value.optInt(
                    "heightMinimumMillimetres",
                    defaults.heightMinimumMillimetres,
                ),
                heightMaximumMillimetres = value.optInt(
                    "heightMaximumMillimetres",
                    defaults.heightMaximumMillimetres,
                ),
            )
        }

        fun safetyThresholdsToJson(config: SafetyThresholdConfig): String = JSONObject()
            .put("version", config.version)
            .put("freeFallThresholdMilliG", config.freeFallThresholdMilliG)
            .put("freeFallMinimumMillis", config.freeFallMinimumMillis)
            .put("fallImpactThresholdMilliG", config.fallImpactThresholdMilliG)
            .put("impactThresholdMilliG", config.impactThresholdMilliG)
            .put("fallImpactWindowMillis", config.fallImpactWindowMillis)
            .put("motionCooldownMillis", config.motionCooldownMillis)
            .put("shakeAccelerationThresholdMilliG", config.shakeAccelerationThresholdMilliG)
            .put("shakeGyroThresholdMilliDegreesPerSecond", config.shakeGyroThresholdMilliDegreesPerSecond)
            .put("shakeDirectionChanges", config.shakeDirectionChanges)
            .put("shakeWindowMillis", config.shakeWindowMillis)
            .put("inactivityAccelerationToleranceMilliG", config.inactivityAccelerationToleranceMilliG)
            .put(
                "inactivityGyroToleranceMilliDegreesPerSecond",
                config.inactivityGyroToleranceMilliDegreesPerSecond,
            )
            .put("inactivityMinimumMillis", config.inactivityMinimumMillis)
            .put("electricCalibrationSamples", config.electricCalibrationSamples)
            .put("electricCalibrationStabilityMilliVolts", config.electricCalibrationStabilityMilliVolts)
            .put("electricPresentThresholdMilliVolts", config.electricPresentThresholdMilliVolts)
            .put("electricHighThresholdMilliVolts", config.electricHighThresholdMilliVolts)
            .put("electricCriticalThresholdMilliVolts", config.electricCriticalThresholdMilliVolts)
            .put("electricHysteresisMilliVolts", config.electricHysteresisMilliVolts)
            .put("electricConfirmationSamples", config.electricConfirmationSamples)
            .put("electricMinimumMilliVolts", config.electricMinimumMilliVolts)
            .put("electricMaximumMilliVolts", config.electricMaximumMilliVolts)
            .put("heightCalibrationSamples", config.heightCalibrationSamples)
            .put("heightCalibrationStabilityMillimetres", config.heightCalibrationStabilityMillimetres)
            .put("heightThresholdMillimetres", config.heightThresholdMillimetres)
            .put("heightHysteresisMillimetres", config.heightHysteresisMillimetres)
            .put("heightConfirmationSamples", config.heightConfirmationSamples)
            .put("heightConfirmationMillis", config.heightConfirmationMillis)
            .put("heightDriftStabilityMillis", config.heightDriftStabilityMillis)
            .put(
                "heightDriftMaximumRateMillimetresPerSecond",
                config.heightDriftMaximumRateMillimetresPerSecond,
            )
            .put("heightDriftWindowMillimetres", config.heightDriftWindowMillimetres)
            .put("heightBaselineAdjustmentDivisor", config.heightBaselineAdjustmentDivisor)
            .put("heightMinimumMillimetres", config.heightMinimumMillimetres)
            .put("heightMaximumMillimetres", config.heightMaximumMillimetres)
            .toString()
    }
}

/**
 * Threshold versions are durable interpretation boundaries for raw safety samples and
 * detector checkpoints. Reusing a version for different values could replay old samples
 * with new limits, while rolling a version back could make a retired checkpoint current.
 */
internal fun validateSafetyThresholdTransition(
    previous: SafetyThresholdConfig,
    next: SafetyThresholdConfig,
) {
    require(next.version >= previous.version) {
        "safety threshold version must not decrease"
    }
    val valuesChanged = previous.copy(version = next.version) != next
    require(!valuesChanged || next.version > previous.version) {
        "changed safety thresholds require a strictly greater version"
    }
}

internal fun validateCommandTransportConfiguration(config: RuntimeConfig) {
    val mqttDisabled = config.mqttBrokerUri.isEmpty() && config.mqttClientCertificateAlias.isEmpty()
    val mqttConfigured = config.mqttBrokerUri.isNotBlank() &&
        config.mqttClientCertificateAlias.isNotBlank()
    require(mqttDisabled || mqttConfigured) {
        "MQTT broker URI and client certificate alias must both be configured"
    }
    if (mqttConfigured) {
        require(config.backendBaseUrl.isNotBlank() && config.backendBearerToken.isNotBlank()) {
            "HTTP backend URL and bearer token are required for MQTT command wake"
        }
    }
}

internal fun validateNextRuntimeConfigRevision(
    storedRevision: Long,
    incomingRevision: Long,
) {
    check(storedRevision in 1 until Long.MAX_VALUE) {
        "runtime configuration revision is exhausted"
    }
    check(incomingRevision == storedRevision + 1) {
        "runtime configuration revision conflict"
    }
}

private val RUNTIME_CONFIG_LOCK = Any()

internal fun persistRuntimeConfiguration(
    writeCredentials: () -> Unit,
    commitPreferences: () -> Boolean,
    rollbackCredentials: () -> Unit = {},
    rollbackPreferences: () -> Unit = {},
) {
    try {
        writeCredentials()
        check(commitPreferences()) { "failed to persist runtime configuration" }
    } catch (failure: Throwable) {
        runCatching {
            runAllRollbackActions(rollbackPreferences, rollbackCredentials)
        }.exceptionOrNull()?.let(failure::addSuppressed)
        throw failure
    }
}

private class RecoveringPreferences(
    private val values: Map<String, *>,
    private val report: (RuntimeConfigIssue) -> Unit,
) {
    fun long(key: String, default: Long): Long = typed(key, default)
    fun int(key: String, default: Int): Int = typed(key, default)
    fun boolean(key: String, default: Boolean): Boolean = typed(key, default)
    fun string(key: String, default: String): String = typed(key, default)
    fun optionalString(key: String): String? {
        if (key !in values) return null
        val value = values[key]
        if (value is String) return value
        report(
            RuntimeConfigIssue(
                field = key,
                kind = RuntimeConfigIssueKind.TYPE_MISMATCH,
                errorType = value?.javaClass?.name ?: "null",
            ),
        )
        return null
    }

    private inline fun <reified T> typed(key: String, default: T): T {
        if (key !in values) return default
        val value = values[key]
        if (value is T) return value
        report(
            RuntimeConfigIssue(
                field = key,
                kind = RuntimeConfigIssueKind.TYPE_MISMATCH,
                errorType = value?.javaClass?.name ?: "null",
            ),
        )
        return default
    }
}

private fun <T> T.validOrDefault(
    field: String,
    default: T,
    issues: MutableList<RuntimeConfigIssue>,
    valid: (T) -> Boolean,
): T = if (valid(this)) {
    this
} else {
    issues += RuntimeConfigIssue(field, RuntimeConfigIssueKind.INVALID_VALUE)
    default
}

private fun String.safeConfigurationString(
    field: String,
    maximumLength: Int,
    default: String,
    issues: MutableList<RuntimeConfigIssue>,
): String = validOrDefault(field, default, issues) {
    it.length <= maximumLength && '\r' !in it && '\n' !in it
}

private fun recoverGeofences(
    raw: String,
    issues: MutableList<RuntimeConfigIssue>,
): List<CircleGeofence> {
    val array = runCatching { JSONArray(raw.ifBlank { "[]" }) }
        .getOrElse { error ->
            issues += RuntimeConfigIssue(
                "geofences_json",
                RuntimeConfigIssueKind.INVALID_JSON,
                error.javaClass.name,
            )
            return emptyList()
        }
    val result = mutableListOf<CircleGeofence>()
    val identifiers = mutableSetOf<String>()
    for (index in 0 until array.length()) {
        val parsed = runCatching {
            val value = array.getJSONObject(index)
            CircleGeofence(
                geofenceId = value.getString("geofenceId"),
                centerLatitude = value.getDouble("centerLatitude"),
                centerLongitude = value.getDouble("centerLongitude"),
                radiusMeters = value.getDouble("radiusMeters"),
                hysteresisMeters = value.optDouble("hysteresisMeters", 5.0),
                confirmationSamples = value.optInt("confirmationSamples", 2),
            )
        }
        if (parsed.isFailure) {
            val error = requireNotNull(parsed.exceptionOrNull())
            issues += RuntimeConfigIssue(
                "geofences_json[$index]",
                RuntimeConfigIssueKind.INVALID_VALUE,
                error.javaClass.name,
            )
            continue
        }
        val geofence = parsed.getOrThrow()
        if (!identifiers.add(geofence.geofenceId)) {
            issues += RuntimeConfigIssue(
                "geofences_json[$index].geofenceId",
                RuntimeConfigIssueKind.INVALID_VALUE,
            )
            continue
        }
        result += geofence
    }
    return result
}

private fun recoverSafetyThresholds(
    raw: String,
    issues: MutableList<RuntimeConfigIssue>,
): SafetyThresholdConfig {
    val value = runCatching { JSONObject(raw.ifBlank { "{}" }) }
        .getOrElse { error ->
            issues += RuntimeConfigIssue(
                "safety_thresholds_json",
                RuntimeConfigIssueKind.INVALID_JSON,
                error.javaClass.name,
            )
            return SafetyThresholdConfig()
        }
    SAFETY_NUMERIC_FIELDS.forEach { field ->
        if (value.has(field) && value.opt(field) !is Number) {
            value.remove(field)
            issues += RuntimeConfigIssue(
                "safety_thresholds_json.$field",
                RuntimeConfigIssueKind.TYPE_MISMATCH,
            )
        }
    }
    SAFETY_FIELD_RANGES.forEach { (field, range) ->
        if (value.has(field) && value.optLong(field) !in range) {
            value.remove(field)
            issues += RuntimeConfigIssue(
                "safety_thresholds_json.$field",
                RuntimeConfigIssueKind.INVALID_VALUE,
            )
        }
    }
    val defaults = SafetyThresholdConfig()
    invalidateSafetyGroupIf(
        value = value,
        fields = setOf("freeFallThresholdMilliG", "fallImpactThresholdMilliG", "impactThresholdMilliG"),
        issues = issues,
    ) {
        val freeFall = value.optInt("freeFallThresholdMilliG", defaults.freeFallThresholdMilliG)
        val fallImpact = value.optInt("fallImpactThresholdMilliG", defaults.fallImpactThresholdMilliG)
        val impact = value.optInt("impactThresholdMilliG", defaults.impactThresholdMilliG)
        freeFall !in 50 until fallImpact || fallImpact > impact
    }
    invalidateSafetyGroupIf(
        value,
        setOf("freeFallMinimumMillis", "fallImpactWindowMillis"),
        issues,
    ) {
        value.optLong("fallImpactWindowMillis", defaults.fallImpactWindowMillis) <
            value.optLong("freeFallMinimumMillis", defaults.freeFallMinimumMillis)
    }
    invalidateSafetyGroupIf(
        value,
        setOf(
            "electricPresentThresholdMilliVolts",
            "electricHighThresholdMilliVolts",
            "electricCriticalThresholdMilliVolts",
            "electricHysteresisMilliVolts",
        ),
        issues,
    ) {
        val present = value.optInt(
            "electricPresentThresholdMilliVolts",
            defaults.electricPresentThresholdMilliVolts,
        )
        val high = value.optInt("electricHighThresholdMilliVolts", defaults.electricHighThresholdMilliVolts)
        val critical = value.optInt(
            "electricCriticalThresholdMilliVolts",
            defaults.electricCriticalThresholdMilliVolts,
        )
        val hysteresis = value.optInt(
            "electricHysteresisMilliVolts",
            defaults.electricHysteresisMilliVolts,
        )
        present <= hysteresis || present >= high || high >= critical ||
            hysteresis >= high - present || hysteresis >= critical - high
    }
    invalidateSafetyGroupIf(
        value,
        setOf(
            "electricCalibrationStabilityMilliVolts",
            "electricPresentThresholdMilliVolts",
            "electricHighThresholdMilliVolts",
            "electricCriticalThresholdMilliVolts",
            "electricMinimumMilliVolts",
            "electricMaximumMilliVolts",
        ),
        issues,
    ) {
        val minimum = value.optInt("electricMinimumMilliVolts", defaults.electricMinimumMilliVolts)
        val maximum = value.optInt("electricMaximumMilliVolts", defaults.electricMaximumMilliVolts)
        val range = maximum - minimum
        minimum >= maximum ||
            value.optInt(
                "electricCalibrationStabilityMilliVolts",
                defaults.electricCalibrationStabilityMilliVolts,
            ) > range ||
            value.optInt(
                "electricCriticalThresholdMilliVolts",
                defaults.electricCriticalThresholdMilliVolts,
            ) > range
    }
    invalidateSafetyGroupIf(
        value,
        setOf("heightThresholdMillimetres", "heightHysteresisMillimetres", "heightDriftWindowMillimetres"),
        issues,
    ) {
        val threshold = value.optInt("heightThresholdMillimetres", defaults.heightThresholdMillimetres)
        val hysteresis = value.optInt("heightHysteresisMillimetres", defaults.heightHysteresisMillimetres)
        val driftWindow = value.optInt("heightDriftWindowMillimetres", defaults.heightDriftWindowMillimetres)
        threshold <= hysteresis || driftWindow !in 1 until threshold
    }
    invalidateSafetyGroupIf(
        value,
        setOf(
            "heightCalibrationStabilityMillimetres",
            "heightThresholdMillimetres",
            "heightMinimumMillimetres",
            "heightMaximumMillimetres",
        ),
        issues,
    ) {
        val minimum = value.optInt("heightMinimumMillimetres", defaults.heightMinimumMillimetres)
        val maximum = value.optInt("heightMaximumMillimetres", defaults.heightMaximumMillimetres)
        val range = maximum.toLong() - minimum.toLong()
        minimum >= maximum ||
            value.optLong("heightThresholdMillimetres", defaults.heightThresholdMillimetres.toLong()) > range ||
            value.optLong(
                "heightCalibrationStabilityMillimetres",
                defaults.heightCalibrationStabilityMillimetres.toLong(),
            ) > range
    }
    return runCatching { RuntimeConfigStore.parseSafetyThresholds(value.toString()) }
        .getOrElse { error ->
            issues += RuntimeConfigIssue(
                "safety_thresholds_json",
                RuntimeConfigIssueKind.INVALID_VALUE,
                error.javaClass.name,
            )
            defaults
        }
}

private inline fun invalidateSafetyGroupIf(
    value: JSONObject,
    fields: Set<String>,
    issues: MutableList<RuntimeConfigIssue>,
    invalid: () -> Boolean,
) {
    if (!invalid()) return
    fields.filter(value::has).forEach { field ->
        value.remove(field)
        issues += RuntimeConfigIssue(
            "safety_thresholds_json.$field",
            RuntimeConfigIssueKind.INVALID_VALUE,
        )
    }
}

private val SAFETY_NUMERIC_FIELDS = setOf(
    "version",
    "freeFallThresholdMilliG",
    "freeFallMinimumMillis",
    "fallImpactThresholdMilliG",
    "impactThresholdMilliG",
    "fallImpactWindowMillis",
    "motionCooldownMillis",
    "shakeAccelerationThresholdMilliG",
    "shakeGyroThresholdMilliDegreesPerSecond",
    "shakeDirectionChanges",
    "shakeWindowMillis",
    "inactivityAccelerationToleranceMilliG",
    "inactivityGyroToleranceMilliDegreesPerSecond",
    "inactivityMinimumMillis",
    "electricCalibrationSamples",
    "electricCalibrationStabilityMilliVolts",
    "electricPresentThresholdMilliVolts",
    "electricHighThresholdMilliVolts",
    "electricCriticalThresholdMilliVolts",
    "electricHysteresisMilliVolts",
    "electricConfirmationSamples",
    "electricMinimumMilliVolts",
    "electricMaximumMilliVolts",
    "heightCalibrationSamples",
    "heightCalibrationStabilityMillimetres",
    "heightThresholdMillimetres",
    "heightHysteresisMillimetres",
    "heightConfirmationSamples",
    "heightConfirmationMillis",
    "heightDriftStabilityMillis",
    "heightDriftMaximumRateMillimetresPerSecond",
    "heightDriftWindowMillimetres",
    "heightBaselineAdjustmentDivisor",
    "heightMinimumMillimetres",
    "heightMaximumMillimetres",
)

private val SAFETY_FIELD_RANGES = mapOf(
    "version" to 1L..0xFFFFL,
    "freeFallMinimumMillis" to 20L..5_000,
    "fallImpactWindowMillis" to 20L..10_000,
    "motionCooldownMillis" to 100L..60_000,
    "shakeAccelerationThresholdMilliG" to 1L..100_000,
    "shakeGyroThresholdMilliDegreesPerSecond" to 1L..10_000_000,
    "shakeDirectionChanges" to 2L..20,
    "shakeWindowMillis" to 100L..10_000,
    "inactivityAccelerationToleranceMilliG" to 10L..500,
    "inactivityGyroToleranceMilliDegreesPerSecond" to 100L..50_000,
    "inactivityMinimumMillis" to 1_000L..86_400_000,
    "electricCalibrationSamples" to 3L..10_000,
    "electricCalibrationStabilityMilliVolts" to 0L..5_000,
    "electricPresentThresholdMilliVolts" to 1L..5_000,
    "electricHighThresholdMilliVolts" to 1L..5_000,
    "electricCriticalThresholdMilliVolts" to 1L..5_000,
    "electricHysteresisMilliVolts" to 0L..5_000,
    "electricConfirmationSamples" to 1L..100,
    "electricMinimumMilliVolts" to 0L..0xFFFF,
    "electricMaximumMilliVolts" to 0L..0xFFFF,
    "heightCalibrationSamples" to 3L..10_000,
    "heightCalibrationStabilityMillimetres" to 0L..0xFFFF,
    "heightThresholdMillimetres" to 1L..Int.MAX_VALUE.toLong(),
    "heightHysteresisMillimetres" to 0L..Int.MAX_VALUE.toLong(),
    "heightConfirmationSamples" to 1L..100,
    "heightConfirmationMillis" to 0L..60_000,
    "heightDriftStabilityMillis" to 0L..3_600_000,
    "heightDriftMaximumRateMillimetresPerSecond" to 0L..10_000,
    "heightDriftWindowMillimetres" to 1L..1_000_000,
    "heightBaselineAdjustmentDivisor" to 2L..10_000,
    "heightMinimumMillimetres" to Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(),
    "heightMaximumMillimetres" to Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(),
)
