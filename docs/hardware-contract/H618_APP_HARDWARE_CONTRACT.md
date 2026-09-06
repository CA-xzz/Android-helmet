# H618 App 硬件对接契约

- 板型：`h618-helmet-v3`
- 契约版本：`1.0.0`
- 原始清单行：`2-45`
- App 边界：Android 标准接口、HSL UART2 和可选独立 RTK UART4。
- App 不直接切换 GPIO、I2C、I2S、SD 或 USB 电源。

## 接口映射

| 行 | 资源 | 引脚 | App 接口 | 能力 | 必需 | 当前策略 | 验收依据 |
|---|---|---|---|---|---|---|---|
| 2 | debug_usb0 | 无 | BOOTLOADER_DIAGNOSTIC: usb://usb0 | FIRMWARE_FLASH | 否 | 启用/KERNEL_DRIVER | BSP driver binding and Android-facing service availability |
| 3, 4 | debug_uart0 | PH0, PH1 | BOOTLOADER_DIAGNOSTIC: /dev/ttyAS0 | DEBUG_CONSOLE | 否 | 启用/KERNEL_DRIVER | BSP driver binding and Android-facing service availability |
| 5 | peripheral_3v3_enable | PG13 | BSP_MANAGED: kernel://gpio/PG13 | PERIPHERAL_POWER | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 6 | camera_usb1 | 无 | ANDROID_FRAMEWORK: android://camera/usb1 | CAMERA | 是 | 启用/ANDROID_SYSTEM_SERVICE | Android API enumeration and successful operation |
| 7 | camera_power_enable | PI4 | BSP_MANAGED: kernel://gpio/PI4 | CAMERA | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 8 | sd2_usb3 | 无 | ANDROID_FRAMEWORK: android://storage/usb3 | REMOVABLE_STORAGE | 是 | 启用/ANDROID_SYSTEM_SERVICE | Android API enumeration and successful operation |
| 9 | sd2_insert_detect | PG12 | BSP_MANAGED: kernel://gpio/PG12 | REMOVABLE_STORAGE | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 10 | sd2_power_enable | PH10 | BSP_MANAGED: kernel://gpio/PH10 | REMOVABLE_STORAGE | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 11 | sd1_reset_power | PI16 | BSP_MANAGED: kernel://gpio/PI16 | REMOVABLE_STORAGE | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 12 | sd1_insert_detect | PF6 | BSP_MANAGED: kernel://gpio/PF6 | REMOVABLE_STORAGE | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 13, 14, 15, 16, 17, 18 | sd1_bus | PF0, PF1, PF2, PF3, PF4, PF5 | BSP_MANAGED: android://storage/sd1, kernel://sdmmc@4020000 | REMOVABLE_STORAGE | 是 | 启用/KERNEL_DRIVER | BSP driver binding and Android-facing service availability |
| 19, 20 | audio_codec_i2c2 | PH2, PH3 | BSP_MANAGED: kernel://i2c2 | AUDIO | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 21 | audio_codec_power | PH4 | BSP_MANAGED: kernel://gpio/PH4 | AUDIO | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 22, 23, 24, 25, 26 | audio_i2s3 | PH5, PH6, PH7, PH8, PH9 | BSP_MANAGED: kernel://i2s3 | AUDIO | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 27 | modem_wakeup | PG8 | BSP_MANAGED: kernel://gpio/PG8 | CELLULAR_NETWORK | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 28 | modem_pwrkey | PG9 | BSP_MANAGED: kernel://gpio/PG9 | CELLULAR_NETWORK | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 29 | modem_reset | PG10 | BSP_MANAGED: kernel://gpio/PG10 | CELLULAR_NETWORK | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 30, 31 | modem_uart1 | PG6, PG7, PG8, PG9 | BSP_MANAGED: /dev/ttyAS1 | CELLULAR_NETWORK | 否 | 启用/KERNEL_DRIVER | BSP driver binding and Android-facing service availability |
| 32 | modem_usb2 | 无 | ANDROID_FRAMEWORK: android://network/usb2 | CELLULAR_NETWORK | 是 | 启用/ANDROID_SYSTEM_SERVICE | Android API enumeration and successful operation |
| 33 | mma8452_power | PI3 | BSP_MANAGED: kernel://gpio/PI3 | MMA8452_ACCELEROMETER | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 34 | mma8452_int2 | PI4 | BSP_MANAGED: kernel://gpio/PI4 | MMA8452_ACCELEROMETER | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 35, 36 | mma8452_i2c0 | PI5, PI6 | BSP_MANAGED: android://sensor/accelerometer, kernel://i2c0 | MMA8452_ACCELEROMETER | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 37, 38 | rtk_uart4 | PI13, PI14 | DIRECT_RTK_UART4: /dev/ttyAS4 | RTK_CORRECTION, RTK_NMEA | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 39 | led_button_gpio_1 | PI1 | BSP_MANAGED: kernel://gpio/PI1 | LED_OUTPUT, PHYSICAL_KEYS | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 40 | led_button_gpio_2 | PI0 | BSP_MANAGED: kernel://gpio/PI0 | LED_OUTPUT, PHYSICAL_KEYS | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 41 | led_button_gpio_3 | PG17 | BSP_MANAGED: kernel://gpio/PG17 | LED_OUTPUT, PHYSICAL_KEYS | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 42 | led_button_gpio_4 | PG19 | BSP_MANAGED: kernel://gpio/PG19 | LED_OUTPUT, PHYSICAL_KEYS | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 43 | led_button_gpio_5 | PG18 | BSP_MANAGED: kernel://gpio/PG18 | LED_OUTPUT, PHYSICAL_KEYS | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 44 | led_button_gpio_6 | PG16 | BSP_MANAGED: kernel://gpio/PG16 | LED_OUTPUT, PHYSICAL_KEYS | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| 45 | led_button_gpio_7 | PG15 | BSP_MANAGED: kernel://gpio/PG15 | LED_OUTPUT, PHYSICAL_KEYS | 否 | 禁用/BLOCKED_PENDING_CONFIRMATION | hardware confirmation and BSP-owned safe control |
| App | android_audio | 无 | ANDROID_FRAMEWORK: android://audio/input, android://audio/output | AUDIO | 是 | 启用/ANDROID_SYSTEM_SERVICE | Android API enumeration and successful operation |
| App | hsl_local_outputs | 无 | HSL_UART2: android://hsl/outputs | BUZZER_OUTPUT, LED_OUTPUT, VIBRATION_OUTPUT | 是 | 启用/HARDWARE_SERVICE | compatible HSL HELLO, heartbeat, and capability-specific data |
| App | hsl_mma8452 | 无 | HSL_UART2: android://hsl/mma8452 | MMA8452_ACCELEROMETER | 是 | 启用/HARDWARE_SERVICE | compatible HSL HELLO, heartbeat, and capability-specific data |
| App | hsl_physical_keys | 无 | HSL_UART2: android://hsl/keys | PHYSICAL_KEYS | 是 | 启用/HARDWARE_SERVICE | compatible HSL HELLO, heartbeat, and capability-specific data |
| App | hsl_rtk_bridge | 无 | HSL_UART2: android://hsl/rtk | RTK_CORRECTION, RTK_NMEA | 否 | 启用/HARDWARE_SERVICE | compatible HSL HELLO, heartbeat, and capability-specific data |
| App | hsl_uart2 | PI5, PI6 | HSL_UART2: /dev/ttyAS2 | BUZZER_OUTPUT, LED_OUTPUT, LOCAL_INTERCOM, MMA8452_ACCELEROMETER, PHYSICAL_KEYS, RTK_CORRECTION, RTK_NMEA, VIBRATION_OUTPUT | 是 | 启用/HARDWARE_SERVICE | compatible HSL HELLO, heartbeat, and capability-specific data |

