# 本地联调服务

`media_service.py` 是 Android App 自动化测试和本地联调使用的最小服务夹具。它使用 SQLite 和本地媒体目录，不属于生产后台，不包含 Web 管理页面、组织、角色、审计、PostgreSQL、S3、MQTT 或容器部署。

## 启动

```sh
export HELMET_MEDIA_TOKEN=local-test-token
python3 backend/media_service.py \
  --host 127.0.0.1 \
  --port 8080 \
  --data-dir /tmp/helmet-media
```

- `/health` 和 `/ready` 不要求令牌。
- `/v1/*` 使用 `Authorization: Bearer <token>`。
- 令牌仅用于本地开发，不提供多用户权限模型。
- WebRTC 默认返回公共 STUN。需要 TURN 时通过受控环境变量提供，不在仓库保存凭据。

## 能力

- 设备状态、轨迹和安全告警上报。
- 分片媒体上传和 SHA-256 完整性校验。
- 设备命令轮询和确认。
- 文字广播和设备回执。
- 呼叫状态、Offer、Answer、ICE 信令和 ICE 配置。

运行测试：

```sh
./tools/test-backend.sh
```

接口定义见 `docs/BACKEND_PROTOCOL.md`。
