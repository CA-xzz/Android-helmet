# 最小服务协议

所有 `/v1/*` 请求使用 `Authorization: Bearer <token>`。正文为 UTF-8 JSON，媒体分片除外。重复业务写入使用调用方提供的稳定 ID 保证幂等。

## 设备上行

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v1/device-status` | 设备状态心跳 |
| POST | `/v1/tracks:batch` | 批量轨迹 |
| POST | `/v1/alerts` | 安全和围栏告警 |

H618 定向测试使用 `GET /v1/tracks` 和 `GET /v1/alerts/{id}` 读取刚上报的数据。它们只用于核对本地联调结果，不提供运营查询能力。

## 媒体

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v1/media/sessions` | 创建或恢复上传会话 |
| PUT | `/v1/media/sessions/{id}/chunks` | 上传分片；使用会话要求的偏移 |
| POST | `/v1/media/sessions/{id}/complete` | 核对大小和 SHA-256 后完成 |
| GET | `/v1/media/{id}` | 查询已完成媒体的归档状态和元数据 |

完成前正文只存在于临时会话。校验失败不返回完成状态。

## 呼叫和 WebRTC

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/v1/calls` | App 创建呼叫 |
| GET | `/v1/calls?deviceId=...` | 查看页查询设备呼叫 |
| GET | `/v1/calls/{id}` | 查询一个呼叫 |
| POST | `/v1/calls/{id}/transitions` | 接听、连接、结束或失败 |
| GET | `/v1/calls/{id}/ice-config?requesterId=...` | App 获取 ICE 配置 |
| GET | `/v1/calls/{id}/signals?afterSequence=...` | 按游标读取信令 |
| POST | `/v1/calls/{id}/signals` | 写入 Offer、Answer、ICE 或完成标记 |

Answer 必须引用对应 Offer 的 `offerSequence`。信令按服务端递增序号读取，断线后可从最后游标继续。

## 命令和广播

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/v1/device-commands?deviceId=...&afterSequence=...` | App 轮询设备命令 |
| POST | `/v1/device-commands/{id}/ack` | App 确认命令结果 |
| POST | `/v1/broadcasts` | 本地联调创建文字广播 |
| POST | `/v1/broadcasts/{id}/receipts` | App 回报播报状态 |

呼叫接听会生成设备命令，App 收到后启动 WebRTC。文字广播由 App TTS 播放并回报结果。

## 服务状态

- `GET /health`：进程存活。
- `GET /ready`：SQLite 和本地数据目录可用。

服务只支持本地联调共享令牌。正式公网身份、TURN 凭据和 TLS 终止应由部署环境提供，不在本仓库扩展为管理平台。
