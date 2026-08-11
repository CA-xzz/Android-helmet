package com.example.helmet.service.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        StructuredLogger.info(
            event = "boot_receiver_invoked",
            fields = mapOf("action" to intent.action),
        )
        runCatching {
            ContextCompat.startForegroundService(
                context,
                HelmetService.recoveryIntent(context, source = "boot_completed"),
            )
        }.onFailure { error ->
            StructuredLogger.error(
                event = "boot_service_start_failed",
                error = error,
                fields = mapOf("action" to intent.action),
            )
        }
    }
}
