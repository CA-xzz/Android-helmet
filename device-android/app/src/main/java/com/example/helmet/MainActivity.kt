package com.example.helmet

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.helmet.data.local.DeviceIdentityStore
import com.example.helmet.data.local.EventStore
import com.example.helmet.data.local.HelmetDatabase
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.MediaUploadWorker
import com.example.helmet.service.runtime.TrackUploadWorker
import com.example.helmet.service.runtime.CommunicationWorker
import com.example.helmet.service.runtime.SafetyAlertWorker
import com.example.helmet.service.runtime.RuntimeStatus
import com.example.helmet.service.runtime.NtripEndpoint
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var statusView: TextView
    private lateinit var eventsView: TextView
    private lateinit var eventStore: EventStore
    private lateinit var backendUrlInput: EditText
    private lateinit var backendTokenInput: EditText
    private lateinit var ntripUrlInput: EditText
    private lateinit var ntripUsernameInput: EditText
    private lateinit var ntripPasswordInput: EditText
    private lateinit var intercomGroupInput: EditText
    private lateinit var intercomChannelInput: EditText
    private lateinit var intercomKeySlotInput: EditText
    private lateinit var geofenceInput: EditText
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { granted -> granted }) restartRuntimeService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRequiredHardwarePermissions()
        eventStore = EventStore(HelmetDatabase.get(this))
        setContentView(buildContent())
        observeRuntime()
    }

    private fun buildContent(): ScrollView {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(240, 244, 248))
        }

        root.addView(TextView(this).apply {
            text = "Smart Helmet Diagnostics"
            textSize = 26f
            setTextColor(Color.rgb(16, 42, 67))
        })
        root.addView(TextView(this).apply {
            text = "deviceId: ${DeviceIdentityStore(this@MainActivity).getOrCreateDeviceId()}"
            textSize = 14f
            setPadding(0, padding / 2, 0, padding)
        })

        statusView = TextView(this).apply {
            textSize = 17f
            setTextColor(Color.DKGRAY)
        }
        root.addView(statusView, matchWidthWrapHeight())

        root.addView(actionButton("Start HelmetService") {
            ContextCompat.startForegroundService(this, HelmetService.startIntent(this))
        })
        root.addView(actionButton("Use simulated hardware") { switchHardwareMode(simulatorEnabled = true) })
        root.addView(actionButton("Use UART hardware") { switchHardwareMode(simulatorEnabled = false) })
        val runtimeConfig = RuntimeConfigStore(this).load()
        backendUrlInput = EditText(this).apply {
            hint = "HTTPS media backend base URL"
            setText(runtimeConfig.backendBaseUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(backendUrlInput, matchWidthWrapHeight())
        backendTokenInput = EditText(this).apply {
            hint = if (runtimeConfig.backendBearerToken.isBlank()) {
                "Media bearer token"
            } else {
                "Media bearer token unchanged when blank"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(backendTokenInput, matchWidthWrapHeight())
        root.addView(actionButton("Save media upload configuration") { saveBackendConfiguration(clear = false) })
        root.addView(actionButton("Clear media upload configuration") { saveBackendConfiguration(clear = true) })
        ntripUrlInput = EditText(this).apply {
            hint = "HTTPS NTRIP mount-point URL"
            setText(runtimeConfig.rtk.ntripUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        root.addView(ntripUrlInput, matchWidthWrapHeight())
        ntripUsernameInput = EditText(this).apply {
            hint = "NTRIP username"
            setText(runtimeConfig.rtk.username)
            inputType = InputType.TYPE_CLASS_TEXT
        }
        root.addView(ntripUsernameInput, matchWidthWrapHeight())
        ntripPasswordInput = EditText(this).apply {
            hint = if (runtimeConfig.rtk.password.isBlank()) {
                "NTRIP password"
            } else {
                "NTRIP password unchanged when blank"
            }
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(ntripPasswordInput, matchWidthWrapHeight())
        root.addView(actionButton("Enable RTK correction") { saveRtkConfiguration(enabled = true) })
        root.addView(actionButton("Disable and clear RTK correction") { saveRtkConfiguration(enabled = false) })
        intercomGroupInput = EditText(this).apply {
            hint = "Local intercom group ID"
            setText(runtimeConfig.localIntercom.groupId.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(intercomGroupInput, matchWidthWrapHeight())
        intercomChannelInput = EditText(this).apply {
            hint = "Local intercom channel"
            setText(runtimeConfig.localIntercom.channel.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(intercomChannelInput, matchWidthWrapHeight())
        intercomKeySlotInput = EditText(this).apply {
            hint = "Provisioned module key slot"
            setText(runtimeConfig.localIntercom.keySlot.toString())
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        root.addView(intercomKeySlotInput, matchWidthWrapHeight())
        root.addView(actionButton("Enable offline local intercom fallback") {
            saveLocalIntercomConfiguration(enabled = true)
        })
        root.addView(actionButton("Disable local intercom fallback") {
            saveLocalIntercomConfiguration(enabled = false)
        })
        geofenceInput = EditText(this).apply {
            hint = "Geofence JSON array"
            setText(RuntimeConfigStore.geofencesToJson(runtimeConfig.geofences))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(geofenceInput, matchWidthWrapHeight())
        root.addView(actionButton("Save geofence configuration") { saveGeofenceConfiguration() })
        root.addView(actionButton("Simulate photo key") { simulate(SimulatedInput.PHOTO_SHORT) })
        root.addView(actionButton("Simulate record key") { simulate(SimulatedInput.RECORD_LONG) })
        root.addView(actionButton("Simulate call key") { simulate(SimulatedInput.CALL) })
        root.addView(actionButton("Simulate fall alarm") { simulate(SimulatedInput.FALL) })
        root.addView(actionButton("Simulate near-electric alarm") { simulate(SimulatedInput.NEAR_ELECTRIC) })
        root.addView(actionButton("Simulate height alarm") { simulate(SimulatedInput.HEIGHT_LIMIT) })
        root.addView(actionButton("Simulate volume up") { simulate(SimulatedInput.VOLUME_UP) })
        root.addView(actionButton("Simulate volume down") { simulate(SimulatedInput.VOLUME_DOWN) })

        eventsView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.rgb(36, 59, 83))
            setPadding(0, padding, 0, 0)
        }
        root.addView(eventsView, matchWidthWrapHeight())

        return ScrollView(this).apply { addView(root) }
    }

    private fun actionButton(label: String, onClick: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { onClick() }
            layoutParams = matchWidthWrapHeight().apply {
                topMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private fun simulate(input: SimulatedInput) {
        ContextCompat.startForegroundService(this, HelmetService.simulateIntent(this, input))
    }

    private fun switchHardwareMode(simulatorEnabled: Boolean) {
        val store = RuntimeConfigStore(this)
        val current = store.load()
        store.save(
            current.copy(
                revision = current.revision + 1,
                simulatorEnabled = simulatorEnabled,
            ),
        )
        restartRuntimeService()
    }

    private fun restartRuntimeService() {
        lifecycleScope.launch {
            stopService(HelmetService.startIntent(this@MainActivity))
            delay(300)
            ContextCompat.startForegroundService(this@MainActivity, HelmetService.startIntent(this@MainActivity))
        }
    }

    private fun saveBackendConfiguration(clear: Boolean) {
        val store = RuntimeConfigStore(this)
        val current = store.load()
        val enteredToken = backendTokenInput.text.toString()
        store.save(
            current.copy(
                revision = current.revision + 1,
                backendBaseUrl = if (clear) "" else backendUrlInput.text.toString().trim(),
                backendBearerToken = when {
                    clear -> ""
                    enteredToken.isNotBlank() -> enteredToken
                    else -> current.backendBearerToken
                },
            ),
        )
        backendTokenInput.text.clear()
        if (clear) backendUrlInput.text.clear()
        MediaUploadWorker.enqueue(this)
        TrackUploadWorker.enqueue(this)
        CommunicationWorker.enqueue(this)
        SafetyAlertWorker.enqueue(this)
    }

    private fun saveRtkConfiguration(enabled: Boolean) {
        val store = RuntimeConfigStore(this)
        val current = store.load()
        val enteredPassword = ntripPasswordInput.text.toString()
        val candidate = runCatching {
            val url = if (enabled) ntripUrlInput.text.toString().trim() else ""
            if (enabled) NtripEndpoint.parse(url)
            current.rtk.copy(
                enabled = enabled,
                ntripUrl = url,
                username = if (enabled) ntripUsernameInput.text.toString().trim() else "",
                password = when {
                    !enabled -> ""
                    enteredPassword.isNotBlank() -> enteredPassword
                    else -> current.rtk.password
                },
            )
        }
        candidate.onFailure { error ->
            ntripUrlInput.error = error.message ?: "Invalid NTRIP configuration"
        }.onSuccess { rtk ->
            ntripUrlInput.error = null
            store.save(current.copy(revision = current.revision + 1, rtk = rtk))
            ntripPasswordInput.text.clear()
            if (!enabled) {
                ntripUrlInput.text.clear()
                ntripUsernameInput.text.clear()
            }
            restartRuntimeService()
        }
    }

    private fun saveLocalIntercomConfiguration(enabled: Boolean) {
        val store = RuntimeConfigStore(this)
        val current = store.load()
        runCatching {
            current.localIntercom.copy(
                enabled = enabled,
                fallbackWhenInternetUnavailable = true,
                groupId = intercomGroupInput.text.toString().toInt(),
                channel = intercomChannelInput.text.toString().toInt(),
                keySlot = intercomKeySlotInput.text.toString().toInt(),
            )
        }.onSuccess { localIntercom ->
            intercomGroupInput.error = null
            intercomChannelInput.error = null
            intercomKeySlotInput.error = null
            store.save(current.copy(revision = current.revision + 1, localIntercom = localIntercom))
            restartRuntimeService()
        }.onFailure { error ->
            intercomGroupInput.error = error.message ?: "Invalid local intercom configuration"
        }
    }

    private fun observeRuntime() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    RuntimeStatus.snapshot.collect { snapshot ->
                        statusView.text = buildString {
                            appendLine("state: ${snapshot.state}")
                            appendLine("networkAvailable: ${snapshot.networkAvailable}")
                            appendLine("networkState: ${snapshot.networkState}")
                            appendLine("networkTransports: ${snapshot.networkTransports}")
                            appendLine("networkMetered: ${snapshot.networkMetered}")
                            appendLine("networkInterface: ${snapshot.networkInterface}")
                            appendLine("persistedEvents: ${snapshot.persistedEventCount}")
                            appendLine("lastEvent: ${snapshot.lastEventType ?: "none"}")
                            appendLine("appVersion: ${snapshot.appVersion}")
                            appendLine("androidVersion: ${snapshot.androidVersion}")
                            appendLine("configRevision: ${snapshot.configRevision}")
                            appendLine("hardwareMode: ${snapshot.hardwareMode}")
                            appendLine("hardwareConnected: ${snapshot.hardwareConnected}")
                            appendLine("hardwareLinkState: ${snapshot.hardwareLinkState}")
                            appendLine("cameraCount: ${snapshot.cameraCount}")
                            appendLine("camera: ${snapshot.cameraSummary}")
                            appendLine("pendingMedia: ${snapshot.pendingMediaCount}")
                            appendLine("locationState: ${snapshot.locationState}")
                            appendLine("locationProvider: ${snapshot.locationProvider}")
                            appendLine("locationFixQuality: ${snapshot.locationFixQuality}")
                            appendLine("locationHasPosition: ${snapshot.locationHasPosition}")
                            appendLine("rtkState: ${snapshot.rtkState}")
                            appendLine("rtkCorrectionFrames: ${snapshot.rtkCorrectionFrames}")
                            appendLine("rtkCorrectionBytes: ${snapshot.rtkCorrectionBytes}")
                            appendLine("rtkLastError: ${snapshot.rtkLastError ?: "none"}")
                            appendLine("localIntercomState: ${snapshot.localIntercomState}")
                            appendLine("localIntercomPeers: ${snapshot.localIntercomPeers}")
                            appendLine("localIntercomRssiDbm: ${snapshot.localIntercomRssiDbm ?: "unknown"}")
                            appendLine(
                                "localIntercomPacketLossPermille: " +
                                    (snapshot.localIntercomPacketLossPermille ?: "unknown"),
                            )
                            appendLine(
                                "localIntercomLatencyMillis: " +
                                    (snapshot.localIntercomLatencyMillis ?: "unknown"),
                            )
                            appendLine(
                                "localIntercomLastError: ${snapshot.localIntercomLastError ?: "none"}",
                            )
                            appendLine("pendingTrack: ${snapshot.pendingTrackCount}")
                            appendLine("activeGeofences: ${snapshot.activeGeofenceCount}")
                            appendLine("activeCallId: ${snapshot.activeCallId}")
                            appendLine("callState: ${snapshot.callState}")
                            appendLine("pendingCallSync: ${snapshot.pendingCallSyncCount}")
                            appendLine("pendingBroadcastReceipts: ${snapshot.pendingBroadcastReceiptCount}")
                            appendLine("pendingSafetyAlerts: ${snapshot.pendingSafetyAlertCount}")
                            appendLine("retainedSafetySamples: ${snapshot.retainedSafetySampleCount}")
                            appendLine("batteryPresent: ${snapshot.batteryPresent}")
                            appendLine("batteryPercent: ${snapshot.batteryPercent ?: "unknown"}")
                            appendLine("batteryVoltageMillivolts: ${snapshot.batteryVoltageMillivolts ?: "unknown"}")
                            appendLine("timeSource: ${snapshot.timeSource}")
                            appendLine("timeSynchronized: ${snapshot.timeSynchronized}")
                            appendLine("timeUncertaintyMillis: ${snapshot.timeUncertaintyMillis ?: "unknown"}")
                            append("timeCalibrationAgeMillis: ${snapshot.timeCalibrationAgeMillis ?: "unknown"}")
                        }
                    }
                }
                launch {
                    eventStore.observeRecent(20).collect { events ->
                        eventsView.text = buildString {
                            appendLine("Recent persisted events")
                            events.forEach { event ->
                                appendLine("${event.occurredAtEpochMillis} ${event.eventType} ${event.severity}")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestRequiredHardwarePermissions() {
        val requested = linkedSetOf<String>()
        val cameraManager = getSystemService(CameraManager::class.java)
        if (runCatching { cameraManager.cameraIdList.isNotEmpty() }.getOrDefault(false)) {
            requested += Manifest.permission.CAMERA
            requested += Manifest.permission.RECORD_AUDIO
        }
        val locationManager = getSystemService(LocationManager::class.java)
        val positionProviders = setOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        if (locationManager.allProviders.any(positionProviders::contains)) {
            requested += Manifest.permission.ACCESS_FINE_LOCATION
            requested += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        val missing = requested
            .filter { permission ->
                ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
            }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun saveGeofenceConfiguration() {
        runCatching { RuntimeConfigStore.parseGeofences(geofenceInput.text.toString()) }
            .onSuccess { geofences ->
                geofenceInput.error = null
                val store = RuntimeConfigStore(this)
                val current = store.load()
                store.save(current.copy(revision = current.revision + 1, geofences = geofences))
                restartRuntimeService()
            }
            .onFailure { error -> geofenceInput.error = error.message ?: "Invalid geofence JSON" }
    }
}
