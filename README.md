# H618 智能安全帽 Android App

本仓库以 `最终版安全帽说明书.docx` 为需求基准，只保留运行在 H618 Android 12 设备上的 App，以及 App 直接依赖的用户态硬件接入、本地联调夹具、构建和板测工具。

## 目录

- `device-android/`：App、前台服务、媒体、定位、安全检测、通信和本地存储。
- `native-hardware-service/`：UART/JNI/AIDL 用户态硬件接入。
- `backend/`：仅用于 App 自动化测试和本地联调的 SQLite 服务夹具。
- `tools/`：构建、主机测试和 H618 定向测试。
- `docs/`：项目边界、架构、协议、硬件接口、测试和需求状态。

## 主机验证

```sh
./tools/build-android.sh
./tools/test-android.sh
./tools/test-backend.sh
```

## 本地联调服务

```sh
export HELMET_MEDIA_TOKEN=local-test-token
python3 backend/media_service.py --host 127.0.0.1 --port 8080 --data-dir /tmp/helmet-media
```

App 通过 ADB 联调时执行 `adb reverse tcp:8080 tcp:8080`，后台地址填 `http://127.0.0.1:8080`。该服务只用于 App 测试，使用共享开发令牌，只能绑定本机或受控测试网络。

## H618

所有板测命令必须显式指定序列号。真实 UART 测试还必须指定已确认的设备节点和波特率。

```sh
adb -s 2c001031774186e21d3 get-state
./tools/run-board-connected-software-tests.sh \
  --adb-serial 2c001031774186e21d3 \
  --hardware-device-path /dev/ttyAS2 \
  --hardware-baud-rate 115200
```

不卸载或清除主应用数据。摄像头、麦克风、GNSS/RTK、LoRa、IMU、近电、高度和电池功能必须在对应硬件存在时验收。

## 文档

- [项目边界](docs/PROJECT_SCOPE.md)
- [架构](docs/ARCHITECTURE.md)
- [最小服务协议](docs/BACKEND_PROTOCOL.md)
- [硬件接口](docs/HARDWARE_INTERFACE.md)
- [需求追踪](docs/REQUIREMENTS_TRACEABILITY.md)
- [测试计划](docs/TEST_PLAN.md)
- [当前状态](docs/GOAL_STATUS.md)
