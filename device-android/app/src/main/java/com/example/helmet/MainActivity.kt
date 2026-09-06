package com.example.helmet

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.helmet.data.local.RuntimeConfigStore
import com.example.helmet.core.model.RtkRuntimeConfig
import com.example.helmet.core.model.RtkTransportMode
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.service.runtime.CommunicationWorkTrigger
import com.example.helmet.service.runtime.CommunicationWorker
import com.example.helmet.service.runtime.HelmetService
import com.example.helmet.service.runtime.MediaUploadWorker
import com.example.helmet.service.runtime.SafetyAlertWorker
import com.example.helmet.service.runtime.TrackUploadWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), OperatorDashboardView.Actions {
    private lateinit var dashboard: OperatorDashboardView
    private lateinit var repository: OperatorUiRepository
    private lateinit var configStore: RuntimeConfigStore
    private var selectedPage = DashboardPage.OVERVIEW

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        repository.refresh()
        startRuntimeService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedPage = savedInstanceState?.getString(STATE_SELECTED_PAGE)
            ?.let { runCatching { DashboardPage.valueOf(it) }.getOrNull() }
            ?: DashboardPage.OVERVIEW
        configStore = RuntimeConfigStore(this)
        repository = OperatorUiRepository(this)
        dashboard = OperatorDashboardView(
            context = this,
            debugToolsAvailable = VariantDebugUiPolicy.debugToolsAvailable,
            actions = this,
            restoredPage = selectedPage,
        )
        setContentView(dashboard)
        startRuntimeService()
        observeDashboard()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SELECTED_PAGE, selectedPage.name)
        super.onSaveInstanceState(outState)
    }

    override fun onPageChanged(page: DashboardPage) {
        selectedPage = page
    }

    override fun onRefreshDetection() {
        repository.refresh()
        startRuntimeService()
        showMessage("已刷新检测结果")
    }

    override fun onStartService() {
        startRuntimeService()
        repository.refresh()
        showMessage("前台服务启动请求已发送")
    }

    override fun onRequestPermissions() {
        requestRequiredHardwarePermissions()
    }

    override fun onEditSerialConfiguration() {
        val current = configStore.load()
        val path = editField(
            label = "串口设备节点",
            value = current.hardwareDevicePath,
            hint = "/dev/ttyAS2",
        )
        val baud = editField(
            label = "波特率",
            value = current.hardwareBaudRate.toString(),
            hint = "115200",
            inputType = InputType.TYPE_CLASS_NUMBER,
        )
        val rtkMode = editField(
            label = "RTK 接入模式",
            value = current.rtk.transportMode.name,
            hint = "HSL 或 DIRECT_UART4",
        )
        val rtkBaud = editField(
            label = "UART4 波特率",
            value = current.rtk.directBaudRate.toString(),
            hint = "115200",
            inputType = InputType.TYPE_CLASS_NUMBER,
        )
        val content = if (VariantDebugUiPolicy.debugToolsAvailable) {
            dialogColumn(
                path.first,
                path.second,
                baud.first,
                baud.second,
                rtkMode.first,
                rtkMode.second,
                rtkBaud.first,
                rtkBaud.second,
            )
        } else {
            dialogColumn(rtkMode.first, rtkMode.second, rtkBaud.first, rtkBaud.second)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (VariantDebugUiPolicy.debugToolsAvailable) "修改硬件串口配置" else "RTK 接入配置")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val normalizedPath = if (VariantDebugUiPolicy.debugToolsAvailable) {
                        LocalConfigurationPolicy.normalizeHardwareDevicePath(
                            path.second.text.toString(),
                            productionBuild = false,
                        )
                    } else {
                        RuntimeConfig().hardwareDevicePath
                    }
                    val normalizedBaud = if (VariantDebugUiPolicy.debugToolsAvailable) {
                        LocalConfigurationPolicy.validateHardwareBaudRate(baud.second.text.toString().toInt())
                    } else {
                        RuntimeConfig().hardwareBaudRate
                    }
                    val normalizedRtkMode = RtkTransportMode.valueOf(
                        rtkMode.second.text.toString().trim().uppercase(),
                    )
                    val normalizedRtkBaud = rtkBaud.second.text.toString().toInt().also {
                        require(it in RtkRuntimeConfig.SUPPORTED_DIRECT_RTK_BAUD_RATES) {
                            "不支持的 UART4 波特率"
                        }
                    }
                    configStore.update { config ->
                        config.copy(
                            simulatorEnabled = false,
                            hardwareDevicePath = normalizedPath,
                            hardwareBaudRate = normalizedBaud,
                            rtk = config.rtk.copy(
                                transportMode = normalizedRtkMode,
                                directDevicePath = "/dev/ttyAS4",
                                directBaudRate = normalizedRtkBaud,
                            ),
                        )
                    }
                }.onSuccess {
                    dialog.dismiss()
                    configurationSaved("RTK 接入配置已保存，已恢复真实 UART 模式")
                }.onFailure { error ->
                    rtkMode.second.error = error.message ?: "串口配置无效"
                }
            }
        }
        dialog.show()
    }

    override fun onEditBackendConfiguration() {
        val current = configStore.load()
        val person = editField(
            label = "人员编号",
            value = current.personId.orEmpty(),
            hint = "可留空",
        )
        val url = editField(
            label = "服务器地址",
            value = current.backendBaseUrl,
            hint = "https://helmet.example.com",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        val token = editField(
            label = "访问凭据",
            value = "",
            hint = if (current.backendBearerToken.isBlank()) "请输入访问凭据" else "留空表示不修改",
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        val content = dialogColumn(person.first, person.second, url.first, url.second, token.first, token.second)
        val dialog = AlertDialog.Builder(this)
            .setTitle("修改服务器配置")
            .setView(content)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                runCatching {
                    val normalizedUrl = LocalConfigurationPolicy.normalizeBackendBaseUrl(
                        url.second.text.toString(),
                        BuildConfig.PRODUCTION_BUILD,
                    )
                    val enteredToken = token.second.text.toString()
                    configStore.update { config ->
                        val savedToken = when {
                            enteredToken.isNotBlank() -> LocalConfigurationPolicy.validateBearerToken(enteredToken)
                            config.backendBearerToken.isNotBlank() -> config.backendBearerToken
                            else -> throw IllegalArgumentException("访问凭据不能为空")
                        }
                        config.copy(
                            personId = person.second.text.toString().trim().ifBlank { null },
                            backendBaseUrl = normalizedUrl,
                            backendBearerToken = savedToken,
                        )
                    }
                }.onSuccess {
                    dialog.dismiss()
                    configurationSaved("服务器配置已保存")
                    enqueueUploads()
                }.onFailure { error ->
                    url.second.error = error.message ?: "服务器配置无效"
                }
            }
        }
        dialog.show()
    }

    override fun onClearBackendConfiguration() {
        AlertDialog.Builder(this)
            .setTitle("清除服务器配置")
            .setMessage("清除后，本地数据仍会保留，但不能上传到服务器。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认清除") { _, _ ->
                runCatching {
                    configStore.update(LocalConfigurationPolicy::clearBackendAndMqttConfiguration)
                }.onSuccess {
                    configurationSaved("服务器配置已清除")
                }.onFailure { error ->
                    showMessage(error.message ?: "无法清除服务器配置")
                }
            }
            .show()
    }

    override fun onUseSimulator() {
        if (!VariantDebugUiPolicy.debugToolsAvailable) return
        updateHardwareMode(simulatorEnabled = true)
    }

    override fun onUseUart() {
        if (!VariantDebugUiPolicy.debugToolsAvailable) return
        updateHardwareMode(simulatorEnabled = false)
    }

    override fun onSimulate(input: SimulatedInput) {
        if (!VariantDebugUiPolicy.debugToolsAvailable) return
        if (!configStore.load().simulatorEnabled) {
            showMessage("请先启用模拟硬件")
            return
        }
        ContextCompat.startForegroundService(this, HelmetService.simulateIntent(this, input))
        showMessage("调试模拟请求已发送")
    }

    private fun observeDashboard() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    repository.states.collect(dashboard::render)
                }
                launch {
                    while (isActive) {
                        dashboard.updateClock(repository.nowEpochMillis())
                        delay(1_000L)
                    }
                }
            }
        }
    }

    private fun updateHardwareMode(simulatorEnabled: Boolean) {
        val effective = LocalConfigurationPolicy.effectiveSimulatorEnabled(
            productionBuild = BuildConfig.PRODUCTION_BUILD,
            requested = simulatorEnabled,
        )
        if (simulatorEnabled && !effective) return
        runCatching {
            configStore.update { current -> current.copy(simulatorEnabled = effective) }
        }.onSuccess {
            configurationSaved(if (effective) "调试模拟硬件已启用" else "已恢复真实 UART 模式")
        }.onFailure { error ->
            showMessage(error.message ?: "无法切换硬件模式")
        }
    }

    private fun configurationSaved(message: String) {
        CommunicationWorker.enqueue(this, CommunicationWorkTrigger.CONFIG_REVISION_CHANGED)
        repository.refresh()
        restartRuntimeService()
        showMessage(message)
    }

    private fun startRuntimeService() {
        ContextCompat.startForegroundService(this, HelmetService.startIntent(this))
    }

    private fun restartRuntimeService() {
        lifecycleScope.launch {
            stopService(HelmetService.startIntent(this@MainActivity))
            delay(300L)
            startRuntimeService()
        }
    }

    private fun enqueueUploads() {
        MediaUploadWorker.enqueue(this)
        TrackUploadWorker.enqueue(this)
        SafetyAlertWorker.enqueue(this)
        CommunicationWorker.enqueue(this, CommunicationWorkTrigger.CONFIG_REVISION_CHANGED)
    }

    private fun requestRequiredHardwarePermissions() {
        val cameraAvailable = runCatching {
            getSystemService(CameraManager::class.java).cameraIdList.isNotEmpty()
        }.getOrDefault(false)
        val locationManager = getSystemService(LocationManager::class.java)
        val positionProviders = setOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.FUSED_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        )
        val locationProviderAvailable = runCatching {
            locationManager.allProviders.any(positionProviders::contains)
        }.getOrDefault(false)
        val bluetoothAvailable = packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val missing = LocalConfigurationPolicy.requiredRuntimePermissions(
            cameraAvailable = cameraAvailable,
            locationProviderAvailable = locationProviderAvailable,
            bluetoothAvailable = bluetoothAvailable,
        ).filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            showMessage("所需权限已授予")
            repository.refresh()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun editField(
        label: String,
        value: String,
        hint: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ): Pair<TextView, EditText> {
        val labelView = TextView(this).apply {
            text = label
            textSize = 15f
            setPadding(0, dp(10), 0, dp(4))
        }
        val input = EditText(this).apply {
            setText(value)
            this.hint = hint
            this.inputType = inputType
            textSize = 17f
            minHeight = dp(54)
            isSingleLine = true
        }
        return labelView to input
    }

    private fun dialogColumn(vararg views: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(6), dp(24), dp(12))
        views.forEach { addView(it, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )) }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val STATE_SELECTED_PAGE = "selected_dashboard_page"
    }
}
