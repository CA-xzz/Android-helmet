# H618 硬件引脚映射

本文记录 `HELMET_v3_footpin.xlsx` 第 2 至 45 行在 `QUAD-CORE H618 p1` 上的核对结果。板端基线采集日期为 2026-08-19，ADB 序列号为 `2c001031774186e21d3`。

状态含义：

- 已映射：H618 设备树、平台驱动或 Android 标准接口与表格一致。
- 软件通过：应用路径和降级逻辑已测试，不表示外设性能通过。
- 等待外设：接口存在，但没有外部设备或有效业务数据。
- 冲突：同一引脚存在两个用途，或与当前启用的 pinmux 冲突。
- 未配置：方向、电平、时序、拓扑或器件型号尚未确认，生产代码不会操作。
- 驱动缺失：当前 BSP 没有可用设备或 Android 能力。

## 逐行映射

| 行 | 模块 | 表格信号 | H618 映射 | 状态与依据 |
|---:|---|---|---|---|
| 2 | DEBUG | USB0 | Bootloader/BSP 烧录口 | 已映射。保留为固件维护口。 |
| 3 | DEBUG | PH1 UART0_RX | `/dev/ttyAS0`，UART0 | 已映射。仅用于调试。 |
| 4 | DEBUG | PH0 UART0_TX | `/dev/ttyAS0`，UART0 | 已映射。仅用于调试。 |
| 5 | SYSTEM | PG13 GPIO，3.3 V 总使能，高有效 | GPIO 内核驱动待实现 | 未配置。板端已有 `gpio203` 为输出高，但不能据此认定为 PG13。未执行切换。 |
| 6 | CAMERA | USB1 UVC | Camera2/CameraService | 软件通过，等待外设。CameraService 当前枚举 0 个摄像头。 |
| 7 | CAMERA | PI4 摄像头电源，高有效 | GPIO 电源控制待实现 | 冲突。表格第 34 行同时把 PI4 定义为 MMA8452 INT2。 |
| 8 | SD2 | USB3 GL823K | StorageManager/vold | 软件通过，等待外设。当前没有可归属的可移动卷。 |
| 9 | SD2 | PG12 插入检测，高有效 | 输入驱动待实现 | 未配置。上拉、消抖和业务归属待原理图确认。 |
| 10 | SD2 | PH10 电源使能，高有效 | GPIO 电源控制待实现 | 未配置。实时设备树还为 `s_cir0` 定义 PH10，需确认实际 owner。 |
| 11 | SD1 | PI16 nRST，低电平断电 | GPIO 电源控制待实现 | 未配置。低有效语义来自表格，未验证复位默认值和断电时序。 |
| 12 | SD1 | PF6 插入检测，高有效 | 输入驱动待实现 | 未配置。不能用检测电平代替挂载和读写结果。 |
| 13 | SD1 | PF0 SDC0_D1 | `sdmmc@4020000` | 已映射，驱动缺失。实时 SDC0 pinmux 与 PF0 至 PF5 相符，当前没有可移动块设备。 |
| 14 | SD1 | PF1 SDC0_D0 | `sdmmc@4020000` | 已映射，驱动缺失。 |
| 15 | SD1 | PF2 SDC0_CLK | `sdmmc@4020000` | 已映射，驱动缺失。 |
| 16 | SD1 | PF3 SDC0_CMD | `sdmmc@4020000` | 已映射，驱动缺失。 |
| 17 | SD1 | PF4 SDC0_D3 | `sdmmc@4020000` | 已映射，驱动缺失。 |
| 18 | SD1 | PF5 SDC0_D2 | `sdmmc@4020000` | 已映射，驱动缺失。 |
| 19 | AUDIO | PH2 IIC2_SCK | I2C2 codec 驱动待实现 | 驱动缺失。当前只有 `/dev/i2c-1`，其客户端为 PCF8563。 |
| 20 | AUDIO | PH3 IIC2_SDA | I2C2 codec 驱动待实现 | 驱动缺失。表格在各行写出 NAU88C22YG、23YG、24YG、25YG，不能据此确定实际 codec。 |
| 21 | AUDIO | PH4 codec 电源，高有效 | GPIO 电源控制待实现 | 未配置。器件型号、设备树 compatible 和安全上电时序待确认。 |
| 22 | AUDIO | PH5 IIS3_MCLK | ALSA/Audio HAL 待实现 | 驱动缺失。实时设备树未给出可确认的完整 I2S3 时钟组。 |
| 23 | AUDIO | PH6 IIS3_BCLK | ALSA/Audio HAL 待实现 | 驱动缺失。 |
| 24 | AUDIO | PH7 IIS3_LRCK | ALSA/Audio HAL 待实现 | 驱动缺失。 |
| 25 | AUDIO | PH8 IIS3_SDO | I2S3 data pin group | 部分映射。实时设备树列出 PH8、PH9，但完整 codec 链路未建立。 |
| 26 | AUDIO | PH9 IIS3_SDI | I2S3 data pin group | 部分映射。Android 当前未声明麦克风，AudioManager 没有有效输入设备。 |
| 27 | 4G | PG8 WAKEUP0 | GPIO 输出待实现 | 冲突。实时 UART1 pinmux 同时使用 PG6 至 PG9。有效电平未知。 |
| 28 | 4G | PG9 PWRKEY，拉低 1 秒 | GPIO 脉冲待实现 | 冲突。1 秒低脉冲仅进入可测试状态机，物理执行被禁用；实时 UART1 同时使用 PG9。 |
| 29 | 4G | PG10 RESET，拉低关机 | GPIO 输出待实现 | 未配置。RESET 与关机语义、脉宽及恢复时序互相矛盾，不执行。 |
| 30 | 4G | PG6 UART1_TXD | `/dev/ttyAS1` | 已映射，等待外设。实时 UART1 pinmux 使用 PG6 至 PG9，模块角色仍待确认。 |
| 31 | 4G | PG7 UART1_RXD | `/dev/ttyAS1` | 已映射，等待外设。表格同时出现 AIR780EG 和 AIR781EG，型号待确认。 |
| 32 | 4G | USB2 | ConnectivityManager/USB 驱动 | 软件通过，等待外设。只在 Android 报告已验证蜂窝网络时显示运行。 |
| 33 | MMA8452 | PI3 电源，高有效 | GPIO 电源控制待实现 | 未配置。传感器驱动缺失，未执行切换。 |
| 34 | MMA8452 | PI4 INT2 | 输入驱动待实现 | 冲突。与第 7 行摄像头电源相同。 |
| 35 | MMA8452 | PI6 IIC0_SDA | I2C0 待实现 | 冲突。实时系统把 PI5、PI6 分配给 UART2。 |
| 36 | MMA8452 | PI5 IIC0_SCK | I2C0 待实现 | 冲突。当前 `/dev/ttyAS2` 生产 HSL 路径使用 UART2。 |
| 37 | RTK | PI13 UART4_TXD | `/dev/ttyAS4`，UART4 | 已映射，未配置。设备节点与 `uart@5001000`、PI13/PI14 对应。 |
| 38 | RTK | PI14 UART4_RXD | `/dev/ttyAS4`，UART4 | 已映射，未配置。接收机波特率及直连或 HSL 转发拓扑待确认。 |
| 39 | LED/按键 | PI1 GPIO | 输入或 LED 驱动待确认 | 未配置。表格只说明 4(3?) 个按键、3(4?) 个 LED。 |
| 40 | LED/按键 | PI0 GPIO | 输入或 LED 驱动待确认 | 未配置。 |
| 41 | LED/按键 | PG17 GPIO | 输入或 LED 驱动待确认 | 未配置。 |
| 42 | LED/按键 | PG19 GPIO | 输入或 LED 驱动待确认 | 未配置。 |
| 43 | LED/按键 | PG18 GPIO | 输入或 LED 驱动待确认 | 未配置。 |
| 44 | LED/按键 | PG16 GPIO | 输入或 LED 驱动待确认 | 未配置。 |
| 45 | LED/按键 | PG15 GPIO | 输入或 LED 驱动待确认 | 未配置。说明书列出电源、多媒体、录音/语音和音量操作，但没有给出七根 GPIO 的一一对应关系。 |

