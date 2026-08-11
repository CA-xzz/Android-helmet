# Goal 状态

更新时间：2026-08-11

当前状态：进行中。

## 范围

本阶段只交付 Android 开发板应用、用户态 USB/UART/I²C/JNI 外设接入、后台、管理端，以及说明书第 5.1 至 5.6 的软件实现和开发板联调。

不开发 Android 系统固件、内核、设备树、外置模块固件、整机更新、生产签名、量产烧录、硬件结构或产品认证。

## 当前结果

- 说明书基线 SHA-256 与目标一致。
- Android 多模块工程、前台服务、Room、专用离线队列、本地事件日志和启动恢复已实现。
- 用户态 UART/JNI 硬件服务、外部模块协议、模拟器和协议测试已实现。
- 原始 IMU、近电和高度样本已接入 Android 安全检测、告警持久化、本地反馈和外部模块输出命令运行路径。
- 轨迹、电子围栏、媒体、呼叫、广播、语音消息、安全告警、处置审计和权限隔离已实现。
- 后台、管理端和开发板联调路径可运行。
- 需求矩阵为 0 项未实现、31 项软件完成待实机、0 项已完成。

## 已验证

- Android 主机单元测试、Lint、APK 构建和开发板仪器测试已有记录。
- H618 上已验证应用安装、启动、前台服务恢复、Room 持久化、UART 打开关闭、离线队列恢复和 ADB 回环端到端链路。
- H618 在没有 Android 默认网络时，轨迹、媒体和告警的远端路由任务保持等待；测试进程终止并切换 ADB 回环路由后，旧任务被取消，新任务由 WorkManager 自动上传。记录见 `docs/verification/stage-7/durable-offline-recovery-board-test.txt`。该记录不替代真实 4G/5G 断网恢复测试。
- H618 已验证超过单次 Worker 上限的队列续传。一次调度可处理 201 条轨迹、101 条合成近电告警和 101 条通话记录；101 条无效媒体记录用于验证媒体任务能追加第二批。记录见 `docs/verification/stage-7/durable-offline-recovery-board-test.txt`。
- H618 已验证通信持久队列的进程重启恢复。待发送通话、命令确认和广播最终回执在强制终止测试进程后由 WorkManager 送达；广播回执按 RECEIVED、FAILED 顺序重放，原始播放错误保持不变。记录见 `docs/verification/stage-7/durable-offline-recovery-board-test.txt`。
- H618 已验证生产启动路径自动恢复持久队列。测试先写入 attemptCount 为 0 的轨迹、媒体、合成近电告警和通话，并向 256 KiB 媒体会话预置首个 64 KiB 分片；强制停止主应用后只启动应用进程，HelmetApplication 和 HelmetService 自动调度四类 Worker 并全部送达。媒体 Worker 从 nextOffset 65536 继续上传剩余 196608 字节，没有重复首片。测试不调用 Worker 或其 enqueue 方法，记录见 `docs/verification/stage-7/automatic-startup-queue-recovery-board-test.txt`。
- 主应用已删除没有传输实现的通用事件上传任务。运行状态显示本地持久事件数，不再把本地事件日志标为待发送队列。
- Room 版本 8 已删除本地事件日志中无用途的投递状态列。H618 上 1→8 迁移测试通过，主应用保留原有数据完成 7→8 覆盖升级，事件内容继续保留。
- H618 在没有 Android 默认网络时，通过 ADB 回环验证了前台服务自动上报启动状态、服务器校时状态和 60 秒心跳，测试不直接调用 Worker，记录见 `docs/verification/stage-7/automatic-status-heartbeat-board-test.txt`。
- H618 在没有 Android 默认网络时，通过 ADB 回环验证了前台服务接收模拟跌倒输入后自动持久化样本和告警，并由 SafetyAlertWorker 上传到后台。测试不直接创建告警或调用 Worker，记录见 `docs/verification/stage-7/automatic-safety-alert-upload-board-test.txt`。该记录不替代真实 IMU、位置和媒体证据。
- H618 自动生成的模拟跌倒告警已验证后台提醒字段、只读角色拒绝、确认、处理中、关闭、操作者历史和后台重启持久化，记录见 `docs/verification/stage-7/safety-alert-workflow-board-test.txt`。该记录不替代真实现场告警和实际处置演练。
- H618 在没有 Android 默认网络和 MQTT 配置时，通过 ADB 回环验证了覆盖安装后的前台服务自动轮询 HTTP 设备命令，按序处理接听、挂断、拒绝和文字广播，并提交回执和命令确认，记录见 `docs/verification/stage-5/android-http-command-polling-board-test.txt`。
- H618 上使用合成输入验证了原始安全样本、Android 检测、稳定告警周期、SET_OUTPUT 命令和样本去重运行路径，记录见 `docs/verification/stage-6/android-safety-runtime-board-test.txt`。该记录不替代真实传感器和执行器测试。
- 后台已验证资源隔离、轨迹、媒体、呼叫、广播、语音、告警、审计、PostgreSQL 适配、S3 完整性和 MQTT 消息处理。
- 管理端已验证权限中心、地图、媒体、呼叫、广播、语音和告警处置界面。

## 未完成

以下输入缺失，不能以模拟数据替代：

- 外置摄像头及连续 1080P、帧率、温升和长时间录像测试。
- GNSS/RTK 接收机、天线、差分服务、基准点和 0.5 米现场测试。
- 麦克风、扬声器及真实 WebRTC 音视频链路。
- 专用 LoRa 语音模块及距离、穿透、时延、丢包和可懂度测试。
- IMU、工频电场、高度、电池和本地 LED/语音/振动执行器。
- 4G/5G 网络及真实弱网、断网和恢复场景。
- 后台外部 PostgreSQL、S3、MQTT、TURN、地图和 TLS 环境。

## 下一步

1. 获取外部模块型号、接口和接线资料。
2. 按 `docs/HARDWARE_INTERFACE.md` 接入真实硬件。
3. 逐项执行 `docs/TEST_PLAN.md` 的开发板和端到端测试。
4. 将真实证据写入 `docs/verification/` 并更新需求矩阵。
5. 仅在 31 项需求全部具备真实验收证据后完成 Goal。
