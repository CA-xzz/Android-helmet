package com.example.helmet

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.core.content.ContextCompat
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.service.runtime.CrashCapture
import com.example.helmet.service.runtime.DeviceTimeAuthorityProvider
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.StructuredLogger

class HelmetApplication : Application() {
    @Volatile
    private var startupSanitization: RuntimeConfigurationSanitization? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // attachBaseContext runs before manifest ContentProviders. Persist rejected release state
        // here so AndroidX Startup and WorkManager cannot observe stale debug configuration.
        if (ApplicationProcessPolicy.shouldInitialize(Application.getProcessName(), packageName)) {
            sanitizeRuntimeConfigurationBeforeComponents(StartupApplicationContext(base))
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (!ApplicationProcessPolicy.shouldInitialize(Application.getProcessName(), packageName)) return
        val configurationSanitization = sanitizeRuntimeConfigurationBeforeComponents()
        val timeAuthority = DeviceTimeAuthorityProvider.get(this)
        StructuredLogger.installClock(timeAuthority::nowEpochMillis)
        StructuredLogger.info(event = "application_started")
        if (configurationSanitization.persisted) {
            StructuredLogger.info(
                event = "runtime_configuration_sanitized",
                fields = mapOf(
                    "fields" to configurationSanitization.changedFields.sorted().joinToString(","),
                    "revision" to configurationSanitization.config.revision,
                ),
            )
        }
        CrashCapture.install(EventStore(HelmetDatabase.get(this), timeAuthority::nowEpochMillis))
        ApplicationProcessPolicy.attemptRuntimeServiceStart {
            ContextCompat.startForegroundService(this, HelmetService.startIntent(this))
        }?.let { error ->
            // WorkManager and other background components may create the process while Android 12
            // forbids starting a foreground service. Keep that work alive; the sticky service,
            // boot receiver, launcher, or board watchdog will request runtime recovery separately.
            StructuredLogger.warn(
                event = "application_runtime_start_deferred",
                fields = mapOf("errorType" to error.javaClass.name),
            )
        }
    }

    @Synchronized
    private fun sanitizeRuntimeConfigurationBeforeComponents(
        context: Context = this,
    ): RuntimeConfigurationSanitization {
        startupSanitization?.let { return it }
        val runtimeConfigStore = RuntimeConfigStore(context)
        val result = runCatching {
            LocalConfigurationPolicy.sanitizeAndPersistRuntimeConfiguration(
                productionBuild = BuildConfig.PRODUCTION_BUILD,
                load = runtimeConfigStore::load,
                save = runtimeConfigStore::save,
            )
        }.getOrElse { error ->
            // A release process must fail closed if rejected debug state cannot be removed. The
            // error deliberately excludes configuration values and credentials.
            throw IllegalStateException("release runtime configuration sanitization failed", error)
        }
        startupSanitization = result
        return result
    }
}

/**
 * LoadedApk has not assigned Application as the process application while attachBaseContext runs,
 * so Context.getApplicationContext may still be null. RuntimeConfigStore intentionally retains an
 * application context; this wrapper supplies the already process-scoped base context at that stage.
 */
private class StartupApplicationContext(base: Context) : ContextWrapper(base) {
    override fun getApplicationContext(): Context = this
}
