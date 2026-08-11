# 阶段 7 验证

阶段 7 正在进行开发板软件联调和第 5.1 至 5.6 的端到端验收。当前软件链已建立，真实外部模块仍缺失。

## 当前有效证据

- `board-app-verification.txt`：应用安装、启动和服务状态。
- `data-local-board-tests.log`：Room 数据和迁移。
- `hardware-process-board-regression.txt`：独立硬件进程与 UART 用户态访问。
- `durable-offline-recovery-board-test.txt`：轨迹、媒体和告警在无默认网络时保留远端路由任务，进程重启并切换回环路由后取消旧任务并由 WorkManager 自动补传；验证轨迹、媒体、告警和通信队列超过单次 Worker 上限后继续处理；验证通话、命令确认和广播回执跨进程恢复及有序重放；同时记录 Room 8 事件日志清理和保留数据升级。
- `automatic-startup-queue-recovery-board-test.txt`：主应用被强制停止后，由应用初始化和前台服务启动自动恢复轨迹、媒体、告警和通信队列；测试不调用 Worker 或其 enqueue 方法。
- `device-status-board-test.log`：设备状态、回执和服务器校时。
- `automatic-status-heartbeat-board-test.txt`：前台服务自动上报启动状态、服务器校时状态和 60 秒心跳，测试不直接调用 Worker。
- `automatic-safety-alert-upload-board-test.txt`：前台服务接收模拟跌倒输入后自动持久化样本和告警，并通过 SafetyAlertWorker 上传，测试不直接创建告警或调用 Worker。
- `safety-alert-workflow-board-test.txt`：H618 自动生成的模拟跌倒告警完成后台提醒、权限拒绝、确认、处理中、关闭、操作者历史和重启持久化。
- `geofence-board-test.log`：电子围栏告警软件路径。
- `rtk-credential-board-test.txt`、`rtk-local-intercom-board-test.txt`：RTK 凭据和外部模块控制夹具。
- `call-prompt-board-test.txt`、`audio-pcm-board-probe.txt`：呼叫提示和音频能力探针。
- `dashboard-access-center-regression.txt`：权限中心、角色隔离和拒绝审计。
- `dashboard-media-archive-regression.txt`：媒体上传、检索、预览和组织隔离。
- `dashboard-broadcast-console-regression.txt`：广播发送、设备回执和只读角色。
- `dashboard-voice-player-regression.txt`：语音上传、播放和组织隔离。
- `controlled-board-e2e-regression.txt`：开发板到本地后台的受控端到端回归。

## 已知边界

- 当前摄像和音频文件来自测试夹具，不是摄像头或麦克风证据。
- 当前定位、RTK、LoRa 和安全传感器数据来自回环或模拟输入，不是精度、射频或检测性能证据。
- 自动安全告警上传和处置使用模拟跌倒输入，没有真实 IMU、位置、现场媒体或实际处置演练证据。
- H618 音频播放路径可写入 PCM，内置采集返回全零，没有声学闭环。
- 后台回归主要使用本地数据库、对象目录和 ADB reverse。外部 PostgreSQL、S3、MQTT、TURN、地图和 TLS 环境尚未完成联调。
- 需求矩阵仍为 31 项软件完成待实机，0 项已完成。

## 后续验收

按 `docs/TEST_PLAN.md` 接入真实摄像头、GNSS/RTK、蜂窝、音频、LoRa 语音、IMU、近电、高度、电池和本地执行器。每次测试记录硬件、接线、配置、环境、原始结果和结论，再更新需求矩阵。