## 当前生产所有权

- Android 标准服务负责 Camera2、Audio、Connectivity、Sensor、Input 和 Storage 状态。设备节点存在不等于外设运行。
- `native-hardware-service` 独占白名单 UART。当前 HSL 生产配置为 `/dev/ttyAS2`、115200。主界面不打开 UART。
- `/dev/ttyAS4` 的独立 RTK 软件路径已经实现，运行配置默认仍为 HSL。波特率和拓扑确认前不选择 UART4；收到 HSL 转发定位不能反推 UART4 已接收数据。
- GPIO 电源、复位、LED 和按键映射全部为禁用状态。`SafePulseExecutor`、`PowerSequenceExecutor`、按键消抖和 LED 优先级状态机只提供可测试语义，未连接到未确认的物理 GPIO。
- 板型校验会拒绝 Excel 行遗漏或重复、重复启用的引脚、重复设备节点、HSL 与 RTK 共用 UART，以及缺少有效电平、安全态或初始化步骤的输出。

## 必须由硬件确认的问题

1. PI4 属于摄像头电源还是 MMA8452 INT2，或者是否存在未记录的复用电路。
2. 七根 LED/按键 GPIO 的功能、方向、有效电平、上拉、消抖和上电默认状态。
3. PG13 对应的 Linux GPIO 编号。当前 gpiochip 基址为 0，已导出的 `gpio203` 不能直接视为 PG13。
4. NAU88C2x 的实际料号、I2C 地址、device-tree compatible、主时钟和上电时序。
5. AIR780EG 或 AIR781EG 的实际型号，PG8/PG9 与 UART1 四线 pinmux 的关系，以及 PG10 的安全语义。
6. RTK 是 `/dev/ttyAS4` 直连还是通过 HSL 转发，及其波特率、报文类型和电平。
7. 所有输出脚的复位默认值、开漏要求、最大驱动能力和断电顺序。

基线证据位于 `docs/verification/h618-hardware-adaptation-2026-08-19/baseline/`。板端 BSP 或原理图确认前，不应解除 `BLOCKED_PENDING_CONFIRMATION`。
