package com.example.helmet

import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.HelmetEvent
import com.example.helmet.core.model.HelmetOperationalState
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RuntimeSnapshot
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.core.model.StreamState
import com.example.helmet.hardware.api.BoardAvailability
import com.example.helmet.hardware.api.BoardResourceStatus
import java.net.URI
import java.util.Locale

enum class DashboardPage(val title: String) {
    OVERVIEW("设备总览"),
    ALERTS("安全报警"),
    FEATURES("功能状态"),
    DEVICES("设备检测"),
    MAINTENANCE("维护信息"),
    DEBUG("调试功能"),
}

internal fun dashboardPages(debugToolsAvailable: Boolean): List<DashboardPage> = buildList {
    add(DashboardPage.OVERVIEW)
    add(DashboardPage.ALERTS)
    add(DashboardPage.FEATURES)
    add(DashboardPage.DEVICES)
    add(DashboardPage.MAINTENANCE)
    if (debugToolsAvailable) add(DashboardPage.DEBUG)
}

enum class UiTone {
    NORMAL,
    ATTENTION,
    ALARM,
    MUTED,
}

data class UiStatus(
    val title: String,
    val label: String,
    val detail: String,
    val tone: UiTone,
    val updatedAtEpochMillis: Long? = null,
)

enum class FeatureAvailability(val label: String, val tone: UiTone) {
    AVAILABLE("可用", UiTone.NORMAL),
    RUNNING("运行中", UiTone.NORMAL),
    OFFLINE_AVAILABLE("离线可用", UiTone.ATTENTION),
    WAITING_FOR_HARDWARE("等待外设", UiTone.ATTENTION),
    INCOMPLETE_CONFIGURATION("配置不完整", UiTone.MUTED),
    FAULT("故障", UiTone.ALARM),
}

data class FeatureUiState(
    val title: String,
    val availability: FeatureAvailability,
    val detail: String,
)

data class AlertUiState(
    val alertId: String,
    val title: String,
    val active: Boolean,
    val simulated: Boolean,
    val occurredAtEpochMillis: Long,
    val location: String,
    val delivery: String,
    val tone: UiTone,
)

data class QueueUiState(
    val tracks: Int,
    val media: Int,
    val alerts: Int,
    val communications: Int,
) {
    val total: Int = tracks + media + alerts + communications
}

data class MaintenanceRow(
    val label: String,
    val value: String,
    val tone: UiTone = UiTone.MUTED,
)

data class OperatorUiState(
    val overall: UiStatus,
    val service: UiStatus,
    val network: UiStatus,
    val backend: UiStatus,
    val uart: UiStatus,
    val location: UiStatus,
    val sensors: List<UiStatus>,
    val activeAlerts: List<AlertUiState>,
    val recentAlerts: List<AlertUiState>,
    val riskStatuses: List<UiStatus>,
    val features: List<FeatureUiState>,
    val deviceChecks: List<UiStatus>,
    val queues: QueueUiState,
    val maintenance: List<MaintenanceRow>,
    val recentErrors: List<MaintenanceRow>,
    val deviceId: String,
    val appVersion: String,
    val simulatorEnabled: Boolean,
)

data class PlatformCapabilities(
    val microphoneDeclared: Boolean,
    val microphonePermissionGranted: Boolean,
    val cameraPermissionGranted: Boolean,
    val locationProviderAvailable: Boolean,
    val gnssProviderAvailable: Boolean,
    val locationPermissionGranted: Boolean,
    val vibratorAvailable: Boolean,
    val systemImuAvailable: Boolean,
    val boardStatuses: List<BoardResourceStatus> = emptyList(),
)

data class OperatorUiInput(
    val snapshot: RuntimeSnapshot,
    val config: RuntimeConfig,
    val recentAlerts: List<SafetyAlertRecord>,
    val recentSamples: List<SafetySensorTelemetry>,
    val recentEvents: List<HelmetEvent>,
    val capabilities: PlatformCapabilities,
    val nowEpochMillis: Long,
)

object OperatorUiMapper {
    private const val SERVICE_STALE_MILLIS = 90_000L
    private const val LOCATION_STALE_MILLIS = 15_000L
    private const val SENSOR_STALE_MILLIS = 30_000L
    private const val BACKEND_STALE_MILLIS = 150_000L
    private const val MAXIMUM_CLOCK_SKEW_MILLIS = 30_000L
    private const val SAMPLE_IMU = 0x0001
    private const val SAMPLE_ELECTRIC = 0x0002
    private const val SAMPLE_ALTITUDE = 0x0004
    private const val SAMPLE_PRESSURE = 0x0010
    private const val SENSOR_FAULT_IMU = 0x0001
    private const val SENSOR_FAULT_ELECTRIC = 0x0002
    private const val SENSOR_FAULT_HEIGHT = 0x0004

