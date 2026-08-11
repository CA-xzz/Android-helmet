# 开发板硬件接口

## 当前开发板

- Android 12，API 31。
- ADB 序列号：`2c001031774186e21d3`。
- 已验证用户态 UART 设备节点：`/dev/ttyAS2`。
- 当前板卡未提供可完成验收的摄像头、GNSS/RTK、LoRa 语音、IMU、近电、高度和电池模块。
- 当前音频播放路径可写入 PCM，采集端返回全零，不能作为麦克风证据。

## 接口表

| 功能 | 首选接口 | Android 入口 | 当前状态 |
|---|---|---|---|
| 摄像头 | USB UVC 或 Camera2 可见设备 | `Camera2MediaController` | 待硬件 |
| 麦克风/扬声器 | Android Audio API 或 USB Audio | `AudioRecord`、`AudioTrack` | 待可用采集端和声学测试 |
| 普通定位 | Android Location 或 USB/UART NMEA | `AndroidLocationController`、`NmeaParser` | 软件完成待模块 |
| RTK | USB/UART NMEA + RTCM3 | `ExternalRtkFixAssembler`、`RtkCorrectionController` | 软件完成待模块和差分服务 |
| 4G/5G | Android 网络栈 | `AndroidNetworkMonitor` | 待蜂窝模块和 SIM |
| LoRa 语音 | 厂商串口或 USB 控制接口 | `LocalIntercomController` | 控制协议完成待真实语音模块 |
| IMU | Android Sensor、USB、UART 或 I²C | `SafetySampleProcessor`、`SafetyDetectionEngine` | 运行时判断完成待传感器 |
| 近电 | UART、I²C 或厂商 SDK | `SafetySampleProcessor`、`SafetyDetectionEngine` | 运行时基线和分级完成待标定 |
| 高度 | UART、I²C 或厂商 SDK | `SafetySampleProcessor`、`SafetyDetectionEngine` | 运行时阈值和滞回完成待传感器 |
| 电池和电压 | Android Battery API 或外部电量计 | `BatteryStatusReader` | 待电池硬件 |
| 按键、LED、振动 | UART、GPIO、I²C 或厂商 SDK | `HardwareGateway`、`HslOutputPayloadCodec`、`LocalFeedbackController` | 输出请求和 Android 振动完成待接线 |

## UART 服务

`native-hardware-service` 在独立进程中打开白名单设备节点，通过 AIDL 向主应用传递字节流。原生层只负责打开、配置、读写和关闭串口，协议解析位于 Kotlin 模块。

开发板联调步骤：

```sh
adb -s 2c001031774186e21d3 shell ls -l /dev/ttyAS2
HELMET_ADB_SERIAL=2c001031774186e21d3 ./tools/test-android.sh connected
```

实际接线前必须确认 UART 电平、波特率、地线和端口复用。不得通过本工程修改内核或设备树来启用端口。

## USB

UVC、USB Audio、GNSS 和串口转换器应先确认 Android 枚举结果，再检查应用权限和标准 API 可见性。记录供应商 ID、产品 ID、端点和权限流程。

## I²C 和 GPIO

仅在现有开发固件已暴露可访问设备节点时使用。JNI 层必须限制节点白名单、读写长度和超时。若节点不存在或权限不足，将其记录为外部依赖，不修改系统软件。

## 实机验收记录

每个模块至少记录：

- 型号、固件版本和接线。
- Android 枚举或设备节点。
- 采样率、单位、时间戳和质量字段。
- 断开、重连、超时和数据损坏行为。
- 对应需求的日志、照片或后台记录。
