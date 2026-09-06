package com.example.helmet.service.runtime

import android.content.Context
import com.example.helmet.data.local.RuntimeConfigStore
import java.security.KeyStore
import org.json.JSONArray
import org.json.JSONObject

internal fun capturePreferenceFilesForRecoveryTest(
    context: Context,
    names: Collection<String>,
): String = JSONObject().also { root ->
    names.forEach { name ->
        val entries = JSONArray()
        context.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
            require(!PLAINTEXT_RECOVERY_SECRET_KEY.containsMatchIn(key)) {
                "refusing to persist a plaintext credential in recovery-test evidence"
            }
            val stored = requireNotNull(value)
            entries.put(
                JSONObject()
                    .put("key", key)
                    .put("type", stored.recoveryTestType())
                    .put("value", stored.toRecoveryTestJson()),
            )
        }
        root.put(name, entries)
    }
    root.put(KEYSTORE_METADATA, captureKeystoreMetadata(names))
}.toString()

internal fun captureRuntimeConfigurationForTest(context: Context): String =
    capturePreferenceFilesForRecoveryTest(context, RUNTIME_CONFIGURATION_PREFERENCE_FILES)

internal fun restoreRuntimeConfigurationForTest(context: Context, serialized: String) {
    restorePreferenceFilesForRecoveryTest(context, serialized)
}

internal fun RuntimeConfigStore.saveForInstrumentationTest(
    config: com.example.helmet.core.model.RuntimeConfig,
) = update { current -> config.copy(revision = current.revision) }

internal fun restorePreferenceFilesForRecoveryTest(context: Context, serialized: String) {
    val root = JSONObject(serialized)
    root.keys().asSequence().filter { it != KEYSTORE_METADATA }.forEach { name ->
        val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val editor = preferences.edit().clear()
        val entries = root.getJSONArray(name)
        repeat(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            val key = entry.getString("key")
            when (entry.getString("type")) {
                "string" -> editor.putString(key, entry.getString("value"))
                "int" -> editor.putInt(key, entry.getInt("value"))
                "long" -> editor.putLong(key, entry.getLong("value"))
                "float" -> editor.putFloat(key, entry.getDouble("value").toFloat())
                "boolean" -> editor.putBoolean(key, entry.getBoolean("value"))
                "string_set" -> editor.putStringSet(
                    key,
                    entry.getJSONArray("value").let { array ->
                        buildSet { repeat(array.length()) { item -> add(array.getString(item)) } }
                    },
                )
                else -> error("unsupported recovery-test preference type")
            }
        }
        check(editor.commit()) { "failed to restore recovery-test preferences" }
    }
    root.optJSONObject(KEYSTORE_METADATA)?.let(::restoreKeystoreMetadata)
}

private fun captureKeystoreMetadata(names: Collection<String>): JSONObject = JSONObject().also { metadata ->
    val keyStore = androidKeyStore()
    CREDENTIAL_KEY_ALIASES.forEach { (preferencesName, alias) ->
        if (preferencesName in names) metadata.put(alias, keyStore.containsAlias(alias))
    }
}

private fun restoreKeystoreMetadata(metadata: JSONObject) {
    val keyStore = androidKeyStore()
    CREDENTIAL_KEY_ALIASES.values.forEach { alias ->
        if (metadata.has(alias) && !metadata.getBoolean(alias) && keyStore.containsAlias(alias)) {
            keyStore.deleteEntry(alias)
            check(!keyStore.containsAlias(alias)) { "failed to remove recovery-test-created credential key" }
        }
    }
}

private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

private fun Any.recoveryTestType(): String = when (this) {
    is String -> "string"
    is Int -> "int"
    is Long -> "long"
    is Float -> "float"
    is Boolean -> "boolean"
    is Set<*> -> "string_set"
    else -> error("unsupported recovery-test preference type: ${javaClass.name}")
}

private fun Any.toRecoveryTestJson(): Any = when (this) {
    is Set<*> -> JSONArray(this)
    else -> this
}

private val PLAINTEXT_RECOVERY_SECRET_KEY = Regex(
    "(?i)(authorization|password|passwd|token|secret|credential|api[_-]?key|cookie)",
)

private val RUNTIME_CONFIGURATION_PREFERENCE_FILES = listOf(
    "helmet_runtime_config",
    "helmet_backend_credentials",
    "helmet_rtk_credentials",
)
private const val KEYSTORE_METADATA = "__helmet_test_keystore_metadata"
private val CREDENTIAL_KEY_ALIASES = mapOf(
    "helmet_backend_credentials" to "helmet_backend_bearer_aes_v1",
    "helmet_rtk_credentials" to "helmet_ntrip_password_aes_v1",
)
