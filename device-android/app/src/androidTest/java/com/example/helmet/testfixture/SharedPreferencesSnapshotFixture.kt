package com.example.helmet.testfixture

import android.content.Context
import android.content.SharedPreferences
import java.security.KeyStore
import org.json.JSONArray
import org.json.JSONObject

fun SharedPreferences.snapshotForTest(): Map<String, Any> = all.mapValues { (_, value) ->
    when (value) {
        is String, is Int, is Long, is Float, is Boolean -> value
        is Set<*> -> value.map { item -> requireNotNull(item as? String) }.toSet()
        else -> error("unsupported SharedPreferences test value: ${value?.javaClass?.name ?: "null"}")
    }
}

fun SharedPreferences.restoreForTest(snapshot: Map<String, Any>) {
    val editor = edit().clear()
    snapshot.forEach { (key, value) -> editor.putTestValue(key, value) }
    check(editor.commit()) { "failed to restore SharedPreferences after instrumentation" }
}

/**
 * Serializes preference records, including encrypted credential ciphertext, for a two-process
 * board recovery test. The result contains no decrypted credential values.
 */
fun capturePreferenceFilesForTest(context: Context, names: Collection<String>): String =
    JSONObject().also { root ->
        names.forEach { name ->
            val entries = JSONArray()
            context.getSharedPreferences(name, Context.MODE_PRIVATE).snapshotForTest()
                .forEach { (key, value) ->
                    require(!PLAINTEXT_SECRET_KEY.containsMatchIn(key)) {
                        "refusing to persist a plaintext credential in instrumentation evidence"
                    }
                    entries.put(
                        JSONObject()
                            .put("key", key)
                            .put("type", value.testType())
                            .put("value", value.toJsonTestValue()),
                    )
                }
            root.put(name, entries)
        }
        root.put(KEYSTORE_METADATA, captureKeystoreMetadata(names))
    }.toString()

fun restorePreferenceFilesForTest(context: Context, serialized: String) {
    val root = JSONObject(serialized)
    root.keys().asSequence().filter { it != KEYSTORE_METADATA }.forEach { name ->
        val entries = root.getJSONArray(name)
        val values = buildMap<String, Any> {
            repeat(entries.length()) { index ->
                val entry = entries.getJSONObject(index)
                put(
                    entry.getString("key"),
                    entry.getTestValue("type", "value"),
                )
            }
        }
        context.getSharedPreferences(name, Context.MODE_PRIVATE).restoreForTest(values)
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
            check(!keyStore.containsAlias(alias)) { "failed to remove instrumentation-created credential key" }
        }
    }
}

private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

class PersistentPreferencesTestGuard private constructor(
    private val context: Context,
    private val evidencePreferencesName: String,
    private val serializedSnapshot: String,
) {
    fun restore() {
        restorePreferenceFilesForTest(context, serializedSnapshot)
        val evidence = context.getSharedPreferences(evidencePreferencesName, Context.MODE_PRIVATE)
        check(evidence.edit().clear().commit()) { "failed to clear connected-test recovery evidence" }
    }

    companion object {
        fun restoreStale(context: Context, evidencePreferencesName: String) {
            val evidence = context.getSharedPreferences(evidencePreferencesName, Context.MODE_PRIVATE)
            evidence.getString(EVIDENCE_SNAPSHOT_KEY, null)?.let { staleSnapshot ->
                restorePreferenceFilesForTest(context, staleSnapshot)
                check(evidence.edit().clear().commit()) {
                    "failed to clear stale connected-test recovery evidence"
                }
            }
        }

        fun capture(
            context: Context,
            evidencePreferencesName: String,
            preferenceNames: Collection<String>,
        ): PersistentPreferencesTestGuard {
            val evidence = context.getSharedPreferences(evidencePreferencesName, Context.MODE_PRIVATE)
            check(!evidence.contains(EVIDENCE_SNAPSHOT_KEY)) {
                "stale connected-test recovery evidence must be restored before capture"
            }
            val snapshot = capturePreferenceFilesForTest(context, preferenceNames)
            check(evidence.edit().putString(EVIDENCE_SNAPSHOT_KEY, snapshot).commit()) {
                "failed to persist connected-test recovery evidence"
            }
            return PersistentPreferencesTestGuard(context, evidencePreferencesName, snapshot)
        }

        private const val EVIDENCE_SNAPSHOT_KEY = "encrypted_preferences_snapshot"
    }
}

private fun SharedPreferences.Editor.putTestValue(key: String, value: Any): SharedPreferences.Editor =
    when (value) {
        is String -> putString(key, value)
        is Int -> putInt(key, value)
        is Long -> putLong(key, value)
        is Float -> putFloat(key, value)
        is Boolean -> putBoolean(key, value)
        is Set<*> -> {
            @Suppress("UNCHECKED_CAST")
            putStringSet(key, (value as Set<String>).toSet())
        }
        else -> error("unsupported SharedPreferences test value: ${value.javaClass.name}")
    }

private fun Any.testType(): String = when (this) {
    is String -> "string"
    is Int -> "int"
    is Long -> "long"
    is Float -> "float"
    is Boolean -> "boolean"
    is Set<*> -> "string_set"
    else -> error("unsupported SharedPreferences test value: ${javaClass.name}")
}

private fun Any.toJsonTestValue(): Any = when (this) {
    is Set<*> -> JSONArray(this)
    else -> this
}

private fun JSONObject.getTestValue(typeKey: String, valueKey: String): Any = when (getString(typeKey)) {
    "string" -> getString(valueKey)
    "int" -> getInt(valueKey)
    "long" -> getLong(valueKey)
    "float" -> getDouble(valueKey).toFloat()
    "boolean" -> getBoolean(valueKey)
    "string_set" -> getJSONArray(valueKey).let { array ->
        buildSet { repeat(array.length()) { index -> add(array.getString(index)) } }
    }
    else -> error("unsupported serialized SharedPreferences test value")
}

private val PLAINTEXT_SECRET_KEY = Regex(
    "(?i)(authorization|password|passwd|token|secret|credential|api[_-]?key|cookie)",
)
private const val KEYSTORE_METADATA = "__helmet_test_keystore_metadata"
private val CREDENTIAL_KEY_ALIASES = mapOf(
    "helmet_backend_credentials" to "helmet_backend_bearer_aes_v1",
    "helmet_rtk_credentials" to "helmet_ntrip_password_aes_v1",
)
