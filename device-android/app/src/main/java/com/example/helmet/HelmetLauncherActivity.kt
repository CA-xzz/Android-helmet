package com.example.helmet

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import com.example.helmet.service.runtime.HelmetService

/**
 * Exported entry point for launcher and managed-device startup.
 *
 * It accepts no configuration or commands. Production builds only ensure that the runtime service
 * is started; debug builds continue to the internal diagnostics activity.
 */
class HelmetLauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ContextCompat.startForegroundService(this, HelmetService.startIntent(this))
        if (!BuildConfig.PRODUCTION_BUILD) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }
}
