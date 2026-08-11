package com.example.helmet

import android.app.Application
import androidx.core.content.ContextCompat
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.service.runtime.CrashCapture
import com.example.helmet.service.runtime.DeviceTimeAuthorityProvider
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.StructuredLogger

class HelmetApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (!ApplicationProcessPolicy.shouldInitialize(Application.getProcessName(), packageName)) return
        val timeAuthority = DeviceTimeAuthorityProvider.get(this)
        StructuredLogger.installClock(timeAuthority::nowEpochMillis)
        StructuredLogger.info(event = "application_started")
        CrashCapture.install(EventStore(HelmetDatabase.get(this), timeAuthority::nowEpochMillis))
        ContextCompat.startForegroundService(this, HelmetService.startIntent(this))
    }
}
