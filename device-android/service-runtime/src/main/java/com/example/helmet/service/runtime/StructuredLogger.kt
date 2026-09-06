package com.example.helmet.service.runtime

import android.util.Log
import org.json.JSONObject

object StructuredLogger {
    @Volatile
    private var wallClock: () -> Long = System::currentTimeMillis

    fun installClock(clock: () -> Long) {
        wallClock = clock
    }

    fun info(event: String, fields: Map<String, Any?> = emptyMap()) {
        Log.i(TAG, encode("INFO", event, fields))
    }

    fun warn(event: String, fields: Map<String, Any?> = emptyMap()) {
        Log.w(TAG, encode("WARN", event, fields))
    }

    fun error(event: String, error: Throwable, fields: Map<String, Any?> = emptyMap()) {
        // Throwable messages can contain URLs, credentials, media paths, or location payloads.
        // Keep diagnostic types and bounded call sites without copying exception text to logcat.
        Log.e(TAG, encode("ERROR", event, fields + sanitizedErrorFields(error)))
    }

    internal fun sanitizedErrorFields(error: Throwable): Map<String, Any?> {
        val causes = generateSequence(error.cause) { it.cause }
            .take(MAX_CAUSE_DEPTH)
            .map { it.javaClass.name }
            .toList()
        return linkedMapOf(
            "errorType" to error.javaClass.name,
            "causeTypes" to causes,
            "stack" to error.stackTrace.take(MAX_STACK_FRAMES).map(StackTraceElement::toString),
        )
    }

    internal fun sanitizedFields(fields: Map<String, Any?>): Map<String, Any?> =
        fields.entries.associate { (key, value) ->
            key.take(MAX_FIELD_NAME_LENGTH) to sanitizeValue(key, value)
        }

    private fun sanitizeValue(key: String, value: Any?): Any? {
        if (SENSITIVE_FIELD_NAME.containsMatchIn(key)) return REDACTED
        return when (value) {
            null -> null
            is Boolean, is Number -> value
            is Throwable -> value.javaClass.name
            is String -> sanitizeString(value)
            is Map<*, *> -> sanitizedFields(
                value.entries.associate { (nestedKey, nestedValue) ->
                    nestedKey.toString() to nestedValue
                },
            )
            is Iterable<*> -> value.take(MAX_COLLECTION_ITEMS).map { item -> sanitizeValue(key, item) }
            is Array<*> -> value.take(MAX_COLLECTION_ITEMS).map { item -> sanitizeValue(key, item) }
            else -> value.javaClass.name
        }
    }

    private fun sanitizeString(value: String): String {
        val trimmed = value.take(MAX_STRING_LENGTH)
        return if (
            trimmed.startsWith('/') ||
            ABSOLUTE_PATH.containsMatchIn(trimmed) ||
            URI_LIKE.containsMatchIn(trimmed) ||
            SENSITIVE_VALUE.containsMatchIn(trimmed)
        ) {
            REDACTED
        } else {
            trimmed.replace(CONTROL_CHARACTER, "?")
        }
    }

    private fun encode(level: String, event: String, fields: Map<String, Any?>): String =
        JSONObject(
            linkedMapOf<String, Any?>(
                "timestamp" to wallClock(),
                "level" to level,
                "event" to event.take(MAX_EVENT_NAME_LENGTH).takeIf(SAFE_EVENT_NAME::matches)
                    .orEmpty()
                    .ifBlank { "invalid_event" },
            ) + sanitizedFields(fields).filterKeys { key -> key !in RESERVED_FIELD_NAMES },
        ).toString()

    private const val TAG = "HelmetRuntime"
    private const val MAX_CAUSE_DEPTH = 8
    private const val MAX_STACK_FRAMES = 32
    private const val MAX_FIELD_NAME_LENGTH = 64
    private const val MAX_EVENT_NAME_LENGTH = 128
    private const val MAX_STRING_LENGTH = 512
    private const val MAX_COLLECTION_ITEMS = 64
    private const val REDACTED = "[REDACTED]"
    private val SENSITIVE_FIELD_NAME = Regex(
        "(?i)(authorization|password|passwd|token|secret|credential|api[_-]?key|cookie|file[_-]?path|media[_-]?path|latitude|longitude|coordinates|(^|[_-])(lat|lon)($|[_-]))",
    )
    private val SENSITIVE_VALUE = Regex(
        "(?i)(bearer\\s+\\S+|authorization\\s*[:=]|password\\s*[:=]|passwd\\s*[:=]|token\\s*[:=]|secret\\s*[:=]|api[_-]?key\\s*[:=])",
    )
    private val URI_LIKE = Regex("[A-Za-z][A-Za-z0-9+.-]*://")
    private val ABSOLUTE_PATH = Regex("(^|\\s)/[^\\s]+")
    private val CONTROL_CHARACTER = Regex("[\\u0000-\\u001F\\u007F]")
    private val SAFE_EVENT_NAME = Regex("[A-Za-z0-9_.:-]{1,128}")
    private val RESERVED_FIELD_NAMES = setOf("timestamp", "level", "event")
}
