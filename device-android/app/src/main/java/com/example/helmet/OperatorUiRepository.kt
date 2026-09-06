package com.example.helmet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.data.local.SafetyStore
import com.example.helmet.service.runtime.DeviceTimeAuthorityProvider
import com.example.helmet.service.runtime.RuntimeStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers

class OperatorUiRepository(
    context: Context,
    wallClock: (() -> Long)? = null,
) {
    private val appContext = context.applicationContext
    private val wallClock = wallClock ?: DeviceTimeAuthorityProvider.get(appContext)::nowEpochMillis
    private val database = HelmetDatabase.get(appContext)
    private val deviceId = DeviceIdentityStore(appContext).getOrCreateDeviceId()
    private val eventStore = EventStore(database)
    private val safetyStore = SafetyStore(database)
    private val configStore = RuntimeConfigStore(appContext)
    private val config = MutableStateFlow(configStore.load())
    private val boardStatusProbe = H618BoardStatusProbe(appContext)

    val states: Flow<OperatorUiState> = combine(
        RuntimeStatus.snapshot,
        safetyStore.observeRecentAlerts(deviceId, 80),
        safetyStore.observeRecentSamples(deviceId, 200),
        eventStore.observeRecent(200),
        config,
    ) { snapshot, alerts, samples, events, runtimeConfig ->
        OperatorUiInput(
            snapshot = snapshot,
            config = runtimeConfig,
            recentAlerts = alerts,
            recentSamples = samples,
            recentEvents = events,
            capabilities = inspectCapabilities(snapshot, runtimeConfig, wallClock()),
            nowEpochMillis = wallClock(),
        )
    }.combine(clockTicks()) { input, now ->
        OperatorUiMapper.map(
            input.copy(
                capabilities = inspectCapabilities(input.snapshot, input.config, now),
                nowEpochMillis = now,
            ),
        )
    }.distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    fun refresh() {
        config.value = configStore.load()
    }

    fun nowEpochMillis(): Long = wallClock()

    private fun inspectCapabilities(
        snapshot: com.example.helmet.core.model.RuntimeSnapshot,
        runtimeConfig: com.example.helmet.core.model.RuntimeConfig,
        nowEpochMillis: Long,
    ): PlatformCapabilities {
        val packageManager = appContext.packageManager
        val locationManager = appContext.getSystemService(LocationManager::class.java)
        val sensorManager = appContext.getSystemService(SensorManager::class.java)
        val vibratorManager = appContext.getSystemService(VibratorManager::class.java)
        val locationProviders = setOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.FUSED_PROVIDER,
        )
        val locationProviderAvailable = runCatching {
            locationManager.allProviders.any(locationProviders::contains)
        }.getOrDefault(false)
        val accelerometer = runCatching { sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }.getOrNull()
        val gyroscope = runCatching { sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }.getOrNull()
        return PlatformCapabilities(
            microphoneDeclared = packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
            microphonePermissionGranted = hasPermission(Manifest.permission.RECORD_AUDIO),
            cameraPermissionGranted = hasPermission(Manifest.permission.CAMERA),
            locationProviderAvailable = locationProviderAvailable,
            gnssProviderAvailable = runCatching {
                locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)
            }.getOrDefault(false),
            locationPermissionGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
            vibratorAvailable = runCatching { vibratorManager.defaultVibrator.hasVibrator() }.getOrDefault(false),
            systemImuAvailable = accelerometer != null && gyroscope != null,
            boardStatuses = boardStatusProbe.inspect(snapshot, runtimeConfig, nowEpochMillis),
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun clockTicks(): Flow<Long> = flow {
        while (currentCoroutineContext().isActive) {
            emit(wallClock())
            delay(5_000L)
        }
    }
}