    fun map(input: OperatorUiInput): OperatorUiState {
        val snapshot = input.snapshot
        val latestAlerts = input.recentAlerts.distinctBy(SafetyAlertRecord::alertId)
        val activeRealAlerts = latestAlerts.filter { it.active && !it.simulated }
        val activeSimulatedAlerts = latestAlerts.filter { it.active && it.simulated }
        val queues = QueueUiState(
            tracks = snapshot.pendingTrackCount,
            media = snapshot.pendingMediaCount,
            alerts = snapshot.pendingSafetyAlertCount,
            communications = snapshot.pendingCallSyncCount + snapshot.pendingBroadcastReceiptCount,
        )

        val service = serviceStatus(snapshot, input.nowEpochMillis)
        val network = networkStatus(snapshot)
        val backend = backendStatus(input)
        val uart = uartStatus(snapshot, input.config)
        val location = locationStatus(snapshot, input.nowEpochMillis)
        val sensorFaults = activeRealAlerts
            .filter { it.alarmType == "SENSOR_FAULT" }
            .fold(0) { result, alert -> result or alert.sensorFaults }
        val sensors = sensorStatuses(input, sensorFaults)
        val riskStatuses = riskStatuses(activeRealAlerts, activeSimulatedAlerts)
        val features = featureStatuses(input, sensors, backend)
        val deviceChecks = deviceChecks(input, service, network, backend, uart, location, sensors)
        val boardFault = input.capabilities.boardStatuses.any {
            it.availability in setOf(
                BoardAvailability.DRIVER_MISSING,
                BoardAvailability.PROTOCOL_INCOMPATIBLE,
                BoardAvailability.PIN_CONFLICT,
                BoardAvailability.PERMISSION_DENIED,
                BoardAvailability.FAULT,
            )
        }
        val boardAttention = input.capabilities.boardStatuses.any {
            it.availability !in setOf(BoardAvailability.AVAILABLE, BoardAvailability.RUNNING)
        }

        val explicitFault = listOf(service, backend, uart, location).any { it.tone == UiTone.ALARM } ||
            sensors.any { it.tone == UiTone.ALARM } ||
            activeRealAlerts.any { it.alarmType == "SENSOR_FAULT" } ||
            features.any { it.availability == FeatureAvailability.FAULT } || boardFault
        val needsAttention = explicitFault || queues.total > 0 ||
            listOf(service, network, backend, uart, location).any { it.tone != UiTone.NORMAL } ||
            sensors.any { it.tone != UiTone.NORMAL } || boardAttention
        val overall = when {
            activeRealAlerts.isNotEmpty() -> UiStatus(
                title = "总体状态",
                label = "报警",
                detail = "${activeRealAlerts.size} 项活动报警，请立即查看",
                tone = UiTone.ALARM,
            )
            explicitFault -> UiStatus(
                title = "总体状态",
                label = "注意",
                detail = "检测到明确故障，请进入设备检测",
                tone = UiTone.ALARM,
            )
            needsAttention -> UiStatus(
                title = "总体状态",
                label = "注意",
                detail = attentionSummary(queues, network, backend, uart, location),
                tone = UiTone.ATTENTION,
            )
            else -> UiStatus(
                title = "总体状态",
                label = "正常",
                detail = "服务、连接和设备状态正常",
                tone = UiTone.NORMAL,
            )
        }

        return OperatorUiState(
            overall = overall,
            service = service,
            network = network,
            backend = backend,
            uart = uart,
            location = location,
            sensors = sensors,
            activeAlerts = activeRealAlerts.map(::alertUi),
            recentAlerts = latestAlerts.take(12).map(::alertUi),
            riskStatuses = riskStatuses,
            features = features,
            deviceChecks = deviceChecks,
            queues = queues,
            maintenance = maintenanceRows(input, queues),
            recentErrors = recentErrorRows(input.recentEvents),
            deviceId = snapshot.deviceId,
            appVersion = snapshot.appVersion,
            simulatorEnabled = input.config.simulatorEnabled,
        )
    }

    private fun serviceStatus(snapshot: RuntimeSnapshot, now: Long): UiStatus {
        val age = ageMillis(now, snapshot.statusUpdatedAtEpochMillis)
        if (snapshot.statusUpdatedAtEpochMillis <= 0) {
            return UiStatus("前台服务", "启动中", "等待服务发布运行状态", UiTone.ATTENTION)
        }
        if (age > SERVICE_STALE_MILLIS) {
            return UiStatus("前台服务", "状态超时", "超过 90 秒未更新", UiTone.ALARM, snapshot.statusUpdatedAtEpochMillis)
        }
        return when (snapshot.state) {
            HelmetOperationalState.FAULT -> UiStatus(
                "前台服务", "故障", "服务报告运行故障", UiTone.ALARM, snapshot.statusUpdatedAtEpochMillis,
            )
            HelmetOperationalState.BOOTING,
            HelmetOperationalState.SELF_TEST,
            -> UiStatus(
                "前台服务", "启动中", "正在准备设备能力", UiTone.ATTENTION, snapshot.statusUpdatedAtEpochMillis,
            )
            HelmetOperationalState.SHUTTING_DOWN -> UiStatus(
                "前台服务", "正在停止", "服务正在结束当前任务", UiTone.ATTENTION, snapshot.statusUpdatedAtEpochMillis,
            )
            else -> UiStatus(
                "前台服务", "运行中", operationalStateDetail(snapshot), UiTone.NORMAL, snapshot.statusUpdatedAtEpochMillis,
            )
        }
    }

