package com.example.helmet.service.runtime

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

internal object BatteryStatusReader {
    fun read(context: Context): DeviceBatteryStatus {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return DeviceBatteryStatus(false, null, null)
        val present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        if (!present) return DeviceBatteryStatus(false, null, null)
        val manager = context.getSystemService(BatteryManager::class.java)
        val propertyPercent = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?.takeIf { it in 0..100 }
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val broadcastPercent = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else null
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it in 1..100_000 }
        return DeviceBatteryStatus(true, propertyPercent ?: broadcastPercent, voltage)
    }
}
