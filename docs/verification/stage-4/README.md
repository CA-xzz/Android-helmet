# 阶段 4 验证记录

日期：2026-08-11（Asia/Shanghai）

设备：`2c001031774186e21d3`，H618，Android 12，API 31。

## 结论

阶段 4 软件门禁通过，真实定位和网络验收未完成。设备端已实现定位质量、外部 NMEA、安全 NTRIP、RTCM3 双重校验、轨迹 Room 队列、顺序补传、电子围栏、媒体位置关联和网络诊断；开发后台实现轨迹持久化、查询、双重幂等和 RTK 状态展示。新增软件与 H618 夹具证据见 `../stage-7/rtk-local-intercom-board-test.txt`、`../stage-7/rtk-credential-board-test.txt` 和 `geofence-uart-board-e2e.txt`。开发板没有 GNSS provider、RTK 接收机、天线、差分服务或蜂窝服务，所有真实精度、动态轨迹、网络切换和越界指标继续阻塞。

## 自动测试

- `./tools/test-android.sh`：`testDebugUnitTest` 与 `lintDebug` 通过，当前回归为 738 个 Gradle 任务。
- `./tools/test-backend.sh`：当前回归 71 项通过。
- NMEA 覆盖 GGA、RMC、GSA、GSV、校验和及 RTK 质量字段。
- 电子围栏覆盖滞回、连续样本确认、首次处于围栏外、模拟位置和 `NO_FIX` 拒绝。
- 后台覆盖轨迹顺序、重启持久化、重复提交、消息冲突、序号冲突和模拟位置拒绝。

## 开发板结果

- 网络能力 instrumentation：1 项通过。
- 定位能力 instrumentation：1 项通过。
- 数据层 instrumentation：8 项通过，包括 Room 1→4 迁移、轨迹顺序和配置持久化。
- 媒体和轨迹 Worker instrumentation：2 项通过。
- 未授予定位权限时，服务保持运行并显示 `PERMISSION_REQUIRED`、`NO_FIX`、轨迹 0。
- 授予权限后，选择系统 `fused` provider，显示 `WAITING_FOR_FIX`；系统 `last location=null`，轨迹仍为 0。
- 最终主应用显示 `OFFLINE_READY`、`networkState=UNAVAILABLE`、`locationHasPosition=false`、`pendingTrack=0`，没有异常退出。
- ADB reverse 软件管线在后台保存 1 个媒体归档和 2 个轨迹点，轨迹序号为 1、2。重复 Worker 不重新尝试已送达点。
- `geofence-uart-board-e2e.txt` 验证外部 HSL RTK_NMEA 经用户态串口服务、定位服务、电子围栏、Room 和 WorkManager 自动形成退出与返回后台事件。测试没有直接写入告警或调用 Worker。

软件管线使用测试坐标验证序列化和传输，不属于真实定位证据。主应用没有注入测试坐标。

## 关键证据

- `android-test-lint-final.log`：全量 Android 测试和 Lint。
- `backend-track-tests.log`：后台 5 项测试。
- `board-connectivity-connected-test.log`、`board-location-connected-test-final.log`、`board-data-connected-test-final.log`、`board-sync-workers-connected-test-final.log`：12 项板端测试。
- `board-location-runtime-final.txt`：仅有 passive、fused 且最后位置为空。
- `board-connectivity-runtime-final.txt`、`board-telephony-baseline.txt`、`board-network-interfaces.txt`：无有效默认网络、蜂窝无服务和以太网无载波。
- `board-main-service-final.txt`：最终主应用前台服务状态。
- `board-backend-archive-query-final.txt`、`board-backend-track-payloads-final.jsonl`、`board-backend-evidence-sha256-final.txt`：媒体和两点轨迹后台证据。
- `apk-sha256-final.txt`：阶段 4 APK 摘要。
- `geofence-uart-board-e2e.txt`：外部 UART NMEA 到电子围栏 Room 和后台的生产服务链路。

既有开发板日志时间为 2026-07-29，本次围栏日志时间为 2026-07-30；对应主机取证日期为 2026-08-10 和 2026-08-11。该偏差来自板端系统时钟，仍由 OQ-017 跟踪。

## 未完成项

- 无北斗/GNSS/RTK 模块、天线、差分账号和已知基准点，不能验证 5 米与 0.5 米指标。
- 无蜂窝模块或可用 SIM/APN，不能执行 Wi-Fi、蜂窝和以太网切换、弱网及离线恢复实测。
- 无真实移动轨迹和真实越界条件；电子围栏已有外部 UART 合成 NMEA 的生产服务回归，但不属于真实越界证据。
- 无生产 TLS、设备证书、地图、组织权限、处置和审计，阶段 7 继续完成。