    private fun operationalStateDetail(snapshot: RuntimeSnapshot): String = when {
        snapshot.videoRecording -> "正在录像"
        snapshot.voiceMessageRecording -> "正在录制语音"
        snapshot.streamState == StreamState.STREAMING -> "音视频通话运行中"
        snapshot.state == HelmetOperationalState.OFFLINE_READY -> "离线业务可继续运行"
        else -> "核心功能在后台运行"
    }

    private fun networkStatus(snapshot: RuntimeSnapshot): UiStatus = when (snapshot.networkState) {
        "VALIDATED" -> UiStatus(
            "网络", "已联网", networkTransportLabel(snapshot.networkTransports), UiTone.NORMAL,
        )
        "LOCAL_ONLY" -> UiStatus("网络", "仅本地网络", "没有互联网连接", UiTone.ATTENTION)
        "CAPTIVE_OR_UNVALIDATED" -> UiStatus("网络", "网络受限", "互联网连接尚未验证", UiTone.ATTENTION)
        else -> UiStatus("网络", "离线", "数据将保存在本机并等待补传", UiTone.ATTENTION)
    }

    private fun backendStatus(input: OperatorUiInput): UiStatus {
        val config = input.config
        if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) {
            return UiStatus("服务器", "未配置", "未设置完整服务器地址和凭据", UiTone.MUTED)
        }
        if (input.snapshot.networkState != "VALIDATED") {
            return UiStatus("服务器", "等待网络", "网络恢复后自动重连", UiTone.ATTENTION)
        }
        val latest = input.recentEvents.firstOrNull { event ->
            event.eventType in setOf(
                "DEVICE_STATUS_UPLOAD_COMPLETED",
                "DEVICE_STATUS_UPLOAD_RETRY_SCHEDULED",
                "DEVICE_STATUS_UPLOAD_REJECTED",
            )
        } ?: return UiStatus("服务器", "正在连接", "等待首次状态确认", UiTone.ATTENTION)
        return when (latest.eventType) {
            "DEVICE_STATUS_UPLOAD_COMPLETED" -> {
                if (ageMillis(input.nowEpochMillis, latest.occurredAtEpochMillis) <= BACKEND_STALE_MILLIS) {
                    UiStatus("服务器", "已连接", backendHost(config.backendBaseUrl), UiTone.NORMAL, latest.occurredAtEpochMillis)
                } else {
                    UiStatus("服务器", "连接待确认", "最近确认已超过 150 秒", UiTone.ATTENTION, latest.occurredAtEpochMillis)
                }
            }
            "DEVICE_STATUS_UPLOAD_REJECTED" -> UiStatus(
                "服务器", "配置被拒绝", "请检查服务器配置", UiTone.ALARM, latest.occurredAtEpochMillis,
            )
            else -> UiStatus(
                "服务器", "不可达", "正在后台重试", UiTone.ATTENTION, latest.occurredAtEpochMillis,
            )
        }
    }

    private fun uartStatus(snapshot: RuntimeSnapshot, config: RuntimeConfig): UiStatus {
        if (snapshot.hardwareMode == "SIMULATED" || config.simulatorEnabled) {
            return UiStatus(
                "UART", "调试模拟", "模拟数据不能作为外设证据", UiTone.MUTED,
            )
        }
        if (snapshot.hardwareConnected) {
            return UiStatus(
                "UART", "已连接", "${config.hardwareDevicePath} · ${config.hardwareBaudRate} 波特", UiTone.NORMAL,
            )
        }
        if (snapshot.hardwareCompatibility in setOf(
                "CONTRACT_VERSION_MISMATCH",
                "REQUIRED_CAPABILITIES_MISSING",
            )
        ) {
            return UiStatus(
                "UART", "协议不兼容",
                "App 契约 ${com.example.helmet.hardware.api.H618BoardProfile.profile.contractVersion}，模块 ${snapshot.hardwareContractVersion ?: "未知"}",
                UiTone.ALARM,
            )
        }
        if (snapshot.hardwareLastError != null || isFailureState(snapshot.hardwareLinkState)) {
            return UiStatus(
                "UART", "连接故障", "${config.hardwareDevicePath} 无法建立连接", UiTone.ALARM,
            )
        }
        return UiStatus(
            "UART", "等待外设", "${config.hardwareDevicePath} · ${config.hardwareBaudRate} 波特", UiTone.ATTENTION,
        )
    }

    private fun locationStatus(snapshot: RuntimeSnapshot, now: Long): UiStatus {
        val occurredAt = snapshot.locationOccurredAtEpochMillis
        val fresh = snapshot.locationHasPosition && occurredAt != null &&
            ageMillis(now, occurredAt) <= LOCATION_STALE_MILLIS
        if (!fresh) {
            val detail = if (occurredAt == null) "无有效定位" else "定位已过期，不使用旧坐标"
            return UiStatus("定位", "不可用", detail, UiTone.ATTENTION, occurredAt)
        }
        val accuracy = snapshot.locationHorizontalAccuracyMeters?.let {
            "，精度 ${formatOneDecimal(it.toDouble())} 米"
        }.orEmpty()
        return UiStatus(
            "定位",
            "有效",
            "${fixQualityLabel(snapshot.locationFixQuality)}$accuracy",
            UiTone.NORMAL,
            occurredAt,
        )
    }

    private fun sensorStatuses(input: OperatorUiInput, sensorFaults: Int): List<UiStatus> {
        val snapshot = input.snapshot
        val camera = when {
            snapshot.cameraCount > 0 && input.capabilities.cameraPermissionGranted -> UiStatus(
                "摄像头", if (snapshot.videoRecording) "运行中" else "可用",
                if (snapshot.videoRecording) "正在录像" else "检测到 ${snapshot.cameraCount} 个设备", UiTone.NORMAL,
            )
            snapshot.cameraCount > 0 -> UiStatus("摄像头", "需要权限", "请授予摄像头权限", UiTone.ATTENTION)
            else -> UiStatus("摄像头", "等待外设", "系统未检测到摄像头", UiTone.ATTENTION)
        }
        val microphone = when {
            !input.capabilities.microphoneDeclared -> UiStatus("麦克风", "等待外设", "系统未声明麦克风", UiTone.ATTENTION)
            !input.capabilities.microphonePermissionGranted -> UiStatus("麦克风", "需要权限", "请授予录音权限", UiTone.ATTENTION)
            else -> UiStatus(
                "麦克风", if (snapshot.voiceMessageRecording || snapshot.streamAudioEnabled) "运行中" else "可用",
                "系统已声明音频输入", UiTone.NORMAL,
            )
        }
        val gnss = when {
            locationStatus(snapshot, input.nowEpochMillis).tone == UiTone.NORMAL -> UiStatus(
                "GNSS / RTK", "定位中", fixQualityLabel(snapshot.locationFixQuality), UiTone.NORMAL,
                snapshot.locationOccurredAtEpochMillis,
            )
            !input.capabilities.gnssProviderAvailable && !snapshot.hardwareConnected -> UiStatus(
                "GNSS / RTK", "等待外设", "没有系统定位源或外部接收机", UiTone.ATTENTION,
            )
            else -> UiStatus("GNSS / RTK", "等待定位", "尚未取得有效位置", UiTone.ATTENTION)
        }
        val imu = sensorEvidenceStatus(
            title = "IMU",
            flagMask = SAMPLE_IMU,
            input = input,
            fault = sensorFaults and SENSOR_FAULT_IMU != 0,
            missingDetail = if (input.capabilities.systemImuAvailable) "等待有效运动数据" else "等待外部 IMU",
        )
        val electric = sensorEvidenceStatus(
            title = "电场传感器",
            flagMask = SAMPLE_ELECTRIC,
            input = input,
            fault = sensorFaults and SENSOR_FAULT_ELECTRIC != 0,
            missingDetail = "等待外部电场传感器",
        )
        val height = sensorEvidenceStatus(
            title = "高度 / 气压",
            flagMask = SAMPLE_ALTITUDE or SAMPLE_PRESSURE,
            input = input,
            fault = sensorFaults and SENSOR_FAULT_HEIGHT != 0,
            missingDetail = "等待外部高度或气压传感器",
        )
        return listOf(camera, microphone, gnss, imu, electric, height)
    }

    private fun sensorEvidenceStatus(
        title: String,
        flagMask: Int,
        input: OperatorUiInput,
        fault: Boolean,
        missingDetail: String,
    ): UiStatus {
        if (fault) return UiStatus(title, "故障", "传感器数据超出有效范围", UiTone.ALARM)
        val real = input.recentSamples.firstOrNull { !it.simulated && it.validFlags and flagMask != 0 }
        if (real != null && ageMillis(input.nowEpochMillis, real.recordedAtEpochMillis) <= SENSOR_STALE_MILLIS) {
            return UiStatus(title, "数据有效", "已收到真实传感数据", UiTone.NORMAL, real.recordedAtEpochMillis)
        }
        val simulated = input.recentSamples.firstOrNull { it.simulated && it.validFlags and flagMask != 0 }
        if (simulated != null && ageMillis(input.nowEpochMillis, simulated.recordedAtEpochMillis) <= SENSOR_STALE_MILLIS) {
            return UiStatus(title, "仅有调试数据", "不能作为真实硬件证据", UiTone.MUTED, simulated.recordedAtEpochMillis)
        }
        if (real != null) {
            return UiStatus(title, "数据过期", "旧数据不作为当前状态", UiTone.ATTENTION, real.recordedAtEpochMillis)
        }
        return UiStatus(title, "等待外设", missingDetail, UiTone.ATTENTION)
    }

    private fun riskStatuses(
        activeRealAlerts: List<SafetyAlertRecord>,
        activeSimulatedAlerts: List<SafetyAlertRecord>,
    ): List<UiStatus> = listOf(
        "FALL" to "跌落",
        "IMPACT" to "撞击",
        "VIOLENT_SHAKE" to "剧烈晃动",
        "INACTIVITY" to "长时间静止",
        "NEAR_ELECTRIC" to "近电",
        "HEIGHT_LIMIT" to "高度风险",
    ).map { (type, title) ->
        when {
            activeRealAlerts.any { it.alarmType == type } -> UiStatus(title, "报警中", "存在活动报警", UiTone.ALARM)
            activeSimulatedAlerts.any { it.alarmType == type } -> UiStatus(title, "调试模拟", "不属于真实报警", UiTone.MUTED)
            else -> UiStatus(title, "无活动报警", "当前未触发", UiTone.NORMAL)
        }
    }

    private fun featureStatuses(
        input: OperatorUiInput,
        sensors: List<UiStatus>,
        backend: UiStatus,
    ): List<FeatureUiState> {
        val snapshot = input.snapshot
        val offline = snapshot.networkState != "VALIDATED"
        val uartWaiting = !snapshot.hardwareConnected && !input.config.simulatorEnabled
        val camera = sensors.first { it.title == "摄像头" }
        val microphone = sensors.first { it.title == "麦克风" }
        val imu = sensors.first { it.title == "IMU" }
        val electric = sensors.first { it.title == "电场传感器" }
        val height = sensors.first { it.title == "高度 / 气压" }

        fun status(
            title: String,
            running: Boolean = false,
            waiting: Boolean = false,
            configurationIncomplete: Boolean = false,
            fault: Boolean = false,
            offlineCapable: Boolean = false,
            availableDetail: String,
            waitingDetail: String,
        ): FeatureUiState {
            val availability = when {
                fault -> FeatureAvailability.FAULT
                running -> FeatureAvailability.RUNNING
                waiting -> FeatureAvailability.WAITING_FOR_HARDWARE
                configurationIncomplete -> FeatureAvailability.INCOMPLETE_CONFIGURATION
                offline && offlineCapable -> FeatureAvailability.OFFLINE_AVAILABLE
                else -> FeatureAvailability.AVAILABLE
            }
            val detail = when (availability) {
                FeatureAvailability.RUNNING -> "功能正在执行"
                FeatureAvailability.WAITING_FOR_HARDWARE -> waitingDetail
                FeatureAvailability.INCOMPLETE_CONFIGURATION -> "请在维护页面完成服务器配置"
                FeatureAvailability.OFFLINE_AVAILABLE -> "当前离线，数据会先保存在本机"
                FeatureAvailability.FAULT -> "检测到明确故障，请查看设备检测"
                FeatureAvailability.AVAILABLE -> availableDetail
            }
            return FeatureUiState(title, availability, detail)
        }

        val backendMissing = input.config.backendBaseUrl.isBlank() || input.config.backendBearerToken.isBlank()
        val streamFault = snapshot.streamState == StreamState.FAILED || snapshot.streamError != null
        return listOf(
            status(
                title = "按键控制音视频推流",
                running = snapshot.streamState in setOf(StreamState.STARTING, StreamState.STREAMING),
                waiting = uartWaiting || microphone.tone == UiTone.ATTENTION,
                configurationIncomplete = backendMissing,
                fault = streamFault,
                offlineCapable = true,
                availableDetail = "按键请求会持久保存并连接服务器",
                waitingDetail = "等待按键模块和麦克风",
            ),
            status(
                title = "实时定位",
                running = locationStatus(snapshot, input.nowEpochMillis).tone == UiTone.NORMAL,
                waiting = sensors.first { it.title == "GNSS / RTK" }.label == "等待外设",
                offlineCapable = true,
                availableDetail = "定位和轨迹记录已准备",
                waitingDetail = "等待 GNSS 或 RTK 接收机",
            ),
            status(
                title = "拍照与录像",
                running = snapshot.videoRecording,
                waiting = camera.tone == UiTone.ATTENTION,
                offlineCapable = true,
                availableDetail = "媒体会保存在本机并自动上传",
                waitingDetail = "等待摄像头",
            ),
            status(
                title = "语音视频通话",
                running = snapshot.activeCallId != "none" || snapshot.streamState != StreamState.IDLE,
                waiting = microphone.tone == UiTone.ATTENTION,
                configurationIncomplete = backendMissing && input.config.localIntercom.enabled.not(),
                fault = streamFault,
                offlineCapable = input.config.localIntercom.enabled,
                availableDetail = if (backend.tone == UiTone.NORMAL) "可与服务器建立通话" else "等待服务器连接",
                waitingDetail = "等待麦克风或摄像头",
            ),
            status(
                title = "跌落撞击报警",
                running = imu.tone == UiTone.NORMAL,
                waiting = imu.label in setOf("等待外设", "数据过期"),
                fault = imu.tone == UiTone.ALARM,
                offlineCapable = true,
                availableDetail = "运动风险检测已准备",
                waitingDetail = "等待 IMU 数据",
            ),
            status(
                title = "近电报警",
                running = electric.tone == UiTone.NORMAL,
                waiting = electric.label in setOf("等待外设", "数据过期"),
                fault = electric.tone == UiTone.ALARM,
                offlineCapable = true,
                availableDetail = "近电风险检测已准备",
                waitingDetail = "等待电场传感器数据",
            ),
            status(
                title = "高度检测",
                running = height.tone == UiTone.NORMAL,
                waiting = height.label in setOf("等待外设", "数据过期"),
                fault = height.tone == UiTone.ALARM,
                offlineCapable = true,
                availableDetail = "高度风险检测已准备",
                waitingDetail = "等待高度或气压传感器数据",
            ),
        )
    }

    private fun deviceChecks(
        input: OperatorUiInput,
        service: UiStatus,
        network: UiStatus,
        backend: UiStatus,
        uart: UiStatus,
        location: UiStatus,
        sensors: List<UiStatus>,
    ): List<UiStatus> {
        val hardwareConnected = input.snapshot.hardwareConnected && !input.config.simulatorEnabled
        val led = if (hardwareConnected) {
            UiStatus("LED", "状态未知", "协议未提供独立自检结果", UiTone.MUTED)
        } else {
            UiStatus("LED", "等待外设", "等待外部执行器", UiTone.ATTENTION)
        }
        val vibrator = when {
            input.capabilities.vibratorAvailable -> UiStatus("振动器", "系统可用", "检测到 Android 振动器", UiTone.NORMAL)
            hardwareConnected -> UiStatus("振动器", "状态未知", "外部振动器需要实物检测", UiTone.MUTED)
            else -> UiStatus("振动器", "等待外设", "没有系统振动器", UiTone.ATTENTION)
        }
        val buzzer = if (hardwareConnected) {
            UiStatus("蜂鸣器", "状态未知", "外部蜂鸣器需要实物检测", UiTone.MUTED)
        } else {
            UiStatus("蜂鸣器", "等待外设", "等待外部执行器", UiTone.ATTENTION)
        }
        val lora = when (input.snapshot.localIntercomState) {
            "JOINED", "TRANSMITTING", "RECEIVING" -> UiStatus(
                "LoRa 模块", "已连接", "本地对讲链路可用", UiTone.NORMAL,
            )
            "FAILED", "FAULT" -> UiStatus("LoRa 模块", "故障", "本地对讲链路异常", UiTone.ALARM)
            "DISABLED" -> UiStatus("LoRa 模块", "未配置", "本地对讲未启用", UiTone.MUTED)
            else -> UiStatus("LoRa 模块", "等待外设", "尚未收到模块状态", UiTone.ATTENTION)
        }
        val boardChecks = input.capabilities.boardStatuses.map(::boardUiStatus)
        return listOf(
            service.copy(title = "前台服务"),
            network,
            backend,
            location,
            sensors.first { it.title == "电场传感器" },
            sensors.first { it.title == "高度 / 气压" },
            vibrator,
            buzzer,
            lora,
        ) + if (boardChecks.isEmpty()) {
            listOf(
                uart,
                sensors.first { it.title == "摄像头" },
                sensors.first { it.title == "麦克风" },
                sensors.first { it.title == "IMU" },
                led,
            )
        } else {
            boardChecks
        }
    }

    private fun boardUiStatus(status: BoardResourceStatus): UiStatus = UiStatus(
        title = when (status.resourceId) {
            "peripheral_3v3_enable" -> "3.3V 外设电源"
            "camera_power_enable" -> "摄像头电源"
            "camera_usb1" -> "Camera2 摄像头"
            "audio_codec_i2c2" -> "音频 codec"
            "android_audio" -> "Android 音频"
            "modem_usb2" -> "4G 模块与网络"
            "mma8452_i2c0" -> "MMA8452 引脚"
            "hsl_mma8452" -> "MMA8452 数据"
            "rtk_uart4" -> "RTK UART4"
            "hsl_rtk_bridge" -> "RTK HSL 转发"
            "sd1_bus" -> "SD1"
            "sd2_usb3" -> "SD2"
            "hsl_physical_keys" -> "物理按键"
            "hsl_local_outputs" -> "本地输出"
            "hsl_uart2" -> "UART / HSL"
            else -> status.resourceId
        },
        label = when (status.availability) {
            BoardAvailability.AVAILABLE -> "可用"
            BoardAvailability.RUNNING -> "运行中"
            BoardAvailability.WAITING_EXTERNAL -> "等待外设"
            BoardAvailability.NOT_CONFIGURED -> "未配置"
            BoardAvailability.STALE -> "数据过期"
            BoardAvailability.DRIVER_MISSING -> "驱动缺失"
            BoardAvailability.PROTOCOL_INCOMPATIBLE -> "协议不兼容"
            BoardAvailability.PIN_CONFLICT -> "引脚冲突"
            BoardAvailability.PERMISSION_DENIED -> "权限不足"
            BoardAvailability.FAULT -> "故障"
        },
        detail = status.detail,
        tone = when (status.availability) {
            BoardAvailability.AVAILABLE,
            BoardAvailability.RUNNING,
            -> UiTone.NORMAL
            BoardAvailability.WAITING_EXTERNAL,
            BoardAvailability.STALE,
            -> UiTone.ATTENTION
            BoardAvailability.NOT_CONFIGURED -> UiTone.MUTED
            BoardAvailability.DRIVER_MISSING,
            BoardAvailability.PROTOCOL_INCOMPATIBLE,
            BoardAvailability.PIN_CONFLICT,
            BoardAvailability.PERMISSION_DENIED,
            BoardAvailability.FAULT,
            -> UiTone.ALARM
        },
        updatedAtEpochMillis = status.updatedAtEpochMillis,
    )

    private fun maintenanceRows(input: OperatorUiInput, queues: QueueUiState): List<MaintenanceRow> {
        val snapshot = input.snapshot
        val config = input.config
        return listOf(
            MaintenanceRow("运行状态", operationalStateChinese(snapshot.state), toneForState(snapshot.state)),
            MaintenanceRow("应用版本", snapshot.appVersion),
            MaintenanceRow("系统版本", snapshot.androidVersion),
            MaintenanceRow("设备编号", snapshot.deviceId),
            MaintenanceRow("串口", "${config.hardwareDevicePath} · ${config.hardwareBaudRate} 波特"),
            MaintenanceRow(
                "硬件契约",
                "App ${com.example.helmet.hardware.api.H618BoardProfile.profile.contractVersion} · 模块 ${snapshot.hardwareContractVersion ?: "等待 HELLO"}",
                if (snapshot.hardwareCompatibility == "COMPATIBLE") UiTone.NORMAL else UiTone.ATTENTION,
            ),
            MaintenanceRow("模块固件", snapshot.hardwareFirmwareVersion ?: "未识别"),
            MaintenanceRow(
                "HSL 能力",
                com.example.helmet.hardware.api.HslModuleContract
                    .capabilityNames(snapshot.hardwareCapabilityMask)
                    .joinToString()
                    .ifBlank { "等待 HELLO" },
            ),
            MaintenanceRow(
                "RTK 接入",
                if (config.rtk.transportMode == com.example.helmet.core.model.RtkTransportMode.HSL) {
                    "HSL UART2"
                } else {
                    "${config.rtk.directDevicePath} · ${config.rtk.directBaudRate} 波特"
                },
            ),
            MaintenanceRow(
                "服务器配置",
                if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) "未配置完整" else backendHost(config.backendBaseUrl),
                if (config.backendBaseUrl.isBlank() || config.backendBearerToken.isBlank()) UiTone.MUTED else UiTone.NORMAL,
            ),
            MaintenanceRow("配置版本", config.revision.toString()),
            MaintenanceRow("网络类型", networkTransportLabel(snapshot.networkTransports)),
            MaintenanceRow("网络接口", snapshot.networkInterface.takeUnless { it == "none" }.orEmpty().ifBlank { "无" }),
            MaintenanceRow("待上传轨迹", queues.tracks.toString(), queueTone(queues.tracks)),
            MaintenanceRow("待上传媒体", queues.media.toString(), queueTone(queues.media)),
            MaintenanceRow("待上传告警", queues.alerts.toString(), queueTone(queues.alerts)),
            MaintenanceRow("待同步通信", queues.communications.toString(), queueTone(queues.communications)),
            MaintenanceRow("硬件事件队列溢出", snapshot.hardwareEventQueueOverflowCount.toString(), queueTone(snapshot.hardwareEventQueueOverflowCount)),
            MaintenanceRow("定位队列溢出", snapshot.locationFixQueueOverflowCount.toString(), queueTone(snapshot.locationFixQueueOverflowCount)),
            MaintenanceRow("保留传感样本", snapshot.retainedSafetySampleCount.toString()),
        )
    }

    private fun recentErrorRows(events: List<HelmetEvent>): List<MaintenanceRow> = events
        .filter { it.severity in setOf(EventSeverity.MEDIUM, EventSeverity.HIGH, EventSeverity.CRITICAL) }
        .distinctBy(HelmetEvent::eventType)
        .take(6)
        .map { event ->
            MaintenanceRow(
                label = eventLabel(event.eventType),
                value = "已记录，${if (event.severity >= EventSeverity.HIGH) "需要处理" else "系统正在重试"}",
                tone = if (event.severity >= EventSeverity.HIGH) UiTone.ALARM else UiTone.ATTENTION,
            )
        }

    private fun alertUi(alert: SafetyAlertRecord): AlertUiState {
        val latitude = alert.latitude
        val longitude = alert.longitude
        val location = if (latitude != null && longitude != null && alert.locationFixType != "NO_FIX") {
            buildString {
                append(formatCoordinate(latitude))
                append(", ")
                append(formatCoordinate(longitude))
                alert.horizontalAccuracyMeters?.let { append(" · 精度 ${formatOneDecimal(it.toDouble())} 米") }
            }
        } else {
            "无有效定位"
        }
        return AlertUiState(
            alertId = alert.alertId,
            title = alarmTypeLabel(alert.alarmType),
            active = alert.active,
            simulated = alert.simulated,
            occurredAtEpochMillis = alert.occurredAtEpochMillis,
            location = location,
            delivery = deliveryLabel(alert.deliveryState),
            tone = when {
                alert.simulated -> UiTone.MUTED
                alert.active -> UiTone.ALARM
                else -> UiTone.NORMAL
            },
        )
    }

    private fun attentionSummary(
        queues: QueueUiState,
        network: UiStatus,
        backend: UiStatus,
        uart: UiStatus,
        location: UiStatus,
    ): String = when {
        queues.total > 0 -> "有 ${queues.total} 条数据等待上传或同步"
        network.tone != UiTone.NORMAL -> "网络离线，业务数据将保存在本机"
        backend.tone != UiTone.NORMAL -> "服务器连接需要确认"
        uart.tone != UiTone.NORMAL -> "UART 或外部模块尚未就绪"
        location.tone != UiTone.NORMAL -> "当前没有有效定位"
        else -> "部分外设尚未接入"
    }

    private fun ageMillis(now: Long, timestamp: Long): Long = when {
        timestamp <= 0 -> Long.MAX_VALUE
        now >= timestamp -> now - timestamp
        timestamp - now <= MAXIMUM_CLOCK_SKEW_MILLIS -> 0L
        else -> Long.MAX_VALUE
    }

    private fun isFailureState(state: String): Boolean =
        state.contains("FAIL", ignoreCase = true) || state.contains("ERROR", ignoreCase = true)

    private fun backendHost(raw: String): String = runCatching {
        URI(raw).host?.takeIf(String::isNotBlank) ?: "已配置"
    }.getOrDefault("已配置")

    private fun networkTransportLabel(raw: String): String {
        val labels = raw.split(',').mapNotNull { value ->
            when (value.trim().uppercase(Locale.ROOT)) {
                "WIFI" -> "Wi-Fi"
                "CELLULAR" -> "移动网络"
                "ETHERNET" -> "有线网络"
                "VPN" -> "VPN"
                "BLUETOOTH" -> "蓝牙网络"
                "OTHER" -> "其他网络"
                else -> null
            }
        }
        return labels.distinct().joinToString("、").ifBlank { "无" }
    }

    private fun fixQualityLabel(value: String): String = when (value) {
        "RTK_FIXED" -> "RTK 固定解"
        "RTK_FLOAT" -> "RTK 浮点解"
        "DGPS" -> "差分定位"
        "GNSS_3D" -> "卫星三维定位"
        "GNSS_2D" -> "卫星二维定位"
        "NETWORK" -> "网络定位"
        "UNVALIDATED" -> "未验证定位"
        else -> "普通定位"
    }

    private fun alarmTypeLabel(type: String): String = when (type) {
        "FALL" -> "跌落报警"
        "IMPACT" -> "撞击报警"
        "VIOLENT_SHAKE" -> "剧烈晃动报警"
        "INACTIVITY" -> "长时间静止报警"
        "NEAR_ELECTRIC" -> "近电报警"
        "HEIGHT_LIMIT" -> "高度风险报警"
        "SENSOR_FAULT" -> "传感器故障"
        "GEOFENCE" -> "电子围栏报警"
        "SOS" -> "紧急求助"
        else -> "安全报警"
    }

    private fun deliveryLabel(state: DeliveryState): String = when (state) {
        DeliveryState.PENDING -> "等待上传"
        DeliveryState.IN_FLIGHT -> "正在上传"
        DeliveryState.DELIVERED -> "已上传"
        DeliveryState.FAILED -> "上传失败，正在重试"
        DeliveryState.REJECTED -> "服务器拒绝"
    }

    private fun eventLabel(type: String): String = when {
        type == "DEVICE_STATUS_UPLOAD_REJECTED" -> "服务器拒绝设备状态"
        type == "DEVICE_STATUS_UPLOAD_RETRY_SCHEDULED" -> "服务器暂时不可达"
        type.contains("CAMERA", ignoreCase = true) || type.contains("PHOTO", ignoreCase = true) -> "摄像头操作失败"
        type.contains("VIDEO", ignoreCase = true) -> "录像操作失败"
        type.contains("VOICE", ignoreCase = true) || type.contains("AUDIO", ignoreCase = true) -> "音频操作失败"
        type.contains("HARDWARE", ignoreCase = true) || type.contains("UART", ignoreCase = true) -> "外部模块连接异常"
        type.contains("LOCATION", ignoreCase = true) || type.contains("RTK", ignoreCase = true) -> "定位功能异常"
        type.contains("UPLOAD", ignoreCase = true) -> "数据上传失败"
        else -> "运行异常记录"
    }

    private fun operationalStateChinese(state: HelmetOperationalState): String = when (state) {
        HelmetOperationalState.BOOTING -> "正在启动"
        HelmetOperationalState.SELF_TEST -> "正在检测"
        HelmetOperationalState.OFFLINE_READY -> "离线运行"
        HelmetOperationalState.IDLE -> "后台运行"
        HelmetOperationalState.CALLING -> "正在呼叫"
        HelmetOperationalState.IN_CALL -> "通话中"
        HelmetOperationalState.RECORDING -> "正在录像"
        HelmetOperationalState.SOS -> "紧急报警"
        HelmetOperationalState.FAULT -> "故障"
        HelmetOperationalState.SHUTTING_DOWN -> "正在停止"
    }

    private fun toneForState(state: HelmetOperationalState): UiTone = when (state) {
        HelmetOperationalState.FAULT, HelmetOperationalState.SOS -> UiTone.ALARM
        HelmetOperationalState.BOOTING, HelmetOperationalState.SELF_TEST, HelmetOperationalState.SHUTTING_DOWN -> UiTone.ATTENTION
        else -> UiTone.NORMAL
    }

    private fun queueTone(value: Number): UiTone = if (value.toLong() > 0) UiTone.ATTENTION else UiTone.NORMAL

    private fun formatOneDecimal(value: Double): String = String.format(Locale.CHINA, "%.1f", value)

    private fun formatCoordinate(value: Double): String = String.format(Locale.CHINA, "%.6f", value)
}
