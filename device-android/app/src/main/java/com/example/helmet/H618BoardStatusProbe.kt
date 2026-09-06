package com.example.helmet

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.hardware.input.InputManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.storage.StorageManager
import com.example.helmet.core.model.RuntimeConfig
import com.example.helmet.core.model.RuntimeSnapshot
import com.example.helmet.core.model.RtkTransportMode
import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.hardware.api.BoardAvailability
import com.example.helmet.hardware.api.BoardResourceStatus
import com.example.helmet.hardware.api.H618BoardProfile
import java.io.File

class H618BoardStatusProbe(context: Context) {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val storageManager = appContext.getSystemService(StorageManager::class.java)
    private val inputManager = appContext.getSystemService(InputManager::class.java)

    fun inspect(
        snapshot: RuntimeSnapshot,
        config: RuntimeConfig,
        nowEpochMillis: Long,
    ): List<BoardResourceStatus> {
        val cameraCount = runCatching { cameraManager.cameraIdList.size }.getOrDefault(snapshot.cameraCount)
        val audioInputs = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).size
        }.getOrDefault(0)
        val cellularValidated = runCatching {
            val network = connectivityManager.activeNetwork ?: return@runCatching false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return@runCatching false
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(false)
        val accelerometerPresent = runCatching {
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
        }.getOrDefault(false)
        val removableVolumes = runCatching {
            storageManager.storageVolumes.filter { it.isRemovable }
        }.getOrDefault(emptyList())
        val inputDeviceCount = runCatching { inputManager.inputDeviceIds.size }.getOrDefault(0)
        val externalFixFresh = snapshot.locationProvider == "external-rtk-uart" &&
            snapshot.locationHasPosition && snapshot.locationOccurredAtEpochMillis?.let {
                (nowEpochMillis - it).coerceAtLeast(0) <= RTK_STALE_MILLIS
            } == true

        return listOf(
            status(
                "peripheral_3v3_enable",
                BoardAvailability.NOT_CONFIGURED,
                "PG13 与 gpio203 的线路关系待确认，当前只读",
            ),
            status(
                "camera_power_enable",
                BoardAvailability.PIN_CONFLICT,
                "PI4 同时被表格定义为摄像头电源和 MMA8452 INT2",
            ),
            status(
                "camera_usb1",
                if (cameraCount > 0) BoardAvailability.AVAILABLE else BoardAvailability.WAITING_EXTERNAL,
                if (cameraCount > 0) "Camera2 枚举到 $cameraCount 个设备" else "Camera2 未枚举到摄像头",
                evidence = "CameraManager.cameraIdList",
            ),
            status(
                "audio_codec_i2c2",
                BoardAvailability.DRIVER_MISSING,
                "未确认 NAU88C2x codec，系统没有 /dev/i2c-2 映射证据",
            ),
            status(
                "android_audio",
                when {
                    !packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) ->
                        BoardAvailability.WAITING_EXTERNAL
                    audioInputs <= 0 -> BoardAvailability.DRIVER_MISSING
                    else -> BoardAvailability.AVAILABLE
                },
                when {
                    !packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) -> "系统未声明麦克风"
                    audioInputs <= 0 -> "AudioManager 未枚举输入设备"
                    else -> "Android 枚举到 $audioInputs 个音频输入；仍需非零 PCM 实测"
                },
                evidence = "PackageManager/AudioManager",
            ),
            status(
                "modem_usb2",
                if (cellularValidated) BoardAvailability.RUNNING else BoardAvailability.WAITING_EXTERNAL,
                if (cellularValidated) "已验证蜂窝网络能力" else "没有已验证蜂窝网络，未对模块执行复位",
                evidence = "ConnectivityManager",
            ),
            status(
                "mma8452_i2c0",
                BoardAvailability.PIN_CONFLICT,
                if (accelerometerPresent) {
                    "系统存在加速度计，但不能证明是 MMA8452；PI5/PI6 仍由 UART2 使用"
                } else {
                    "PI5/PI6 由 UART2 使用，系统未枚举加速度计"
                },
                evidence = "SensorManager and H618 live pin mapping",
            ),
            hslCapabilityStatus(
                resourceId = "hsl_mma8452",
                snapshot = snapshot,
                capability = HslCapability.MMA8452_ACCELEROMETER,
                label = "MMA8452",
                lastDataAtEpochMillis = snapshot.hardwareLastSensorSampleEpochMillis,
                nowEpochMillis = nowEpochMillis,
                requiresFreshData = true,
            ),
            rtkStatus(snapshot, config, externalFixFresh),
            storageStatus(
                resourceId = "sd1_bus",
                noVolumeState = BoardAvailability.DRIVER_MISSING,
                removableVolumes = removableVolumes.size,
                detailWhenMissing = "未发现 SD1 可移动卷；插入检测不能代替挂载结果",
            ),
            storageStatus(
                resourceId = "sd2_usb3",
                noVolumeState = BoardAvailability.WAITING_EXTERNAL,
                removableVolumes = removableVolumes.size,
                detailWhenMissing = "未发现 GL823K 可移动卷；等待真实读卡器",
            ),
            hslCapabilityStatus(
                resourceId = "hsl_physical_keys",
                snapshot = snapshot,
                capability = HslCapability.PHYSICAL_KEYS,
                label = "物理按键",
                additionalDetail = "系统有 $inputDeviceCount 个输入设备；七个 GPIO 的业务映射仍需硬件确认",
                lastDataAtEpochMillis = snapshot.hardwareLastKeyEventEpochMillis,
            ),
            hslCapabilityStatus(
                resourceId = "hsl_local_outputs",
                snapshot = snapshot,
                capability = HslCapability.LED_OUTPUT or HslCapability.VIBRATION_OUTPUT or
                    HslCapability.BUZZER_OUTPUT,
                label = "本地输出",
                additionalDetail = "LED 数量、GPIO、方向和有效电平仍需硬件确认",
                lastDataAtEpochMillis = snapshot.hardwareLastOutputConfirmedEpochMillis,
            ),
            hslStatus(snapshot, config),
        )
    }

    private fun rtkStatus(
        snapshot: RuntimeSnapshot,
        config: RuntimeConfig,
        externalFixFresh: Boolean,
    ): BoardResourceStatus {
        if (externalFixFresh) {
            return status(
                if (config.rtk.transportMode == RtkTransportMode.HSL) "hsl_rtk_bridge" else "rtk_uart4",
                BoardAvailability.RUNNING,
                "${config.rtk.transportMode.name} 收到有效外部 RTK 定位",
                evidence = snapshot.locationProvider,
                updatedAtEpochMillis = snapshot.locationOccurredAtEpochMillis,
            )
        }
        if (config.rtk.transportMode == RtkTransportMode.HSL) {
            return hslCapabilityStatus(
                resourceId = "hsl_rtk_bridge",
                snapshot = snapshot,
                capability = HslCapability.RTK_NMEA,
                label = "RTK HSL 转发",
                additionalDetail = "等待经过校验的 NMEA 定位",
            )
        }
        val uart = fixedDeviceNode("/dev/ttyAS4")
        return when {
            !uart.exists -> status(
                "rtk_uart4",
                BoardAvailability.DRIVER_MISSING,
                "UART4 设备节点缺失",
                evidence = "/dev/ttyAS4",
            )
            !uart.readable || !uart.writable -> status(
                "rtk_uart4",
                BoardAvailability.PERMISSION_DENIED,
                "UART4 节点存在但应用无读写权限",
                evidence = "/dev/ttyAS4",
            )
            else -> status(
                "rtk_uart4",
                when {
                    snapshot.rtkTransportConnected -> BoardAvailability.WAITING_EXTERNAL
                    snapshot.rtkTransportLastError != null -> BoardAvailability.FAULT
                    else -> BoardAvailability.NOT_CONFIGURED
                },
                when {
                    snapshot.rtkTransportConnected -> "UART4 已打开，等待有效 NMEA 定位"
                    snapshot.rtkTransportLastError != null -> "UART4 直连失败：${snapshot.rtkTransportLinkState}"
                    else -> "UART4 节点已映射，等待启用直连配置"
                },
                evidence = "/dev/ttyAS4 maps to PI13/PI14",
            )
        }
    }

    private fun storageStatus(
        resourceId: String,
        noVolumeState: BoardAvailability,
        removableVolumes: Int,
        detailWhenMissing: String,
    ): BoardResourceStatus = if (removableVolumes == 0) {
        status(resourceId, noVolumeState, detailWhenMissing, evidence = "StorageManager")
    } else {
        status(
            resourceId,
            BoardAvailability.NOT_CONFIGURED,
            "检测到 $removableVolumes 个可移动卷，但无法安全归属到 SD1 或 SD2",
            evidence = "StorageManager",
        )
    }

    private fun hslStatus(snapshot: RuntimeSnapshot, config: RuntimeConfig): BoardResourceStatus {
        val node = fixedDeviceNode(config.hardwareDevicePath)
        return when {
            config.simulatorEnabled || snapshot.hardwareMode == "SIMULATED" -> status(
                "hsl_uart2",
                BoardAvailability.NOT_CONFIGURED,
                "当前为调试模拟，不能作为 HSL 硬件证据",
            )
            !node.allowed -> status(
                "hsl_uart2",
                BoardAvailability.FAULT,
                "运行配置不是允许的固定串口路径",
            )
            !node.exists -> status(
                "hsl_uart2",
                BoardAvailability.DRIVER_MISSING,
                "HSL 串口节点缺失",
                evidence = config.hardwareDevicePath,
            )
            !node.readable || !node.writable -> status(
                "hsl_uart2",
                BoardAvailability.PERMISSION_DENIED,
                "HSL 串口存在但应用无读写权限",
                evidence = config.hardwareDevicePath,
            )
            snapshot.hardwareCompatibility in setOf(
                "CONTRACT_VERSION_MISMATCH",
                "REQUIRED_CAPABILITIES_MISSING",
            ) -> status(
                "hsl_uart2",
                BoardAvailability.PROTOCOL_INCOMPATIBLE,
                "HSL 模块契约不兼容：${snapshot.hardwareCompatibility}",
                evidence = "contract=${snapshot.hardwareContractVersion ?: "unknown"}",
            )
            snapshot.hardwareConnected -> status(
                "hsl_uart2",
                BoardAvailability.RUNNING,
                "HSL 心跳有效，${config.hardwareBaudRate} 波特",
                evidence = config.hardwareDevicePath,
            )
            snapshot.hardwareLastError != null -> status(
                "hsl_uart2",
                BoardAvailability.FAULT,
                "串口打开或协议链路失败",
                evidence = config.hardwareDevicePath,
            )
            else -> status(
                "hsl_uart2",
                BoardAvailability.WAITING_EXTERNAL,
                if (snapshot.hardwareCompatibility == "AWAITING_HELLO") {
                    "串口节点存在，等待 HSL HELLO 和心跳"
                } else {
                    "HSL 已握手，等待外部模块心跳"
                },
                evidence = config.hardwareDevicePath,
            )
        }
    }

    private fun hslCapabilityStatus(
        resourceId: String,
        snapshot: RuntimeSnapshot,
        capability: Long,
        label: String,
        additionalDetail: String? = null,
        lastDataAtEpochMillis: Long? = null,
        nowEpochMillis: Long? = null,
        requiresFreshData: Boolean = false,
    ): BoardResourceStatus = when {
        snapshot.hardwareCompatibility in setOf(
            "CONTRACT_VERSION_MISMATCH",
            "REQUIRED_CAPABILITIES_MISSING",
        ) -> status(
            resourceId,
            BoardAvailability.PROTOCOL_INCOMPATIBLE,
            "$label 的 HSL 契约不兼容",
            evidence = snapshot.hardwareContractVersion,
        )
        snapshot.hardwareCompatibility != "COMPATIBLE" -> status(
            resourceId,
            BoardAvailability.WAITING_EXTERNAL,
            "$label 等待 HSL HELLO 能力声明",
        )
        !snapshot.hardwareConnected -> status(
            resourceId,
            BoardAvailability.WAITING_EXTERNAL,
            "$label 已完成能力协商，等待有效心跳",
        )
        snapshot.hardwareCapabilityMask and capability != capability -> status(
            resourceId,
            BoardAvailability.NOT_CONFIGURED,
            "$label 未包含在模块能力声明中",
            evidence = "mask=0x${snapshot.hardwareCapabilityMask.toString(16)}",
        )
        requiresFreshData && lastDataAtEpochMillis == null -> status(
            resourceId,
            BoardAvailability.WAITING_EXTERNAL,
            "$label 能力已协商，等待有效数据",
            evidence = "contract=${snapshot.hardwareContractVersion}; firmware=${snapshot.hardwareFirmwareVersion}",
        )
        requiresFreshData && nowEpochMillis != null &&
            (nowEpochMillis - requireNotNull(lastDataAtEpochMillis)).coerceAtLeast(0L) > HSL_DATA_STALE_MILLIS -> status(
            resourceId,
            BoardAvailability.STALE,
            "$label 数据已过期",
            updatedAtEpochMillis = lastDataAtEpochMillis,
        )
        else -> status(
            resourceId,
            if (requiresFreshData) BoardAvailability.RUNNING else BoardAvailability.AVAILABLE,
            listOfNotNull(
                if (requiresFreshData) "$label 有效数据正在更新" else "$label 已通过 HSL 能力协商",
                lastDataAtEpochMillis?.let { "最近确认时间 $it" },
                additionalDetail,
            ).joinToString("；"),
            evidence = "contract=${snapshot.hardwareContractVersion}; firmware=${snapshot.hardwareFirmwareVersion}",
            updatedAtEpochMillis = lastDataAtEpochMillis,
        )
    }

    private fun fixedDeviceNode(path: String): FixedDeviceNode {
        val allowed = FIXED_SERIAL_PATH.matches(path)
        if (!allowed) return FixedDeviceNode(allowed = false, exists = false, readable = false, writable = false)
        val file = File(path)
        return FixedDeviceNode(
            allowed = true,
            exists = file.exists(),
            readable = file.canRead(),
            writable = file.canWrite(),
        )
    }

    private fun status(
        resourceId: String,
        availability: BoardAvailability,
        detail: String,
        evidence: String? = null,
        updatedAtEpochMillis: Long? = null,
    ): BoardResourceStatus {
        check(
            H618BoardProfile.profile.resources.any { it.id == resourceId } ||
                resourceId in SYNTHETIC_RESOURCE_IDS,
        ) { "unknown H618 board status resource: $resourceId" }
        return BoardResourceStatus(resourceId, availability, detail, evidence, updatedAtEpochMillis)
    }

    private data class FixedDeviceNode(
        val allowed: Boolean,
        val exists: Boolean,
        val readable: Boolean,
        val writable: Boolean,
    )

    companion object {
        private const val RTK_STALE_MILLIS = 15_000L
        private const val HSL_DATA_STALE_MILLIS = 15_000L
        private val FIXED_SERIAL_PATH = Regex("/dev/tty(?:AS|S|USB|ACM)[0-9]{1,3}")
        private val SYNTHETIC_RESOURCE_IDS = emptySet<String>()
    }
}
