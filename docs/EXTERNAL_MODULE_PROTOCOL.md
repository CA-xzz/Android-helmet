# 外部模块协议

本协议用于 Android 应用与现有外部控制或传感器模块通信。它不规定模块内部软件，也不包含模块更新或烧录流程。

## 传输

- HSL 固定使用 `/dev/ttyAS2`、115200、8N1。实时设备树确认该节点属于 UART2 和 PI5/PI6。
- 独立 RTK 固定使用 `/dev/ttyAS4`、8N1。该节点属于 UART4 和 PI13/PI14；波特率只能从 App 白名单配置选择，硬件确认前保持 HSL 默认模式。
- USB 串口沿用相同帧格式。
- I²C 适配器将一次完整帧作为事务载荷，具体地址由硬件清单提供。
- Android 端通过 `HardwareGateway` 统一接收事件和发送命令。

## HSL 帧

```text
A5 5A | version | flags | type | sequence(u16 LE) | payloadLength(u16 LE) | payload | CRC16(u16 LE)
```

- `SOF`：固定为 `0xA5 0x5A`。
- `version`：1 字节，当前为 1。
- `flags`：1 字节，bit0 为 `ACK_REQUIRED`，bit1 为 `ACK`，bit2 为 `ERROR`。
- `type`：1 字节，表示心跳、事件、样本、状态、控制或确认。
- `sequence`：小端序无符号 16 位序号，用于去重和确认。Android 对所有 `ACK_REQUIRED` 下行命令使用同一个线程安全分配器，并在返回调用方前持久化下一序号。模块重启后不得依赖 Android 序号从固定值重新开始。
- `payloadLength`：小端序无符号 16 位，当前最大载荷为 1024 字节。
- `CRC16`：小端序 CRC-16/CCITT-FALSE。初值 `0xFFFF`，多项式 `0x1021`，覆盖 `version` 起至 payload 末尾，不覆盖 SOF 和 CRC 字段。

精确编码以 `device-android/core-protocol` 中的 `HslFrameCodec`、`HslStreamDecoder` 和 `Crc16Ccitt` 为准。

## HELLO 兼容握手

模块每次启动或 HSL 串口重连后，必须先发送 `HELLO 0x02`，设置 `ACK_REQUIRED`，再发送心跳和业务数据。串口节点存在不能代替握手。

HELLO 载荷固定为 20 字节：

| 偏移 | 长度 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 1 | schema | 当前为 1 |
| 1 | 1 | contractMajor | 必须与 App 一致 |
| 2 | 1 | contractMinor | 不得低于 App 要求 |
| 3 | 1 | contractPatch | 诊断字段 |
| 4 | 8 | capabilityMask | 小端序 u64，只允许已定义能力位 |
| 12 | 3 | firmwareMajor/Minor/Patch | 模块固件版本 |
| 15 | 1 | hardwareRevision | 硬件修订号 |
| 16 | 4 | bootSessionId | 小端序非零 u32；每次模块启动使用新值 |

能力位定义、完整帧示例、逐行 Excel 映射和签字项见 `hardware-contract/H618_APP_HARDWARE_CONTRACT.md`。契约主版本不一致、模块次版本过低或必需能力缺失时，Android 返回错误确认，状态显示协议不兼容，并阻止相关控制。

## 可靠性

- 需要确认的业务帧设置 `ACK_REQUIRED`。`ACK` 只用于确认帧，不能与 `ACK_REQUIRED` 同时设置。
- 外部模块按键和告警必须设置 `ACK_REQUIRED`。`SENSOR_SAMPLE` 可按采样率选择可靠或流式发送；设置 `ACK_REQUIRED` 时，Android 仅在样本持久化后发送成功确认。
- 发送方超时后使用相同序列号重试。
- 接收方对重复序列号返回相同确认，不重复生成业务事件。
- Android 只有在载荷完成校验且整帧派生的事件批次进入有界队列后才返回成功确认。队列已满时不确认，发送方继续使用原序列号重试。
- `SENSOR_SAMPLE` 和 `ALARM_EVENT` 的成功确认延后到对应 Room 样本或告警事务提交完成。
- 按键、可靠样本或告警的本地持久化失败时 Android 不发送确认。模块必须用原序号和原业务标识重试。载荷无效时 Android 才发送错误确认。
- `RTK_NMEA` 是连续字节流分片，不允许设置 `ACK_REQUIRED`。NMEA 语义由定位层按完整语句校验。
- 损坏、方向错误、保留或未知的可靠帧返回带 `ACK|ERROR` 标记的错误确认，不会进入业务队列。
- 心跳超时只标记模块离线，不停止 Android 前台服务。
- HELLO 超时会关闭并重开 HSL 端口；重连后必须重新完成 HELLO 和心跳。
- 串口打开失败、原生链路故障、可靠命令超时或心跳故障会触发 250 毫秒起步、最长 30 秒的有界指数退避重开。重连后重新下发尚未由正确认完成的配置。
- Android 用户态串口服务把每个编码后的 HSL 帧作为一个互斥写事务。端口重开会创建新的解码代际；旧端口中迟到的字节被丢弃，不能与新端口字节拼接成帧。
- Android 只把结果码 `0` 视为命令成功。结果码 `1` 至 `4` 是可观察的模块拒绝；超时是单独结果。负确认和超时不得被记录为模块已应用配置或输出。

