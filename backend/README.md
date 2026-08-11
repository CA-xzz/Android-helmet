# 后台服务

`media_service.py` 提供设备综合状态、媒体、轨迹、通话、广播、语音消息和安全告警接口，并同源提供管理端。设备状态、轨迹和告警使用稳定消息 ID 去重；媒体支持分片、断点恢复、摘要校验、内容寻址、幂等提交，以及按组织设备范围检索照片和视频；告警保留设备事件与人工处置两条历史。

本地运行：

```sh
export HELMET_MEDIA_TOKEN='从部署系统注入的测试令牌'
python3 backend/media_service.py --data-dir var/media --host 127.0.0.1 --port 18080
```

本地模式使用 SQLite 和本地对象目录。服务不会接受空令牌。STUN/TURN 测试参数通过 `HELMET_STUN_URLS`、`HELMET_TURN_URLS` 和 `HELMET_TURN_SHARED_SECRET` 注入。地图通过 `HELMET_MAP_TILE_URL_TEMPLATE`、`HELMET_MAP_ATTRIBUTION`、`HELMET_MAP_MIN_ZOOM` 和 `HELMET_MAP_MAX_ZOOM` 注入。本地 HTTP 仅用于 ADB loopback 集成测试。

生产身份模式使用权限为 `0600` 的 schema 2 JSON 文件，并通过 `--auth-config` 加载。文件只保存令牌 SHA-256，不保存原始令牌。可从 `backend/auth-config.example.json` 复制后替换全部示例摘要：

```json
{
  "schemaVersion": 2,
  "organizations": [
    {
      "organizationId": "organization-a",
      "deviceIds": ["helmet-device-001"]
    }
  ],
  "principals": [
    {
      "tokenSha256": "64 个小写十六进制字符",
      "principalId": "admin-a",
      "role": "ADMIN",
      "organizationId": "organization-a"
    },
    {
      "tokenSha256": "64 个小写十六进制字符",
      "principalId": "helmet-device-001",
      "role": "DEVICE",
      "organizationId": "organization-a",
      "deviceIds": ["helmet-device-001"]
    }
  ]
}
```

```sh
chmod 600 /受控路径/helmet-auth.json
python3 tools/validate-backend-auth-config.py /受控路径/helmet-auth.json
python3 backend/media_service.py \
  --data-dir /受控路径/media \
  --host 127.0.0.1 \
  --port 18080 \
  --auth-config /受控路径/helmet-auth.json
```

支持的角色为 `DEVICE`、`VIEWER`、`DISPATCHER`、`SUPERVISOR` 和 `ADMIN`。组织设备是资源隔离边界；操作主体可用 `deviceIds` 进一步限制为组织内设备子集。每个组织必须有 ADMIN，每台设备必须有 DEVICE 主体。轨迹、媒体、通话、广播、语音和告警接口均校验资源所属设备；拒绝事件可由组织 ADMIN 通过 `GET /v1/security-audit` 查询。管理端通过 `GET /v1/access-profile` 读取令牌绑定的实际主体、角色、组织、有效设备范围和能力。ADMIN 响应附带本组织脱敏目录，不含令牌或摘要；DEVICE 不得使用该管理端接口。

操作主体使用 `GET /v1/media?deviceId={deviceId}&kind={PHOTO|VIDEO}&limit={1..100}` 检索可访问范围内的归档。设备和类型筛选可省略；接口不返回语音消息。语音继续通过独立的角色授权接口读取。

操作主体使用 `GET /v1/broadcasts?deviceId={deviceId}&limit={1..100}` 查看可访问设备的文字广播和有序播放回执。VIEWER 只能读取；DISPATCHER、SUPERVISOR 和 ADMIN 可使用 `POST /v1/broadcasts` 发送。开发单令牌模式下发送请求也必须提供有效的操作员 ID 和控制角色，生产模式以令牌主体为准。

DISPATCHER、SUPERVISOR 和 ADMIN 具有 `PLAY_VOICE_MESSAGES` 能力，可通过语音消息接口按设备范围和消息自身 `allowedRoles` 双重授权检索及播放。VIEWER 不具备该能力。读取元数据和请求播放分别写入 READ 与 PLAYBACK 审计。

schema 1 和单令牌模式只保留开发兼容。生产模式要求 PostgreSQL、HTTPS S3、schema 2 身份配置、STUN、TURN、HTTPS 地图、署名和 MQTT 双向 TLS 配置全部存在，任一缺失即拒绝启动。S3 启动时及之后每 5 分钟使用固定探针对象校验完整正文，并确认 `If-None-Match: *` 拒绝再次写入；间隔内 `/ready` 只执行低开销桶可达性检查。`/health` 只表示进程存活，`/ready` 同时检查数据库、对象存储和 MQTT 网关连接。

`database.py` 把仓库 SQL 适配到 PostgreSQL；`object_store.py` 在本地目录和 S3 之间提供相同的内容寻址接口。S3 内容键使用 `If-None-Match: *` 条件写入；大对象在条件完成分段上传后才可见。并发写入相同正文时重新读取并安全去重，不同正文不能覆盖既有键。提交后重新读取全部正文并核对大小和 SHA-256。下载先写入受控临时文件并完成正文校验，再向客户端发送响应；失败时不会把上传会话标记为完成或返回损坏内容。

`mqtt_gateway.py` 使用设备级 MQTT 信封接收状态、轨迹、告警、命令同步、命令确认和广播回执，并复用同一仓储事务。未确认设备命令使用稳定命令 ID 和 QoS 1；同一后端连接只发布一次，后端重连或设备 `command-sync` 时恢复交付，避免离线队列被重复消息填满。部署客户端使用 CA、客户端证书和私钥文件，后端证书 CN 固定为 `backend-gateway`；Broker ACL 和主题定义见 `docs/BACKEND_PROTOCOL.md`。

开发板联调后台栈位于 `deploy/backend-stack/`，包含 PostgreSQL、MQTT 双向 TLS、后台服务和 Caddy。启动前准备身份配置、数据库密码、S3 凭据、MQTT 证书、TURN 参数和地图参数，然后执行：

```sh
docker compose --env-file /受控路径/helmet-backend.env \
  -f deploy/backend-stack/docker-compose.yml config --quiet
```

完整步骤见 `deploy/backend-stack/README.md`。该目录只覆盖开发板联调所需后台服务，不包含独立监控、备份、发布审批、生产签名或产品认证流程。
