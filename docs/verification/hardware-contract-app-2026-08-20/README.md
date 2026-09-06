# Android App 硬件契约验收记录

验证日期：2026-08-20。

## 范围

- 仅验证 Android App、App 内独立硬件进程、测试工具和对接文档。
- 未修改硬件、设备树、BSP、系统镜像或启动脚本。
- 开发板序列号：`2c001031774186e21d3`。
- 开发板：`QUAD-CORE H618 p1`，Android 12、API 31。

## 契约结果

- 契约版本：`1.0.0`。
- `HELMET_v3_footpin.xlsx` 第 2 至 45 行共 44 行全部且仅映射一次。
- Kotlin 板型配置是唯一数据源，生成 [JSON](../../hardware-contract/h618-helmet-v3.json) 和 [Markdown 交接文件](../../hardware-contract/H618_APP_HARDWARE_CONTRACT.md)。
- 9 个硬件确认项在确认前保持禁用，不能记为真实硬件验收通过。
- HSL 固定为 `/dev/ttyAS2`、115200、8N1，必须完成 `HELLO 0x02` 和心跳。
- RTK 默认使用 HSL，同时支持受控配置选择独立 `/dev/ttyAS4`；两条串口路径使用独立 AIDL、文件描述符、线程、状态和重连流程。

## 自动化结果

- `./tools/test-android.sh`：通过，debug/release 单元测试和 lint 完成，1815 个 Gradle 任务无失败。
- `./tools/build-android.sh`：通过，debug/release 构建和 release R8 检查完成，952 个 Gradle 任务无失败。
- `git diff --check` 和契约 JSON 解析：通过。
- H618 联机软件回归：174 项通过。

| 测试包 | 通过数 |
|---|---:|
| data-local | 120 |
| feature-camera | 6 |
| feature-connectivity | 1 |
| feature-location | 1 |
| safety-detection | 3 |
| webrtc-runtime | 2 |
| service-runtime-offline | 25 |
| app-manifest-policy | 10 |
| app-real-hardware-mode-preparation | 1 |
| app-hardware-provider-safe | 5 |

HSL 模拟端已覆盖 HELLO、心跳、按键可靠确认、MMA8452、LED/振动/蜂鸣输出、NMEA 和 RTCM。独立 RTK 测试覆盖原始 NMEA 分片和 RTCM 输出。双端口测试确认 HSL 与 UART4 同时运行时事件和发送方向不串线。模拟结果只作为软件协议证据。

## 开发板结果

- `/dev/ttyAS2` 和 `/dev/ttyAS4` 节点存在，但节点存在不会判定外设可用。
- Camera2 枚举为 0。摄像头、麦克风、HSL 模块、MMA8452 和 RTK 接收机均未接入。
- UI 显示 UART、摄像头、麦克风、GNSS/RTK、IMU、电场和气压为“等待外设”。维护页显示 `App 1.0.0 · 模块 等待 HELLO`、固件未识别、能力等待 HELLO、RTK 模式 `HSL UART2`。
- debug APK 通过 `install -r` 更新。主应用未卸载，数据未清除，运行配置为 `/dev/ttyAS2`、115200、模拟器关闭。
- 普通重启前后 boot ID 从 `9985e28c-85c2-42d4-a08f-0a53754abb97` 变为 `def5f85c-62ca-4339-9901-1f7f68876b2c`。主进程从 PID 10659 恢复为 PID 1654，独立硬件进程从 PID 10944 恢复为 PID 1847，前台服务保持 `isForeground=true`。
- App 数据文件计数重启前后均为 44。

## APK

- debug APK：`e3581a31ae90787650c1ad94a6548babfdd69234a471daeb3938bd49e80dbf1e`，66 MiB。
- release unsigned APK：`df44ee506dee28513274b8b33fabb146bdaa674e52f45e1f263021d05257fc78`，56 MiB。

## 完成边界

当前结果证明 App 的接口契约、协议、双 RTK 路径、安全降级、重连、进程恢复和重启恢复可工作。真实摄像头、音频、4G、MMA8452、RTK、按键和 LED 到货前，不能标记整机硬件验收通过。硬件方按交接文件关闭确认项并满足端点、HELLO、能力位和有效数据判定后，App 无需修改源码，只需选择 RTK 模式并执行实物验收。
