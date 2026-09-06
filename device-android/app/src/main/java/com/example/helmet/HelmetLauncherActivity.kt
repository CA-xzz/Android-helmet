package com.example.helmet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.example.helmet.service.runtime.HelmetService

/**
 * Exported entry point for launcher and managed-device startup.
 *
 * It accepts no configuration or commands. It starts the runtime and opens the app-private device
 * status center. The latter is not exported in production, so provisioning does not rely on an
 * ADB-addressable diagnostics component or intent extras.
 */
class HelmetLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, HelmetService.startIntent(this))
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
