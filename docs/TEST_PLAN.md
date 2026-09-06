# 测试计划

## 主机门禁

```sh
./tools/build-android.sh
./tools/test-android.sh
./tools/test-backend.sh
```

- Android：debug/release 构建、单元测试、Lint 和 androidTest 编译。
- 后台：认证、设备上行、呼叫信令、命令、分片媒体和工具安全测试。

## 音视频推流

至少验证：

1. 呼叫键首次触发创建呼叫，再次触发结束呼叫。
2. 未接听时不采集音视频，接听后进入 `STARTING`。
3. PeerConnection 连接后进入 `STREAMING`，显示真实 `audio`、`video` 标志。
4. 麦克风权限或硬件缺失时进入 `FAILED`，不报告音频成功。
5. 摄像头不可用时显示 `video=false`，允许音频通话。
6. 挂断和异常路径经过 `STOPPING` 并释放会话、采集和 EGL 资源。
7. 网络中断后执行有界重试或 ICE 恢复，不重复创建同一呼叫。

debug App 提供按键模拟入口。该入口只能证明 App 软件路径，不能替代真实按键、摄像头、麦克风或网络验收。

## H618

先确认设备在线，再覆盖安装，不清除主应用数据：

```sh
adb -s 2c001031774186e21d3 get-state
adb -s 2c001031774186e21d3 install -r device-android/app/build/outputs/apk/debug/app-debug.apk
```

UART 综合测试必须显式提供真实节点：

```sh
./tools/run-board-connected-software-tests.sh \
  --adb-serial 2c001031774186e21d3 \
  --hardware-device-path /dev/ttyAS2 \
  --hardware-baud-rate 115200
```

其余定向入口：

- `run-board-audio-pcm-probe.sh`：Android 音频采集和播放探针。
- `run-board-durable-offline-recovery.sh`：离线队列跨进程恢复。
- `run-board-automatic-startup-recovery.sh`：启动恢复。
- `run-board-geofence-uart-e2e.sh`：UART NMEA 和围栏告警。
- `test-autostart-watchdog.sh`：前台服务和启动脚本保护。

脚本只卸载测试包。不得卸载、清除或重置主应用。硬件不存在、权限不足或采集全零时记录为外部依赖，不写成通过。

## 说明书功能

需求逐项状态见 `REQUIREMENTS_TRACEABILITY.md`。软件测试、H618 系统能力检查和真实外设验收分开记录。

## 2026-08-13 结果

- `test-android.sh`：通过，1819 个 Gradle task，覆盖 debug/release 单元测试、Lint 和仪器测试编译。
- `build-android.sh`：通过，952 个 Gradle task，生成 debug/release APK 和硬件模块 debug/release AAR。
- `test-backend.sh`：后台 6 项、工具 34 项通过。
- `test-autostart-watchdog.sh`：5 项通过。
- 综合 H618 板测：155 项通过，包含本地存储、媒体、网络、定位、安全检测、WebRTC、前台服务、Manifest 和 UART 服务恢复。
- UART NMEA 围栏：进出围栏、5 个轨迹请求和 2 个告警请求通过。
- 离线跨进程恢复与开机自动恢复：各 1 项通过。
- 音频探针：播放路径通过；麦克风全零，因此真实采集未通过，归入外设缺口。

最终 debug APK SHA-256：`8174b8936d77a1ff1ffb03d5ea2ff754fb633410f89b0f84f947853683aed717`。

最终 unsigned release APK SHA-256：`7ce2e61fdd5310989ecd8406bf778dd97441418e1dc2c43934b0dc5628f4ffd7`。
