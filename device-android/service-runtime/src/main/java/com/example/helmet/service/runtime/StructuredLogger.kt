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
        Log.e(TAG, encode("ERROR", event, fields + ("error" to error.toString())), error)
    }

    private fun encode(level: String, event: String, fields: Map<String, Any?>): String =
        JSONObject(
            linkedMapOf<String, Any?>(
                "timestamp" to wallClock(),
                "level" to level,
                "event" to event,
            ) + fields,
        ).toString()

    private const val TAG = "HelmetRuntime"
}
