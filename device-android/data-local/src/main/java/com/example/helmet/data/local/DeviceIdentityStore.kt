package com.example.helmet.data.local

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class DeviceIdentityStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    internal constructor(context: Context, preferencesName: String) : this(
        context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE),
    )

    fun getOrCreateDeviceId(): String = synchronized(PROCESS_LOCK) {
        (runCatching { preferences.all[KEY_DEVICE_ID] }.getOrNull() as? String)
            ?.takeIf(DEVICE_ID_PATTERN::matches)
            ?: UUID.randomUUID().toString().also { generated ->
                check(preferences.edit().putString(KEY_DEVICE_ID, generated).commit()) {
                    "failed to persist stable deviceId"
                }
            }
    }

    companion object {
        internal const val PREFERENCES_NAME = "helmet_identity"
        internal const val KEY_DEVICE_ID = "device_id"
        private val DEVICE_ID_PATTERN = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
        private val PROCESS_LOCK = Any()
    }
}
