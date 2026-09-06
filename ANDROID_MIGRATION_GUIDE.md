# H618 Android 迁移与验收指南

更新时间：2026-08-13。

## 边界

- 产品主体是 H618 Android 12 App。
- 旧 Linux 程序中的按键、定位、媒体和告警语义已改为 Android API、前台服务、Room、WorkManager、AIDL/JNI 和受控 UART 实现。
- 仓库只保留 App、直接硬件接入、App 测试所需的最小 SQLite 联调夹具和测试工具。
- 内核、设备树、HAL、外部模块固件、生产签名、OTA 和整机认证不在仓库范围内。

## 无外设时的处理

缺少摄像头、麦克风、GNSS/RTK、LoRa、IMU、近电、高度或电池模块时，继续验证接口、状态机、持久化、恢复和明确降级。模拟输入和 PTY 结果只作为软件验证，不作为真实外设验收。

## 统一门禁

```sh
./tools/test-android.sh
./tools/build-android.sh
./tools/test-backend.sh
./tools/test-autostart-watchdog.sh
```

`test-android.sh` 运行 debug/release 单元测试、Lint，并编译仪器测试 APK。板端仪器测试必须另行执行。

## H618 验证

```sh
adb -s 2c001031774186e21d3 install -r \
  device-android/app/build/outputs/apk/debug/app-debug.apk

./tools/run-board-connected-software-tests.sh \
  --adb-serial 2c001031774186e21d3 \
  --hardware-device-path /dev/ttyAS2 \
  --hardware-baud-rate 115200

./tools/run-board-durable-offline-recovery.sh \
  --adb-serial 2c001031774186e21d3
./tools/run-board-automatic-startup-recovery.sh \
  --adb-serial 2c001031774186e21d3
./tools/run-board-geofence-uart-e2e.sh \
  --adb-serial 2c001031774186e21d3
./tools/run-board-audio-pcm-probe.sh \
  --adb-serial 2c001031774186e21d3
```

板测脚本不得卸载或清除主应用。最终软件与外设状态见 `docs/REQUIREMENTS_TRACEABILITY.md` 和 `docs/HARDWARE_INTERFACE.md`。
