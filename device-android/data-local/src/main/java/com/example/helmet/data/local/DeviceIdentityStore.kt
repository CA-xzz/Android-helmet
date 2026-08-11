package com.example.helmet.data.local

import android.content.Context
import java.util.UUID

class DeviceIdentityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun getOrCreateDeviceId(): String = synchronized(preferences) {
        preferences.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().also { generated ->
                check(preferences.edit().putString(KEY_DEVICE_ID, generated).commit()) {
                    "failed to persist stable deviceId"
                }
            }
    }

    companion object {
        internal const val PREFERENCES_NAME = "helmet_identity"
        private const val KEY_DEVICE_ID = "device_id"
    }
}
