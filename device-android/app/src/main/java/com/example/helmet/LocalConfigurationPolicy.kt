package com.example.helmet

import android.Manifest
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.data.local.RuntimeConfigStore
import java.net.URI
import java.util.Locale
import org.json.JSONObject

internal data class RuntimeConfigurationSanitization(
    val config: RuntimeConfig,
    val changedFields: Set<String>,
    val persisted: Boolean,
)

internal object LocalConfigurationPolicy {
    private val productionSerialPath = Regex("^/dev/tty(?:AS|S|USB|ACM)[0-9]{1,3}$")
    private val certificateAlias = Regex("^[A-Za-z0-9._:-]{1,256}$")
    private val supportedBaudRates = setOf(9_600, 19_200, 38_400, 57_600, 115_200, 230_400, 460_800, 921_600)

    fun simulatorControlsVisible(productionBuild: Boolean): Boolean = !productionBuild

    fun effectiveSimulatorEnabled(productionBuild: Boolean, requested: Boolean): Boolean =
        requested && !productionBuild

    fun requiredRuntimePermissions(
        cameraAvailable: Boolean,
        locationProviderAvailable: Boolean,
        bluetoothAvailable: Boolean,
    ): Set<String> = buildSet {
        // Voice messages and calls require microphone permission even when no camera is present.
        add(Manifest.permission.RECORD_AUDIO)
        if (cameraAvailable) add(Manifest.permission.CAMERA)
        if (locationProviderAvailable) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (bluetoothAvailable) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * Runs synchronously from Application.onCreate before this process starts the runtime service.
     * A failed write is intentionally propagated so a release process cannot use rejected state.
     */
    fun sanitizeAndPersistRuntimeConfiguration(
        productionBuild: Boolean,
        load: () -> RuntimeConfig,
        save: (RuntimeConfig) -> Unit,
    ): RuntimeConfigurationSanitization {
        val current = load()
        if (!productionBuild) {
            return RuntimeConfigurationSanitization(current, emptySet(), persisted = false)
        }
        val sanitized = sanitizeReleaseRuntimeConfiguration(current)
        if (sanitized.changedFields.isEmpty()) return sanitized
        save(sanitized.config)
        return sanitized.copy(persisted = true)
    }

    fun sanitizeReleaseRuntimeConfiguration(current: RuntimeConfig): RuntimeConfigurationSanitization {
        val changed = linkedSetOf<String>()
        var candidate = current

        if (candidate.simulatorEnabled) {
            candidate = candidate.copy(simulatorEnabled = false)
            changed += "simulatorEnabled"
        }

        val backend = runCatching {
            val url = normalizeBackendBaseUrl(candidate.backendBaseUrl, productionBuild = true)
            val token = validateBearerToken(candidate.backendBearerToken)
            url to token
        }.getOrNull()
        if (backend == null) {
            if (candidate.backendBaseUrl.isNotEmpty()) changed += "backendBaseUrl"
            if (candidate.backendBearerToken.isNotEmpty()) changed += "backendBearerToken"
            candidate = candidate.copy(backendBaseUrl = "", backendBearerToken = "")
        } else if (candidate.backendBaseUrl != backend.first) {
            candidate = candidate.copy(backendBaseUrl = backend.first)
            changed += "backendBaseUrl"
        }

        val rtk = sanitizeReleaseRtk(candidate.rtk)
        if (rtk != candidate.rtk) {
            if (rtk.enabled != candidate.rtk.enabled) changed += "rtk.enabled"
            if (rtk.ntripUrl != candidate.rtk.ntripUrl) changed += "rtk.ntripUrl"
            if (rtk.username != candidate.rtk.username) changed += "rtk.username"
            if (rtk.password != candidate.rtk.password) changed += "rtk.password"
            if (rtk.transportMode != candidate.rtk.transportMode) changed += "rtk.transportMode"
            if (rtk.directDevicePath != candidate.rtk.directDevicePath) changed += "rtk.directDevicePath"
            if (rtk.directBaudRate != candidate.rtk.directBaudRate) changed += "rtk.directBaudRate"
            candidate = candidate.copy(rtk = rtk)
        }

        val normalizedMqtt = runCatching {
            normalizeMqttConfiguration(
                brokerUri = candidate.mqttBrokerUri,
                clientCertificateAlias = candidate.mqttClientCertificateAlias,
            ).also { normalized ->
                if (normalized.first.isNotBlank()) requireHttpBackendForMqtt(candidate)
            }
        }.getOrNull()
        if (normalizedMqtt == null) {
            if (candidate.mqttBrokerUri.isNotEmpty()) changed += "mqttBrokerUri"
            if (candidate.mqttClientCertificateAlias.isNotEmpty()) changed += "mqttClientCertificateAlias"
            candidate = candidate.copy(mqttBrokerUri = "", mqttClientCertificateAlias = "")
        } else {
            if (candidate.mqttBrokerUri != normalizedMqtt.first) changed += "mqttBrokerUri"
            if (candidate.mqttClientCertificateAlias != normalizedMqtt.second) {
                changed += "mqttClientCertificateAlias"
            }
            candidate = candidate.copy(
                mqttBrokerUri = normalizedMqtt.first,
                mqttClientCertificateAlias = normalizedMqtt.second,
            )
        }

        val safePath = runCatching {
            normalizeHardwareDevicePath(candidate.hardwareDevicePath, productionBuild = true)
        }.getOrDefault(RuntimeConfig().hardwareDevicePath)
        if (candidate.hardwareDevicePath != safePath) {
            candidate = candidate.copy(hardwareDevicePath = safePath)
            changed += "hardwareDevicePath"
        }
        val safeBaud = RuntimeConfig().hardwareBaudRate
        if (candidate.hardwareBaudRate != safeBaud) {
            candidate = candidate.copy(hardwareBaudRate = safeBaud)
            changed += "hardwareBaudRate"
        }

        if (changed.isNotEmpty()) {
            candidate = candidate.copy(revision = nextRevision(current.revision))
        }
        return RuntimeConfigurationSanitization(candidate, changed, persisted = false)
    }

    fun normalizeBackendBaseUrl(raw: String, productionBuild: Boolean): String {
        val uri = parseUri(raw.trim().trimEnd('/'), "backend base URL")
        require(uri.rawPath.isNullOrEmpty()) { "backend base URL must not contain a path" }
        requireNoEmbeddedComponents(uri, "backend base URL")
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = requiredHost(uri, "backend base URL")
        require(scheme == "https" || (!productionBuild && scheme == "http" && isExactLoopback(host))) {
            "backend base URL must use HTTPS; debug builds may use HTTP only for exact loopback"
        }
        return rootUri(scheme, host, checkedPort(uri, "backend base URL"))
    }

    fun normalizeNtripUrl(raw: String, productionBuild: Boolean): String {
        val uri = parseUri(raw.trim(), "NTRIP URL")
        requireNoEmbeddedComponents(uri, "NTRIP URL")
        val scheme = uri.scheme?.lowercase(Locale.US)
        val host = requiredHost(uri, "NTRIP URL")
        require(scheme == "https" || (!productionBuild && scheme == "http" && isExactLoopback(host))) {
            "NTRIP URL must use HTTPS; debug builds may use HTTP only for exact loopback"
        }
        val mountPoint = uri.rawPath.orEmpty()
        // NTRIP necessarily addresses a mount point. Keep it as one explicit path segment and
        // reject all other URL components so credentials cannot be hidden in the endpoint.
        require(mountPoint.matches(Regex("^/[A-Za-z0-9._~-]{1,128}$"))) {
            "NTRIP URL must contain one mount-point path segment"
        }
        return rootUri(scheme, host, checkedPort(uri, "NTRIP URL")) + mountPoint
    }

    fun validateNtripUsername(raw: String): String = raw.trim().also { username ->
        require(username.length in 1..256 && ':' !in username && !username.hasLineBreak()) {
            "NTRIP username is invalid"
        }
    }

    fun validateNtripPassword(raw: String): String = raw.also { password ->
        require(password.length in 1..512 && !password.hasLineBreak()) { "NTRIP password is invalid" }
    }

    fun normalizeMqttConfiguration(
        brokerUri: String,
        clientCertificateAlias: String,
    ): Pair<String, String> {
        if (brokerUri.isBlank() && clientCertificateAlias.isBlank()) return "" to ""
        val uri = parseUri(brokerUri.trim(), "MQTT broker URI")
        requireNoEmbeddedComponents(uri, "MQTT broker URI")
        require(uri.rawPath.isNullOrEmpty()) { "MQTT broker URI must not contain a path" }
        require(uri.scheme?.lowercase(Locale.US) == "ssl") { "MQTT broker URI must use ssl://" }
        val host = requiredHost(uri, "MQTT broker URI")
        require(uri.port == 8_883) { "MQTT broker URI must use port 8883" }
        val alias = clientCertificateAlias.trim()
        require(certificateAlias.matches(alias)) { "MQTT client certificate alias is invalid" }
        return "ssl://${authorityHost(host)}:8883" to alias
    }

    fun requireHttpBackendForMqtt(config: RuntimeConfig) {
        require(config.backendBaseUrl.isNotBlank() && config.backendBearerToken.isNotBlank()) {
            "HTTP backend URL and bearer token are required for MQTT command wake"
        }
    }

    fun clearBackendAndMqttConfiguration(config: RuntimeConfig): RuntimeConfig = config.copy(
        backendBaseUrl = "",
        backendBearerToken = "",
        mqttBrokerUri = "",
        mqttClientCertificateAlias = "",
    )

    fun normalizeHardwareDevicePath(raw: String, productionBuild: Boolean): String = raw.trim().also { path ->
        require(
            if (productionBuild) {
                path == RuntimeConfig().hardwareDevicePath
            } else {
                productionSerialPath.matches(path) || VariantSerialDevicePathPolicy.acceptsAdditionalPath(path)
            },
        ) {
            "hardware path must be an approved serial device"
        }
    }

    fun validateHardwareBaudRate(value: Int): Int = value.also { baud ->
        require(baud in supportedBaudRates) { "unsupported hardware baud rate" }
    }

    fun parseCompleteSafetyThresholds(raw: String): SafetyThresholdConfig {
        require(raw.length in 2..32_768) { "safety threshold JSON is invalid" }
        val value = runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalArgumentException("safety threshold JSON is invalid", it) }
        val expectedKeys = JSONObject(RuntimeConfigStore.safetyThresholdsToJson(SafetyThresholdConfig()))
            .keys()
            .asSequence()
            .toSet()
        val actualKeys = value.keys().asSequence().toSet()
        require(actualKeys == expectedKeys) {
            val missing = (expectedKeys - actualKeys).sorted().joinToString(",")
            val unknown = (actualKeys - expectedKeys).sorted().joinToString(",")
            "safety threshold JSON fields do not match; missing=[$missing], unknown=[$unknown]"
        }
        actualKeys.forEach { field ->
            val number = value.opt(field) as? Number
                ?: throw IllegalArgumentException("safety threshold field $field must be an integer")
            require(number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble()) {
                "safety threshold field $field must be an integer"
            }
        }
        return runCatching { RuntimeConfigStore.parseSafetyThresholds(value.toString()) }
            .getOrElse { throw IllegalArgumentException("safety threshold values are invalid", it) }
    }

