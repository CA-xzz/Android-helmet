# 后台联调服务栈

该目录只提供当前开发板联调需要的 PostgreSQL、S3 客户端配置、Mosquitto 双向 TLS、后台服务和 Caddy HTTPS 入口。不包含独立监控、备份恢复、发布批准、生产签名或产品运维流程。

## 依赖

- HTTPS S3 兼容对象存储及访问凭据。
- STUN、TURN 服务及共享密钥。
- HTTPS 地图瓦片地址。
- 后台 HTTPS 域名和 MQTT TLS 域名。
- MQTT CA、CRL、Broker 证书、后端客户端证书。
- schema 2 后台身份配置。

## 启动

1. 复制 `.env.example` 并替换示例地址。
2. 在仓库外创建身份、数据库、S3、TURN 和 MQTT 证书文件。
3. 使用 Compose 校验并启动。

```sh
docker compose --env-file /受控路径/helmet-backend.env \
  -f deploy/backend-stack/docker-compose.yml config --quiet

docker compose --env-file /受控路径/helmet-backend.env \
  -f deploy/backend-stack/docker-compose.yml up -d --build
```

## 联调检查

- `https://<HELMET_PUBLIC_HOST>/ready` 返回就绪。
- 未带有效身份的 API 请求被拒绝。
- MQTT 8883 不接受无客户端证书连接，设备只能访问自身主题。
- Android 状态、轨迹、媒体、通话、广播和告警可完成端到端闭环。
- PostgreSQL 中存在业务记录，S3 对象摘要与数据库一致。

该目录不作为量产部署、备份、灾难恢复或产品认证材料。
