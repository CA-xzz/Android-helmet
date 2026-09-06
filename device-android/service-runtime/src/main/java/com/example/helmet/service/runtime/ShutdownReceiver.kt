package com.example.helmet.service.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN) return
        StructuredLogger.info(event = "shutdown_receiver_invoked", fields = mapOf("action" to intent.action))
        runCatching {
            ContextCompat.startForegroundService(context, HelmetService.shutdownIntent(context))
        }.onFailure { error ->
            StructuredLogger.error(event = "shutdown_service_signal_failed", error = error)
        }
    }
}