ACK 载荷为 `acknowledgedSequence(u16 LE) | resultCode(u8) | detail(可选)`。当前结果码如下：

| 结果码 | 含义 |
|---:|---|
| `0` | 成功 |
| `1` | 不支持的协议版本 |
| `2` | 不支持、保留或方向错误的消息类型 |
| `3` | 载荷长度错误 |
| `4` | 标志或字段值错误 |

## 消息类型

| 类型 | 编码 | 方向 | 用途 |
|---|---:|---|---|
| `HEARTBEAT` | `0x01` | 双向 | 链路存活和协议版本 |
| `HELLO` | `0x02` | 模块到 Android | 契约版本、固件版本、能力和启动会话握手 |
| `KEY_EVENT` | `0x10` | 模块到 Android | 物理按键动作 |
| `SENSOR_SAMPLE` | `0x11` | 模块到 Android | IMU、近电、高度和温度原始样本 |
| `ALARM_EVENT` | `0x12` | 模块到 Android | 模块已有告警接口的兼容输入 |
| `SET_CONFIG` | `0x20` | Android 到模块 | 下发版本化安全阈值 |
| `SET_OUTPUT` | `0x22` | Android 到模块 | 请求 LED、振动和蜂鸣输出 |
| `RTK_NMEA` | `0x31` | 模块到 Android | 接收机 NMEA 数据 |
| `RTK_CORRECTION` | `0x32` | Android 到模块 | 已校验 RTCM3 数据块 |
| `LOCAL_INTERCOM_COMMAND` | `0x34` | Android 到模块 | 专用语音模块控制 |
| `LOCAL_INTERCOM_STATUS` | `0x35` | 模块到 Android | 专用语音链路质量 |
| `ACK` | `0x7F` | 双向 | 可靠帧确认或错误确认 |

`BATTERY_STATUS(0x13)`、`GET_CONFIG(0x21)`、`TIME_SYNC(0x23)`、`LOG_REQUEST(0x24)`、`LORA_MESSAGE(0x30)` 和 `RTK_STATUS(0x33)` 仅保留编码，当前没有载荷规范。Android 明确以结果码 `2` 拒绝这些可靠帧。电池数据当前由 `BatteryStatusReader` 读取 Android 电源节点；RTK 状态从经过校验的 NMEA 组合结果生成；专用对讲使用 `LOCAL_INTERCOM_COMMAND/STATUS`。不得使用保留类型传输自定义厂商载荷。

## 事件类型

| 类别 | 关键字段 |
|---|---|
| 按键 | key、action、durationMs |
| IMU | ax、ay、az、gx、gy、gz、sampleReference、monotonicMillis |
| 近电 | fieldStrength、sampleReference、monotonicMillis |
| 高度 | altitude、pressure、temperature、sampleReference、monotonicMillis |
| 电池 | percent、voltage、charging、sampleTime |
| LoRa 对讲状态 | group、ptt、rssi、packetLoss、latencyMs |
| RTK 数据 | NMEA fix 或 RTCM3 分片及质量字段 |

### KEY_EVENT 载荷

`KEY_EVENT` 固定为 12 字节，且必须设置 `ACK_REQUIRED`：

| 偏移 | 长度 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 4 | eventId | 小端序非零 u32；模块必须跨自身重启持久单调，不得复用 |
| 4 | 1 | key | 1 SOS，2 拍照，3 录像，4 通话，5 对讲 |
| 5 | 1 | action | 3 短按完成，4 长按完成，5 双击完成 |
| 6 | 2 | durationMillis | 小端序 u16 |
| 8 | 4 | monotonicMillis | 小端序 u32，允许回绕 |

