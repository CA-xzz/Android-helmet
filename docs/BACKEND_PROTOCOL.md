# 后台协议

## 身份与范围

HTTP 使用 `Authorization: Bearer <token>`。schema 2 身份配置将令牌摘要绑定到组织、角色和设备范围。

角色：

- `DEVICE`：仅访问绑定设备。
- `VIEWER`：读取本组织授权设备。
- `DISPATCHER`：读取并执行呼叫、广播和告警操作。
- `SUPERVISOR`：调度权限及告警处置。
- `ADMIN`：本组织管理和安全审计。

跨组织或超出 `deviceIds` 的请求返回 403，并写入 `security_audit_events`。

## MQTT

主题：

```text
helmet/v1/devices/{deviceId}/up/status
helmet/v1/devices/{deviceId}/up/track
helmet/v1/devices/{deviceId}/up/alert
helmet/v1/devices/{deviceId}/up/command-sync
helmet/v1/devices/{deviceId}/up/command-ack
helmet/v1/devices/{deviceId}/up/broadcast-receipt
helmet/v1/devices/{deviceId}/down/command
```

信封包含 `schemaVersion`、`messageId`、`deviceId`、`sentAtEpochMillis` 和 `payload`。后台按证书身份、主题设备 ID 和信封设备 ID 三者一致性校验。命令使用稳定 ID 和 QoS 1，设备通过同步消息恢复未确认命令。

Broker 配置位于 `deploy/backend-stack/`，仅开放 TLS 端口并使用设备主题 ACL。

设备端 MQTT 地址和客户端证书别名均配置时，使用 MQTT 持久会话接收下行命令。未配置 MQTT 时，前台服务每 2 秒调用 HTTP 设备命令接口。只有主机名严格为 `127.0.0.1`、`localhost` 或 `::1` 的联调地址不要求 Android 网络约束，远端地址必须具有已验证网络。两种通道共用稳定命令 ID、序号、持久化执行状态和确认接口。

## HTTP 接口

### 运行状态

- `GET /health`：进程存活。
- `GET /ready`：数据库、对象存储和 MQTT 依赖就绪。
- `GET /v1/access-profile`：当前主体、角色、组织、设备范围和能力。
- `GET /v1/dashboard-config`：地图和实时通信配置。
- `GET /v1/security-audit`：ADMIN 读取本组织拒绝审计。

### 设备状态和轨迹

- `POST /v1/device-status`：上报可选人员编号、电量、电压、位置质量、运行状态和时间质量。
- `GET /v1/devices/overview`：返回设备最新状态、轨迹、活动告警、呼叫和媒体摘要。
- `POST /v1/tracks:batch`：按稳定消息 ID 和设备序号批量提交轨迹。
- `GET /v1/tracks`：按设备和时间查询轨迹。

位置字段包含纬度、经度、高度、水平精度、来源、fix 类型、卫星数、差分状态和模拟标记。模拟位置不得作为最终证据。

### 告警

- `POST /v1/alerts`：设备提交告警。
- `GET /v1/alerts`：按组织和设备查询。
- `GET /v1/alerts/{alertId}`：查询告警详情和状态历史。
- `POST /v1/alerts/{alertId}/transitions`：确认、处理中或关闭。

告警包含类型、级别、位置、样本摘要、媒体关联和本地动作结果。后台保存操作者、时间和状态变化。设备可以先提交仅含 `relatedEventId` 的告警，再归档同一设备、同一事件的照片或视频；告警查询会动态返回最新匹配媒体的 `mediaAssetId`，不修改设备原始告警，因此重复提交仍保持幂等。管理端通过该 ID 查询并预览现场证据。

### 媒体和语音

- `POST /v1/media/sessions`：创建或恢复上传会话。
- `POST /v1/media/sessions/{sessionId}/chunks`：上传分片。
- `POST /v1/media/sessions/{sessionId}/complete`：校验大小和 SHA-256 后完成。
- `GET /v1/media`：查询照片和视频。
- `GET /v1/media/{mediaId}/content`：读取媒体正文。
- `GET /v1/voice-messages`：查询授权语音消息。
- `GET /v1/voice-messages/{mediaId}/content`：读取语音正文。

同一 `mediaId` 重复完成必须返回相同结果。不同正文不能覆盖已存在的内容键。正文响应使用 `no-store` 并返回长度、类型和 ETag。

设备私有运行配置中的可选 `personId` 使用与后台资源 ID 相同的格式。前台服务在设备状态中上报该值，相机控制器把同一值写入照片和录像元数据；空值表示设备当前未绑定人员。

### 呼叫

- `POST /v1/calls`：设备创建语音或视频呼叫。
- `GET /v1/calls`：查询呼叫。
- `GET /v1/calls/{callId}`：查询状态和历史。
- `POST /v1/calls/{callId}/transitions`：接听、拒绝、结束或失败。
- `GET /v1/calls/{callId}/ice-config`：获取临时 STUN/TURN 配置。
- `GET /v1/calls/{callId}/signals`：按序读取信令。
- `POST /v1/calls/{callId}/signals`：提交 SDP 或 ICE 信令。

设备概览中的活动视频呼叫返回 `hasOffer` 和 `latestOfferSequence`。管理端必须从 `latestOfferSequence - 1` 开始读取并选择该精确 Offer，不能从旧信令页推断当前 Offer。呼叫进入终态后，设备概览的 `liveVideo` 为 `null`。

非法状态转换、越权控制和序号缺口均拒绝并审计。

### 广播和设备命令

- `POST /v1/broadcasts`：调度角色发送文字广播。
- `GET /v1/broadcasts`：查询广播和回执。
- `POST /v1/broadcasts/{broadcastId}/receipts`：设备提交送达、播放中和终态回执。
- `GET /v1/device-commands`：设备同步未确认命令。
- `POST /v1/device-commands/{commandId}/ack`：设备确认命令。

HTTP 轮询启动后立即执行一次，随后每 2 秒触发同步。WorkManager 按回环和远端网络分别使用唯一任务名合并重叠请求，进程首次调度时取消旧版和非当前传输任务。命令处理和回执保存在 Room，覆盖安装或进程重启后继续处理未完成记录。

## 存储

本地测试使用 SQLite 和本地对象目录。外部联调使用 PostgreSQL 和 HTTPS S3。媒体先完成分片校验，再对完整正文计算 SHA-256。数据库只在对象存储提交成功后标记完成。

## 配置

服务地址、身份配置、数据库密码、S3 凭据、MQTT 证书、STUN/TURN 和地图参数通过安全文件或部署环境提供。仓库不保存真实令牌、私钥或服务账号。Android 状态、轨迹、媒体、告警和通信 Worker 仅对主机名严格为 `127.0.0.1`、`localhost` 或 `::1` 的 ADB 联调地址取消网络约束，远端地址保持已连接网络约束。
