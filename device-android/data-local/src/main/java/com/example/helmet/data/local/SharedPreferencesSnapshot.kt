package com.example.helmet.data.local

import android.content.SharedPreferences

internal typealias SharedPreferencesSnapshot = Map<String, Any>

internal fun SharedPreferences.snapshot(): SharedPreferencesSnapshot = all.mapValues { (_, value) ->
    when (value) {
        is String, is Int, is Long, is Float, is Boolean -> value
        is Set<*> -> value.map { item -> requireNotNull(item as? String) }.toSet()
        else -> error("unsupported SharedPreferences value type: ${value?.javaClass?.name ?: "null"}")
    }
}

internal fun SharedPreferences.restore(snapshot: SharedPreferencesSnapshot) {
    val editor = edit().clear()
    snapshot.forEach { (key, value) ->
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Set<*> -> {
                @Suppress("UNCHECKED_CAST")
                editor.putStringSet(key, (value as Set<String>).toSet())
            }
            else -> error("unsupported SharedPreferences snapshot value type: ${value.javaClass.name}")
        }
    }
    check(editor.commit()) { "failed to restore SharedPreferences snapshot" }
}

internal fun runAllRollbackActions(vararg actions: () -> Unit) {
    var firstFailure: Throwable? = null
    actions.forEach { action ->
        runCatching(action).exceptionOrNull()?.let { failure ->
            if (firstFailure == null) {
                firstFailure = failure
            } else {
                firstFailure?.addSuppressed(failure)
            }
        }
    }
    firstFailure?.let { throw it }
}