动作 `1` 按下和 `2` 松开不进入业务路径，Android 返回字段错误。当前每个已接受的 key/action 组合都有明确映射；验证成功的按键帧不会落入空事件批次。模块用相同 `eventId` 重传时，载荷必须完全一致。

Android 以 `deviceId + eventId` 作为真实按键业务标识，`sequence` 只用于当前 HSL 传输确认。模块重启后的防御性重放即使使用新的线序号，只要 `eventId` 和映射后的按键输入及单调时间一致，Android 仍返回当前线序号的确认且不重复执行。相同 `eventId` 映射为不同按键输入或单调时间时会被视为标识冲突，不确认且不执行。原始 key、action 和 duration 的完整一致性仍由模块遵守载荷约束；Android 当前业务事件模型保留映射后的 input、monotonicMillis 和 eventId。

按键成功确认前，Android 将动作计划写入 Room。计划包含固化的对讲 `JOIN/START_TRANSMIT/STOP_TRANSMIT` 目标和 u32 业务 `requestId`、绝对音量目标、拍照关联事件标识，以及呼叫或 SOS 的固定 `callId`。SOS 告警优先使用跨重启唯一的按键 `eventId`，不使用允许回绕的 `monotonicMillis` 作为告警业务标识；模拟输入使用动作标识的稳定校验值。业务副作用完成后才把计划标记为已应用；进程在两步之间退出时，恢复使用同一参数重放。拍照使用相同事件标识恢复同一资产，对讲使用相同业务请求号，音量设置为相同绝对值，呼叫使用相同 `callId`，因此不会反向切换、重复递增或创建第二条业务记录。普通按键提示在已应用提交后尽力播放，不参与业务提交。

`hardware_key_actions` 的终态记录是跨模块重启和任意迟到重放所需的幂等收据。当前协议未规定最大重传时限或可证明的乱序窗口，因此这些低频记录不按数量裁剪。未来只有在协议增加持久高水位及空洞或摘要机制后，才能回收对应收据。

`SENSOR_SAMPLE.sampleReference`、`ALARM_EVENT.alarmId` 和告警引用也必须是非零、跨模块重启持久单调的 u32。相同业务标识只用于完全相同内容的重传，不得在计数器复位后复用。模块心跳 uptime 回退会建立新链路代际并清空短期帧序号窗口，但不会放宽业务标识的跨重启唯一性要求。

字段单位、量程和校准参数必须由模块资料确认。模拟输入设置 `simulated=true`，可用于验证生产软件路径、状态恢复和错误处理，但不得作为真实外设性能或整机产品验收证据。

## RTK

- NTRIP 客户端从差分服务获取 RTCM3。
- `Rtcm3Codec` 校验长度和 CRC24Q。
- 默认 `HSL` 模式使用 `RTK_NMEA` 和 `RTK_CORRECTION` 帧。
- `DIRECT_UART4` 模式使用 `/dev/ttyAS4` 原始字节收发，不套 HSL 帧。
- 外部接收机返回 GGA/RMC/GSA 等 NMEA 数据。
- `ExternalRtkFixAssembler` 只有在质量、时间和坐标字段一致时生成定位结果。
- 两种模式进入同一套 NMEA、RTCM3、定位和 NTRIP 校验。HSL 与 UART4 使用独立 AIDL 服务、文件描述符、读取线程、状态和重连流程，禁止端口复用。
- HSL 转发的有效 NMEA 只能证明 HSL 定位链路有数据，不能反推 `/dev/ttyAS4` 已接入接收机。直连 UART4 启用前必须确认拓扑、波特率、电平和资源所有权。

## LoRa 语音控制

HSL 只传入组、离组、PTT、信道、密钥槽和链路质量，不承载音频正文。音频由专用语音模块的实际链路处理。普通低速数据消息不能代替实时语音验收。

按键触发本地对讲时，Android 在接收事件时根据当前状态固化一个明确目标，不在恢复时重新计算切换方向。每个目标同时持久化非零 u32 `requestId`。发送失败或进程恢复使用相同 action 和 `requestId` 重试；模块应以 `requestId` 对相同命令幂等处理，并拒绝同一 `requestId` 的冲突内容。

## 安全传感器

`SENSOR_SAMPLE.validFlags` 的 bit0 表示 IMU，bit1 表示工频电场，bit2 表示模块计算的海拔，bit3 表示温度，bit4 表示气压。海拔和气压使用独立有效位；只提供气压时必须设置 bit4，Android 才会执行气压高度换算。旧模块只设置 bit2 时仍按海拔输入处理。未置位字段的载荷值必须忽略，不能用零值代替有效测量。