    fun validateBearerToken(raw: String): String = raw.also { token ->
        require(token.length in 1..4_096 && !token.hasLineBreak()) { "backend bearer token is invalid" }
    }

    private fun sanitizeReleaseRtk(current: RtkRuntimeConfig): RtkRuntimeConfig {
        if (!current.enabled) {
            return current.copy(enabled = false, ntripUrl = "", username = "", password = "")
        }
        return runCatching {
            current.copy(
                enabled = true,
                ntripUrl = normalizeNtripUrl(current.ntripUrl, productionBuild = true),
                username = validateNtripUsername(current.username),
                password = validateNtripPassword(current.password),
            )
        }.getOrDefault(
            current.copy(enabled = false, ntripUrl = "", username = "", password = ""),
        )
    }

    private fun parseUri(raw: String, label: String): URI {
        require(raw.length in 1..2_048 && !raw.hasLineBreak()) { "invalid $label" }
        return runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("invalid $label", it) }
    }

    private fun requireNoEmbeddedComponents(uri: URI, label: String) {
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "$label must not contain credentials, query, or fragment"
        }
    }

    private fun requiredHost(uri: URI, label: String): String =
        uri.host?.lowercase(Locale.US)?.removeSurrounding("[", "]")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("$label host is missing")

    private fun checkedPort(uri: URI, label: String): Int = uri.port.also { port ->
        require(port == -1 || port in 1..65_535) { "$label port is invalid" }
    }

    private fun rootUri(scheme: String?, host: String, port: Int): String = buildString {
        append(requireNotNull(scheme))
        append("://")
        append(authorityHost(host))
        if (port >= 0) append(":$port")
    }

    private fun authorityHost(host: String): String = if (':' in host) "[$host]" else host

    private fun isExactLoopback(host: String): Boolean =
        host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "0:0:0:0:0:0:0:1"

    private fun String.hasLineBreak(): Boolean = '\r' in this || '\n' in this

    fun nextRevision(revision: Long): Long {
        check(revision in 1 until Long.MAX_VALUE) {
            "runtime configuration revision is exhausted"
        }
        return revision + 1
    }
}
