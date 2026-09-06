package com.example.helmet

import com.example.helmet.core.model.DeliveryState
import com.example.helmet.core.model.EventSeverity
import com.example.helmet.core.model.HelmetEvent
import com.example.helmet.core.model.HelmetOperationalState
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RuntimeSnapshot
import com.example.helmet.core.model.SafetyAlertRecord
import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.hardware.api.BoardAvailability
import com.example.helmet.hardware.api.BoardResourceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorUiMapperTest {
    @Test
    fun healthyProductionEvidenceMapsToNormalOverview() {
        val state = map()

        assertEquals("正常", state.overall.label)
        assertEquals(UiTone.NORMAL, state.overall.tone)
        assertEquals("运行中", state.service.label)
        assertEquals("已连接", state.uart.label)
        assertEquals("有效", state.location.label)
        assertTrue(state.sensors.all { it.tone == UiTone.NORMAL })
    }

    @Test
    fun realActiveAlarmHasHighestOverallPriority() {
        val state = map(alerts = listOf(alert(active = true, alarmType = "FALL")))

        assertEquals("报警", state.overall.label)
        assertEquals(UiTone.ALARM, state.overall.tone)
        assertEquals(listOf("跌落报警"), state.activeAlerts.map(AlertUiState::title))
    }

    @Test
    fun missingPeripheralsAreAttentionNotFault() {
        val state = map(
            snapshot = healthySnapshot().copy(
                hardwareConnected = false,
                hardwareLinkState = "DISCONNECTED",
                cameraCount = 0,
                locationHasPosition = false,
                locationOccurredAtEpochMillis = null,
            ),
            samples = emptyList(),
            capabilities = PlatformCapabilities(
                microphoneDeclared = false,
                microphonePermissionGranted = false,
                cameraPermissionGranted = false,
                locationProviderAvailable = false,
                gnssProviderAvailable = false,
                locationPermissionGranted = false,
                vibratorAvailable = false,
                systemImuAvailable = false,
            ),
        )

        assertEquals("注意", state.overall.label)
        assertEquals(UiTone.ATTENTION, state.overall.tone)
        assertEquals("等待外设", state.uart.label)
        assertEquals("等待外设", state.sensors.first { it.title == "摄像头" }.label)
        assertFalse(state.features.any { it.availability == FeatureAvailability.FAULT })
    }

    @Test
    fun explicitUartFailureIsRedButIsNotMislabelledAsSafetyAlarm() {
        val state = map(
            snapshot = healthySnapshot().copy(
                hardwareConnected = false,
                hardwareLinkState = "OPEN_FAILED",
                hardwareLastError = "java.io.IOException",
            ),
        )

        assertEquals("注意", state.overall.label)
        assertEquals(UiTone.ALARM, state.overall.tone)
        assertEquals("连接故障", state.uart.label)
        assertTrue(state.activeAlerts.isEmpty())
    }

    @Test
    fun smallClockSamplingSkewDoesNotMakeRunningServiceTimeout() {
        val state = map(
            snapshot = healthySnapshot().copy(statusUpdatedAtEpochMillis = NOW + 5_000L),
        )

        assertEquals("运行中", state.service.label)
    }

    @Test
    fun staleLocationNeverDisplaysAsCurrentNormalPosition() {
        val state = map(
            snapshot = healthySnapshot().copy(
                locationOccurredAtEpochMillis = NOW - 15_001L,
                locationLatitude = 31.2,
                locationLongitude = 121.5,
            ),
        )

        assertEquals("不可用", state.location.label)
        assertEquals(UiTone.ATTENTION, state.location.tone)
        assertTrue(state.location.detail.contains("过期"))
    }

    @Test
    fun networkOfflineAndPendingQueuesRemainOperationalAttention() {
        val state = map(
            snapshot = healthySnapshot().copy(
                networkAvailable = false,
                networkState = "UNAVAILABLE",
                pendingTrackCount = 2,
                pendingMediaCount = 3,
                pendingSafetyAlertCount = 1,
                pendingCallSyncCount = 4,
                pendingBroadcastReceiptCount = 2,
            ),
        )

        assertEquals("离线", state.network.label)
        assertEquals(12, state.queues.total)
        assertEquals("注意", state.overall.label)
        assertTrue(state.features.any { it.availability == FeatureAvailability.OFFLINE_AVAILABLE })
    }

    @Test
    fun newestBackendFailureOverridesOlderSuccess() {
        val events = listOf(
            event("DEVICE_STATUS_UPLOAD_RETRY_SCHEDULED", NOW - 1_000L, EventSeverity.MEDIUM),
            event("DEVICE_STATUS_UPLOAD_COMPLETED", NOW - 2_000L),
        )
        val state = map(events = events)

        assertEquals("不可达", state.backend.label)
        assertEquals(UiTone.ATTENTION, state.backend.tone)
    }

    @Test
    fun resolvedAlertReplacesEarlierActiveRecordForSameEpisode() {
        val cleared = alert(active = false, alarmType = "NEAR_ELECTRIC", messageId = "clear")
        val active = alert(active = true, alarmType = "NEAR_ELECTRIC", messageId = "active")
            .copy(occurredAtEpochMillis = NOW - 1_000L)
        val state = map(alerts = listOf(cleared, active))

        assertTrue(state.activeAlerts.isEmpty())
        assertEquals(1, state.recentAlerts.size)
        assertFalse(state.recentAlerts.single().active)
        assertEquals("正常", state.overall.label)
    }

    @Test
    fun simulatedAlarmIsNeverPresentedAsRealHardwareAlarm() {
        val state = map(alerts = listOf(alert(active = true, alarmType = "HEIGHT_LIMIT", simulated = true)))

        assertTrue(state.activeAlerts.isEmpty())
        assertFalse(state.overall.label == "报警")
        assertEquals(UiTone.MUTED, state.recentAlerts.single().tone)
        assertEquals("调试模拟", state.riskStatuses.first { it.title == "高度风险" }.label)
    }

    @Test
    fun staleAndSimulatedSensorSamplesDoNotBecomeRealEvidence() {
        val simulated = sample(simulated = true)
        val state = map(samples = listOf(simulated))

        listOf("IMU", "电场传感器", "高度 / 气压").forEach { title ->
            assertEquals("仅有调试数据", state.sensors.first { it.title == title }.label)
        }
    }

    @Test
    fun networkLocationProviderDoesNotBecomeGnssHardwareEvidence() {
        val state = map(
            snapshot = healthySnapshot().copy(
                hardwareConnected = false,
                hardwareLinkState = "DISCONNECTED",
                locationHasPosition = false,
                locationOccurredAtEpochMillis = null,
            ),
            capabilities = healthyCapabilities().copy(
                locationProviderAvailable = true,
                gnssProviderAvailable = false,
            ),
        )

        assertEquals("等待外设", state.sensors.first { it.title == "GNSS / RTK" }.label)
    }

    @Test
    fun imuFaultBitIsSeparatedFromMissingHardware() {
        val state = map(
            alerts = listOf(alert(active = true, alarmType = "SENSOR_FAULT").copy(sensorFaults = 0x0001)),
        )

        assertEquals("故障", state.sensors.first { it.title == "IMU" }.label)
        assertEquals(
            FeatureAvailability.FAULT,
            state.features.first { it.title == "跌落撞击报警" }.availability,
        )
    }

    @Test
    fun boardStatusUsesExplicitProductionStatesWithoutTreatingNodeExistenceAsSuccess() {
        val state = map(
            capabilities = healthyCapabilities().copy(
                boardStatuses = listOf(
                    BoardResourceStatus(
                        "rtk_uart4",
                        BoardAvailability.NOT_CONFIGURED,
                        "UART4 节点已映射，接收机波特率待确认",
                        evidence = "/dev/ttyAS4",
                    ),
                    BoardResourceStatus(
                        "camera_power_enable",
                        BoardAvailability.PIN_CONFLICT,
                        "PI4 定义冲突",
                    ),
                    BoardResourceStatus(
                        "sd2_usb3",
                        BoardAvailability.WAITING_EXTERNAL,
                        "等待读卡器",
                    ),
                ),
            ),
        )

        assertEquals(
            "未配置",
            state.deviceChecks.first { it.title == "RTK UART4" }.label,
        )
        assertEquals(
            "引脚冲突",
            state.deviceChecks.first { it.title == "摄像头电源" }.label,
        )
        assertEquals(
            "等待外设",
            state.deviceChecks.first { it.title == "SD2" }.label,
        )
        assertEquals(UiTone.ALARM, state.overall.tone)
        assertTrue(state.activeAlerts.isEmpty())
    }

    private fun map(
        snapshot: RuntimeSnapshot = healthySnapshot(),
        alerts: List<SafetyAlertRecord> = emptyList(),
        samples: List<SafetySensorTelemetry> = listOf(sample()),
        events: List<HelmetEvent> = listOf(event("DEVICE_STATUS_UPLOAD_COMPLETED", NOW - 1_000L)),
        capabilities: PlatformCapabilities = healthyCapabilities(),
    ): OperatorUiState = OperatorUiMapper.map(
        OperatorUiInput(
            snapshot = snapshot,
            config = RuntimeConfig(
                backendBaseUrl = "https://helmet.example.test",
                backendBearerToken = "opaque-token",
                hardwareDevicePath = "/dev/ttyAS2",
                hardwareBaudRate = 115_200,
            ),
            recentAlerts = alerts,
            recentSamples = samples,
            recentEvents = events,
            capabilities = capabilities,
            nowEpochMillis = NOW,
        ),
    )

    private fun healthySnapshot() = RuntimeSnapshot(
        deviceId = "helmet-001",
        state = HelmetOperationalState.IDLE,
        statusUpdatedAtEpochMillis = NOW,
        networkAvailable = true,
        networkState = "VALIDATED",
        networkTransports = "WIFI",
        networkInterface = "wlan0",
        appVersion = "0.3.0",
        androidVersion = "12 (API 31)",
        configRevision = 8,
        hardwareMode = "UART",
        hardwareConnected = true,
        hardwareLinkState = "CONNECTED",
        cameraCount = 1,
        cameraSummary = "0 photo=1920x1080 video=1920x1080",
        locationState = "TRACKING",
        locationProvider = "external-rtk",
        locationFixQuality = "RTK_FIXED",
        locationHasPosition = true,
        locationLatitude = 31.2,
        locationLongitude = 121.5,
        locationHorizontalAccuracyMeters = 0.4f,
        locationOccurredAtEpochMillis = NOW,
        rtkState = "RUNNING",
        localIntercomState = "JOINED",
    )

    private fun healthyCapabilities() = PlatformCapabilities(
        microphoneDeclared = true,
        microphonePermissionGranted = true,
        cameraPermissionGranted = true,
        locationProviderAvailable = true,
        gnssProviderAvailable = true,
        locationPermissionGranted = true,
        vibratorAvailable = true,
        systemImuAvailable = false,
    )

    private fun sample(simulated: Boolean = false) = SafetySensorTelemetry(
        sampleId = if (simulated) "sample-sim" else "sample-real",
        deviceId = "helmet-001",
        sampleReference = if (simulated) 2 else 1,
        monotonicMillis = 100,
        validFlags = 0x0017,
        accelerationXMilliG = 0,
        accelerationYMilliG = 0,
        accelerationZMilliG = 1_000,
        gyroXMilliDegreesPerSecond = 0,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
        electricFieldMilliVolts = 100,
        pressurePascals = 101_325,
        temperatureCentiCelsius = 2_500,
        altitudeMillimetres = 0,
        simulated = simulated,
        recordedAtEpochMillis = NOW,
        thresholdConfigVersion = 1,
        thresholdConfigFingerprint = "0".repeat(64),
    )

    private fun alert(
        active: Boolean,
        alarmType: String,
        simulated: Boolean = false,
        messageId: String = "message-1",
    ) = SafetyAlertRecord(
        messageId = messageId,
        alertId = "episode-1",
        deviceId = "helmet-001",
        alarmType = alarmType,
        severity = EventSeverity.HIGH,
        active = active,
        configVersion = 1,
        sampleReference = 1,
        monotonicMillis = 100,
        occurredAtEpochMillis = NOW,
        localActions = 0,
        sensorFaults = 0,
        simulated = simulated,
        sensorSnapshotJson = "{}",
        latitude = null,
        longitude = null,
        horizontalAccuracyMeters = null,
        locationFixType = "NO_FIX",
        evidenceAssetId = null,
        deliveryState = DeliveryState.PENDING,
        attemptCount = 0,
    )

    private fun event(
        type: String,
        time: Long,
        severity: EventSeverity = EventSeverity.INFO,
    ) = HelmetEvent(
        messageId = "$type-$time",
        eventType = type,
        severity = severity,
        payloadJson = "{}",
        occurredAtEpochMillis = time,
    )

    companion object {
        private const val NOW = 1_800_000_000_000L
    }
}
