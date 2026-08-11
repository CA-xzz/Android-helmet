# 智能安全帽 Android 12 迁移工程

本工程将旧 Linux 原型迁移到 H618 Android 12 开发板，交付 Android 设备端、用户态外设接入、后台、管理端、协议、测试和开发板验证资料。

需求基准为 `最终版安全帽说明书.docx`，SHA-256：

```text
105cac44d389ead143007f73ff94bb59fd2fc1041e9ffc397ced331a729481cd
```

当前 31 项需求均已完成软件实现，仍待真实摄像头、GNSS/RTK、LoRa 语音、IMU、近电、高度、电池和音频硬件验收，因此 Goal 保持进行中。

## 工程范围

- `device-android/`：Android 12/API 31 Kotlin 应用和用户态硬件适配。
- `native-hardware-service/`：UART、GPIO、I²C 的 JNI/AIDL 用户态服务。
- `backend/`：设备、轨迹、媒体、通话、广播、告警和审计服务。
- `dashboard/`：地图、轨迹、媒体、通话、广播、权限和告警管理端。
- `deploy/backend-stack/`：开发板联调所需 PostgreSQL、MQTT TLS、后台和 Caddy。
- `simulator/`：软件测试使用的传感器和网络输入。
- `docs/`、`tools/`：协议、测试、验证证据和开发工具。

本工程不包含 Android 系统固件、内核、设备树、外置模块固件、整机更新、生产签名、量产烧录、硬件结构、EMC、IP 防护或产品认证。外部模块只通过 Android API、USB、UART、I²C、JNI 或厂商现有接口接入。

## 构建与测试

```sh
./tools/build-android.sh
./tools/test-android.sh
./tools/test-backend.sh
python3 tools/validate-requirements-traceability.py
```

开发板序列号默认为 `2c001031774186e21d3`，可通过 `HELMET_ADB_SERIAL` 覆盖。

## 文档

- [需求追踪](docs/REQUIREMENTS_TRACEABILITY.md)
- [目标状态](docs/GOAL_STATUS.md)
- [架构](docs/ARCHITECTURE.md)
- [硬件接口](docs/HARDWARE_INTERFACE.md)
- [外部模块协议](docs/EXTERNAL_MODULE_PROTOCOL.md)
- [后台协议](docs/BACKEND_PROTOCOL.md)
- [测试计划](docs/TEST_PLAN.md)
- [待确认问题](docs/OPEN_QUESTIONS.md)
- [验证证据](docs/verification/stage-7/README.md)