## HSL 量产握手

- 串口固定为 `/dev/ttyAS2`、115200、8N1。串口节点存在不等于可用。
- 模块每次启动或串口重连后，必须先发送 `HELLO 0x02`，并设置 `ACK_REQUIRED`。
- HELLO 载荷固定 20 字节：`schema[1] + contract[3] + capabilities[8] + firmware[3] + hardwareRevision[1] + bootSessionId[4]`，多字节字段为小端序。
- App 要求契约主版本一致、模块次版本不低于 App 次版本、启动会话号非零，并且当前配置所需能力全部存在。
- 兼容 HELLO 通过后还必须收到有效心跳，App 才会执行输出、传感器、RTK 或对讲操作。
- 全能力 HELLO 示例载荷：`01 01 00 00 FF 00 00 00 00 00 00 00 01 00 00 01 01 00 00 00`。
- 完整帧示例，序号 1：`A5 5A 01 01 02 01 00 14 00 01 01 00 00 FF 00 00 00 00 00 00 00 01 00 00 01 01 00 00 00 FF 8F`。

| 位 | 能力 | 基础必需 | 用途 |
|---|---|---|---|
| 0 | PHYSICAL_KEYS | 是 | 物理按键事件 |
| 1 | LED_OUTPUT | 是 | LED 输出及确认 |
| 2 | VIBRATION_OUTPUT | 是 | 振动输出及确认 |
| 3 | BUZZER_OUTPUT | 是 | 蜂鸣输出及确认 |
| 4 | MMA8452_ACCELEROMETER | 是 | MMA8452 样本 |
| 5 | RTK_NMEA | 按配置 | HSL 模式 NMEA |
| 6 | RTK_CORRECTION | 按配置 | HSL 模式 RTCM3 |
| 7 | LOCAL_INTERCOM | 按配置 | 本地对讲 |

