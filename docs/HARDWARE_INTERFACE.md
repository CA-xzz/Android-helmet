# H618 硬件接口

目标设备：Android 12、API 31，ADB 序列号 `2c001031774186e21d3`。

完整引脚表见 `HARDWARE_PIN_MAPPING.md`，量产接入步骤见 `HARDWARE_BRINGUP_CHECKLIST.md`。
版本化交接文件见 `hardware-contract/H618_APP_HARDWARE_CONTRACT.md` 和 `hardware-contract/h618-helmet-v3.json`。

## 2026-08-19 H618 适配结论

| 项目 | 结论 |
|---|---|
| HSL | `/dev/ttyAS2` 对应 UART2、PI5/PI6。生产配置固定为 115200、8N1；App 已实现 HELLO 契约握手、能力校验、心跳和重连。 |
| RTK | 默认由 HSL 转发。App 同时支持独立 `/dev/ttyAS4` UART4 原始 NMEA/RTCM3，路径固定、波特率受限；硬件确认前不选择直连模式。 |
| 冲突 | PI4 同时定义为摄像头电源和 MMA8452 INT2；PI5/PI6 当前属于 UART2；PG8/PG9 同时处于 UART1 pinmux。冲突控制全部禁用。 |
| GPIO | PG13、LED、按键、4G 控制和外设电源缺少完整方向、电平或时序证据。现有 `gpio203` 保持原值，未解释为 PG13。 |
| Camera | Camera2 路径可用，CameraService 枚举 0 个摄像头，状态为等待外设。 |
| Audio | 目标 codec 型号不明确，I2C2 和麦克风输入缺失，状态为驱动缺失或等待外设。 |
| Storage | SDC0 的 PF0 至 PF5 pinmux 与表格相符；没有可移动块设备或 GL823K 卷。 |
| UI | 主界面显示契约版本、模块固件、能力列表、RTK 模式和逐项状态。节点存在不会显示为运行，release 设备检测页只提供重新检测。 |

板型配置位于 `H618BoardProfile.kt`。所有直接 GPIO 输出均使用 `BLOCKED_PENDING_CONFIRMATION`，因此应用不会对未确认电源、复位、按键或 LED 执行操作。

## 2026-08-13 实测

| 项目 | 结果 |
|---|---|
| 设备 | `QUAD-CORE H618 p1`，Android 12，SDK 31 |
| UART | `/dev/ttyAS2` 存在，权限 `crw-rw-rw-`；生产服务和 PTY 自测链路通过 |
| 摄像头 | 系统声明相机特性，但 CameraService 枚举 0 个设备 |
| 音频 | 未声明 `android.hardware.microphone`；采集 16000 帧全零；播放消费 22050 帧 |
| 定位 | 只有网络定位特性，没有 GNSS provider；UART NMEA 软件闭环通过 |
| 电池 | `present=false`，`level=0` |
| 服务 | `HelmetService` 前台运行，无目标应用崩溃日志 |

| 功能 | Android 或用户态接口 | 验收要求 |
|---|---|---|
| 摄像头 | Camera2、UVC | 枚举、拍照、1080p 录像、WebRTC 视频 |
| 麦克风和扬声器 | AudioRecord、AudioTrack | 非零 PCM、回放、语音和通话 |
| 定位 | Location、UART NMEA | 定位质量、轨迹、围栏 |
| RTK | NMEA、RTCM3、NTRIP | Fix 状态、差分龄期和断线恢复 |
| LoRa 对讲 | HSL UART 控制和状态 | 厂商语音模块实测 |
| IMU、近电、高度 | HSL 安全样本 | 现场标定、连续确认和恢复 |
| 电池 | Android Battery API 或电量计 | 20%、10%、5% 提示 |
| 按键、LED、振动 | HSL UART、Android 振动 | 事件去重、确认和输出结果 |

## UART

`native-hardware-service` 在独立进程持有两个隔离的串口运行时，通过私有 HardwareProvider 和 AIDL 向主进程传递数据。release 的 HSL 固定为 `/dev/ttyAS2`，独立 RTK 固定为 `/dev/ttyAS4`；debug 额外允许严格的 `/dev/pts/<数字>` 测试节点。

HSL 服务传递版本化帧；RTK 服务只传递原始字节。两者使用独立文件描述符、线程、状态和重连逻辑。HSL 解码、HELLO、可靠确认、事件去重、持久序号和模块重启处理位于 Kotlin 层。协议见 `EXTERNAL_MODULE_PROTOCOL.md`。

## 板测

```sh
adb -s 2c001031774186e21d3 shell ls -l /dev/ttyAS2
./tools/run-board-connected-software-tests.sh \
  --adb-serial 2c001031774186e21d3 \
  --hardware-device-path /dev/ttyAS2 \
  --hardware-baud-rate 115200
./tools/run-board-audio-pcm-probe.sh --adb-serial 2c001031774186e21d3
./tools/run-board-geofence-uart-e2e.sh --adb-serial 2c001031774186e21d3
./tools/run-board-durable-offline-recovery.sh --adb-serial 2c001031774186e21d3
./tools/run-board-automatic-startup-recovery.sh --adb-serial 2c001031774186e21d3
```

测试前确认 UART 电平、波特率、地线和端口复用。没有对应外设或采集数据无效时记录为外部依赖，不修改内核或设备树，不写成通过。
