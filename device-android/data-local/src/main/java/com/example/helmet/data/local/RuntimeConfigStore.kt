package com.example.helmet.data.local

import android.content.Context
import com.example.helmet.core.model.CircleGeofence
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.LocalIntercomRuntimeConfig
import com.example.helmet.core.model.SafetyThresholdConfig
import org.json.JSONArray
import org.json.JSONObject

class RuntimeConfigStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "helmet_runtime_config",
        Context.MODE_PRIVATE,
    )
    private val credentialStore = BackendCredentialStore(context.applicationContext)
    private val rtkCredentialStore = RtkCredentialStore(context.applicationContext)

    fun load(): RuntimeConfig = RuntimeConfig(
        revision = preferences.getLong("revision", 1),
        simulatorEnabled = preferences.getBoolean("simulator_enabled", true),
        hardwareDevicePath = preferences.getString("hardware_device_path", "/dev/ttyAS2")
            .orEmpty()
            .ifBlank { "/dev/ttyAS2" },
        hardwareBaudRate = preferences.getInt("hardware_baud_rate", 115_200),
        personId = preferences.getString("person_id", null),
        backendBaseUrl = preferences.getString("backend_base_url", "").orEmpty(),
        backendBearerToken = loadBackendBearerToken(),
        mqttBrokerUri = preferences.getString("mqtt_broker_uri", "").orEmpty(),
        mqttClientCertificateAlias = preferences.getString("mqtt_client_certificate_alias", "").orEmpty(),
        rtk = RtkRuntimeConfig(
            enabled = preferences.getBoolean("rtk_enabled", false),
            ntripUrl = preferences.getString("ntrip_url", "").orEmpty(),
            username = preferences.getString("ntrip_username", "").orEmpty(),
            password = rtkCredentialStore.readPassword(),
        ),
        localIntercom = LocalIntercomRuntimeConfig(
            enabled = preferences.getBoolean("local_intercom_enabled", false),
            fallbackWhenInternetUnavailable = preferences.getBoolean(
                "local_intercom_fallback_when_offline",
                true,
            ),
            groupId = preferences.getInt("local_intercom_group_id", 1),
            channel = preferences.getInt("local_intercom_channel", 1),
            keySlot = preferences.getInt("local_intercom_key_slot", 1),
        ),
        geofences = parseGeofences(preferences.getString("geofences_json", "[]").orEmpty()),
        safetyThresholds = parseSafetyThresholds(
            preferences.getString("safety_thresholds_json", "{}").orEmpty(),
        ),
    )

    fun save(config: RuntimeConfig) {
        credentialStore.write(config.backendBearerToken)
        rtkCredentialStore.writePassword(config.rtk.password)
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
            .putString("geofences_json", geofencesToJson(config.geofences))
            .putString("safety_thresholds_json", safetyThresholdsToJson(config.safetyThresholds))
        check(
            editor.commit(),
        ) { "failed to persist runtime configuration" }
    }

    private fun loadBackendBearerToken(): String {
        val encrypted = credentialStore.read()
        val legacy = preferences.getString(BackendCredentialStore.LEGACY_TOKEN_KEY, "").orEmpty()
        if (legacy.isBlank()) return encrypted
        if (encrypted.isBlank()) credentialStore.write(legacy)
        check(preferences.edit().remove(BackendCredentialStore.LEGACY_TOKEN_KEY).commit()) {
            "failed to remove plaintext backend credential"
        }
        return if (encrypted.isBlank()) legacy else encrypted
    }

    companion object {
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
            .put("heightMinimumMillimetres", config.heightMinimumMillimetres)
            .put("heightMaximumMillimetres", config.heightMaximumMillimetres)
            .toString()
    }
}