## RTK 路径

| 模式 | 端点 | 数据 | 启用条件 |
|---|---|---|---|
| `HSL`，默认 | `/dev/ttyAS2` | `RTK_NMEA 0x31` 输入，`RTK_CORRECTION 0x32` 输出 | HELLO 声明对应能力并通过心跳 |
| `DIRECT_UART4` | `/dev/ttyAS4` | 原始 NMEA 输入和原始 RTCM3 输出 | 硬件确认波特率后通过 App 受控配置选择 |

HSL 和 UART4 由硬件进程中的独立服务、文件描述符、读取线程、状态和重连流程持有。release 版本固定端点，不能输入任意串口或执行 shell。两种路径汇入相同的 NMEA、定位、RTCM3 和 NTRIP 校验流程。

## 状态和验收判定

| 接口 | 可用或运行依据 | 不通过状态 |
|---|---|---|
| 摄像头 | Camera2 枚举 USB UVC 摄像头并完成实际采集 | 等待外设、权限不足、驱动缺失、明确故障 |
| 音频 | Android Audio API 录到非零数据并可输出 | 等待外设、权限不足、驱动缺失、明确故障 |
| 4G | ConnectivityManager 报告经过验证的蜂窝网络 | 等待外设、驱动缺失、明确故障 |
| SD1/SD2 | StorageManager 枚举挂载卷并完成读写 | 等待外设、权限不足、驱动缺失、明确故障 |
| HSL | HELLO 兼容、必需能力齐全、心跳有效 | 等待外设、协议不兼容、数据过期、权限不足、明确故障 |
| RTK | NMEA 校验通过并形成有效定位 | 等待外设、未配置、数据过期、协议不兼容、明确故障 |

## 断线恢复

- HSL 或 UART4 断开后关闭原文件描述符，并按 250 ms 起步、最长 30 s 的退避重新打开。
- HSL 重连后必须重新完成 HELLO 和心跳；旧启动会话的数据和确认不得沿用。
- UART4 重连不占用 `/dev/ttyAS2`，HSL 重连也不占用 `/dev/ttyAS4`。
- 未确认控制、冲突引脚和任意 GPIO 操作保持禁用。模拟端结果只证明软件协议路径，不计为真实硬件验收。

## 硬件方确认项

1. [ ] 确认 PI4 最终用于摄像头电源还是 MMA8452 INT2，并消除复用冲突
2. [ ] 确认 PI1、PI0、PG17、PG19、PG18、PG16、PG15 分别对应哪个按键或 LED，以及方向和有效电平
3. [ ] 确认 /dev/ttyAS2 对接 HSL 模块，并确认量产 RTK 默认由 HSL 转发还是独立 UART4
4. [ ] 确认 gpio203 的原理图用途以及它与 PG13 的实际映射关系
5. [ ] 确认实际安装的 NAU88C2x codec 型号及设备树 compatible
6. [ ] 确认 4G 模块型号为 AIR780EG 还是 AIR781EG
7. [ ] 确认 PG10 的复位或关机语义、安全脉冲宽度及默认电平
8. [ ] 确认全部 GPIO 的上电默认电平、上下拉、开漏要求和最大驱动状态
9. [ ] 启用 /dev/ttyAS4 前确认 RTK 接收机波特率

全部确认项关闭并完成真实外设测试前，不得把对应接口标记为硬件验收通过。

## 联调签字

| 角色 | 姓名 | 日期 | 契约版本 | 结论 |
|---|---|---|---|---|
| 硬件负责人 |  |  | `1.0.0` |  |
| BSP 负责人 |  |  | `1.0.0` |  |
| Android App 负责人 |  |  | `1.0.0` |  |