`SafetySampleProcessor` 在样本持久化后调用 `SafetyDetectionEngine`。Android 对范围、时间、序列和质量执行检查，并计算跌落、撞击、晃动、近电基线、累积量和相对高度。每条新样本绑定当前 `SET_CONFIG` 阈值版本和完整阈值 SHA-256 指纹；旧数据库中身份未知的样本不使用当前阈值重放。新样本引用必须严格增加，完全相同的已持久样本才允许作为重传。模块时钟回退或 u32 回绕时，Android 仅在真实样本引用严格大于持续告警最近发布引用时，用原告警 ID 持久化解除并重新校准。

检测器状态使用 Room 13 和二进制 schema 5 检查点。检查点绑定算法版本、阈值版本与完整内容指纹，并覆盖运动候选、冲击和晃动重新武装状态、长时间静止候选和激活状态、近电基线/分级/累计暴露、高度基线/候选时长/慢漂移、持续告警标识及其激活和最近发布引用。当前运动算法版本为 2；旧算法版本按不兼容状态隔离，不能直接沿用旧运动候选。告警持久化完成后，检查点、样本派生标记和最近 10000 条裁剪在同一 Room 事务中提交。告警或检查点提交失败时不确认输入帧；后续实时帧也先从持久状态恢复，不能越过失败水位。阈值内容变化必须提升版本，版本不得回退。配置身份变化、检查点损坏或未来版本不兼容时，旧持续输出先保存解除期望；后台解除使用下一条严格递增的真实 `sampleReference` 走正常告警上传路径，依赖失效基线的早期未派生样本在解除后隔离，不能重新锁存同一告警。

Android 产生的告警先写入 Room，再触发本地语音和可用的 Android 振动器。外部执行器使用 `SET_OUTPUT` 请求，动作位按告警类型选择；近电告警只请求振动位，高度告警只请求 LED 和振动位，不附加说明书未要求的动作。激活和解除命令共用同一个 `requestId`。

`SET_OUTPUT` 载荷固定为 10 字节：

| 偏移 | 长度 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 1 | schema | 当前为 1 |
| 1 | 1 | active | 0 为解除，1 为激活 |
| 2 | 1 | actionMask | bit0 LED，bit1 振动，bit2 蜂鸣 |
| 3 | 1 | reserved | 必须为 0 |
| 4 | 4 | requestId | 小端序非零 u32，激活和解除保持一致 |
| 8 | 2 | durationMillis | 小端序 u16，0 表示保持到解除；解除时必须为 0 |

Android 使用 `ACK_REQUIRED` 重试确认模块已接收输出请求。持续型近电、高度和传感器故障输出会保存期望状态；服务重启或模块链路代际变化后重放激活或尚未确认的解除命令，解除收到正确认后删除恢复记录。`SET_CONFIG` 只有收到正确认后才记录为已应用；负确认按配置版本锁存，防止立即重发风暴；命令超时按链路故障处理。硬件断开、端口重开或模块 uptime 回退会清除已确认会话状态并重发当前配置。实际 LED、振动和蜂鸣效果仍需真实执行器验证。断网不影响检测、本地提示和持久化，恢复网络后告警自动上传。

`SET_CONFIG` schema 2 固定为 106 字节。前 5 字节依次为 schema `u8`、配置版本 `u16 LE` 和正文长度 `u16 LE`；正文为 69 字节；末尾 32 字节是对头和正文计算的 SHA-256。正文按固定顺序编码跌落、撞击、晃动、静止、近电和高度阈值。静止字段位于晃动窗口之后，依次为加速度容差 `u16`、角速度容差 `u32` 和最短持续时间 `u32`，单位分别为 milli-g、milli-degree/s 和毫秒。schema 1 的 96 字节载荷仅用于兼容读取，缺少的静止字段使用默认值；Android 只下发 schema 2。外部模块必须升级后才能确认新配置，不能把 schema 2 截断为 schema 1。

## 兼容性

新增字段只能以版本化方式引入。未知类型、未知版本、超长载荷、无效数值和损坏校验均拒绝。App 契约版本为 `1.0.0`，Kotlin 板型配置是 JSON 和 Markdown 交接文件的唯一数据源。协议测试位于 `device-android/core-protocol/src/test` 和 `device-android/hardware-api/src/test`。
