# 外部模块协议

本协议用于 Android 应用与现有外部控制或传感器模块通信。它不规定模块内部软件，也不包含模块更新或烧录流程。

## 传输

- UART 默认使用 8N1，波特率由模块资料确定。
- USB 串口沿用相同帧格式。
- I²C 适配器将一次完整帧作为事务载荷，具体地址由硬件清单提供。
- Android 端通过 `HardwareGateway` 统一接收事件和发送命令。

## HSL 帧

```text
SOF | version | type | flags | sequence | payloadLength | payload | CRC16
```

- `SOF`：固定帧头。
- `version`：协议版本。
- `type`：心跳、事件、样本、状态、控制或确认。
- `sequence`：无符号递增序号，用于去重和确认。
- `payloadLength`：有界载荷长度。
- `CRC16`：覆盖头部和载荷的 CCITT 校验。

精确编码以 `device-android/core-protocol` 中的 `HslFrameCodec`、`HslStreamDecoder` 和 `Crc16Ccitt` 为准。

## 可靠性

- 需要确认的帧设置 ACK 标记。
- 外部模块告警设置 ACK 标记，Android 在样本和告警持久化后发送确认。
- 发送方超时后使用相同序列号重试。
- 接收方对重复序列号返回相同确认，不重复生成业务事件。
- 心跳超时只标记模块离线，不停止 Android 前台服务。

## 消息类型

| 类型 | 编码 | 方向 | 用途 |
|---|---:|---|---|
| `KEY_EVENT` | `0x10` | 模块到 Android | 物理按键动作 |
| `SENSOR_SAMPLE` | `0x11` | 模块到 Android | IMU、近电、高度和温度原始样本 |
| `ALARM_EVENT` | `0x12` | 模块到 Android | 模块已有告警接口的兼容输入 |
| `SET_CONFIG` | `0x20` | Android 到模块 | 下发版本化安全阈值 |
| `SET_OUTPUT` | `0x22` | Android 到模块 | 请求 LED、振动和蜂鸣输出 |
| `RTK_NMEA` | `0x31` | 模块到 Android | 接收机 NMEA 数据 |
| `RTK_CORRECTION` | `0x32` | Android 到模块 | 已校验 RTCM3 数据块 |
| `LOCAL_INTERCOM_COMMAND` | `0x34` | Android 到模块 | 专用语音模块控制 |
| `LOCAL_INTERCOM_STATUS` | `0x35` | 模块到 Android | 专用语音链路质量 |

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

字段单位、量程和校准参数必须由模块资料确认。模拟输入设置 `simulated=true`，不得作为最终验收证据。

## RTK

- NTRIP 客户端从差分服务获取 RTCM3。
- `Rtcm3Codec` 校验长度和 CRC24Q。
- 修正数据通过现有模块接口发送。
- 外部接收机返回 GGA/RMC/GSA 等 NMEA 数据。
- `ExternalRtkFixAssembler` 只有在质量、时间和坐标字段一致时生成定位结果。

## LoRa 语音控制

HSL 只传入组、离组、PTT、信道、密钥槽和链路质量，不承载音频正文。音频由专用语音模块的实际链路处理。普通低速数据消息不能代替实时语音验收。

## 安全传感器

`SafetySampleProcessor` 在样本持久化后调用 `SafetyDetectionEngine`。Android 对范围、时间、序列和质量执行检查，并计算跌落、撞击、晃动、近电基线、累积量和相对高度。模块时钟回退或回绕时重新校准。重复样本不重复产生告警。

Android 产生的告警先写入 Room，再触发本地语音和可用的 Android 振动器。LED、外部振动器和蜂鸣器使用 `SET_OUTPUT` 请求。激活和解除命令共用同一个 `requestId`。

`SET_OUTPUT` 载荷固定为 10 字节：

| 偏移 | 长度 | 字段 | 约束 |
|---:|---:|---|---|
| 0 | 1 | schema | 当前为 1 |
| 1 | 1 | active | 0 为解除，1 为激活 |
| 2 | 1 | actionMask | bit0 LED，bit1 振动，bit2 蜂鸣 |
| 3 | 1 | reserved | 必须为 0 |
| 4 | 4 | requestId | 小端序非零 u32，激活和解除保持一致 |
| 8 | 2 | durationMillis | 小端序 u16，0 表示保持到解除；解除时必须为 0 |

Android 使用 ACK 重试确认模块已接收输出请求。实际 LED、振动和蜂鸣效果仍需真实执行器验证。断网不影响检测、本地提示和持久化，恢复网络后告警自动上传。

## 兼容性

新增字段只能以版本化方式引入。未知类型、未知版本、超长载荷、无效数值和损坏校验均拒绝。协议测试位于 `device-android/core-protocol/src/test`。
