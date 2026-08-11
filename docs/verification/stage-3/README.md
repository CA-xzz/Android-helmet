# 阶段 3 验证证据

验证日期：2026-08-10（Asia/Shanghai）

开发板：QUAD-CORE H618 p1，ADB `2c001031774186e21d3`，Android 12/API 31。

## 已通过

- Camera2 能力探测按后置、外置、其他顺序选择摄像头；照片选择最大 JPEG，录像优先 1920×1080。MediaRecorder 配置 H.264、AAC、30 fps 和 8 Mbit/s。
- 拍照和录像先写 `.partial`，完成文件同步后原子改名并计算 SHA-256。启动时清理未完成的 `.partial` 文件。
- Room 数据库从版本 1 迁移到版本 3，保留事件并增加媒体表、人员和位置字段。媒体队列在数据库关闭和重开后仍存在。
- Android 全量单元测试与 Lint 通过，共 408 个 Gradle 任务。见 `android-test-lint.log`。
- 后台 3 项测试通过，覆盖认证失败、分片摘要错误、重复分片、断点恢复、进程重开恢复、完成摘要校验、重复提交和内容去重。见 `backend-tests.log`。
- 开发板 2 项相机测试通过，验证 CameraManager 能力一致性，以及无摄像头时明确失败且不留下 `.partial`。见 `connected-camera-tests.log`。
- 开发板 6 项持久化测试通过，包含数据库 1→3 迁移、媒体传输状态和数据库重开恢复。见 `connected-data-tests.log`。
- 通过 ADB reverse，Android 客户端先提交 64 KiB 分片，再从服务端偏移继续上传并归档 700,000 字节媒体；重复提交未再次上传。见 `connected-media-upload-tests.log` 和 `media-backend-board.log`。
- MediaUploadWorker 从 Room 读取待传媒体，上传 300,000 字节后把状态改为 `DELIVERED`。见 `connected-media-worker-tests.log`。
- 服务端归档记录、对象大小和对象文件 SHA-256 一致。两条相同内容归档只保存一个对象。见 `media-backend-archive.txt` 和 `media-backend-data/`。
- 开发板 CameraService 报告 0 个摄像头。模拟拍照物理键语义后记录 `PHOTO_CAPTURE_FAILED`，服务保持 `OFFLINE_READY`，进程和前台服务存活，媒体目录无残留文件。见 `board-camera-dumpsys.txt`、`board-no-camera-logcat.txt`、`board-processes.txt`、`board-service-state.txt` 和 `board-media-files.txt`。
- 开发板复验发现心跳会取消尚未完成的按键处理。事件流已由 `collectLatest` 改为顺序 `collect`，复测日志显示 `SIMULATED_KEY_PHOTO_SHORT` 后稳定产生 `PHOTO_CAPTURE_FAILED`。
- APK SHA-256 为 `c314f96b5fdf7bf0579a9514aa961900c42f6364527a25d2869ca6cfd4223b08`。

## 媒体上传边界

- 非 loopback 地址必须使用 HTTPS；本次板端测试的 HTTP 仅通过 ADB reverse 连接主机 `127.0.0.1`。
- 后台要求非空 Bearer token。测试令牌只存在于仪器测试和测试进程，主应用最终保持空后台配置。
- 服务端以 `mediaId` 保证归档幂等，以 SHA-256 复用内容对象；分片要求 `Content-Range` 和 `X-Chunk-SHA256`。
- Worker 只读取应用私有 `files/media/` 下的文件。路径越界、文件缺失、大小变化或摘要变化会拒绝上传。
- 当前本地后台使用 SQLite 和文件对象目录。外部 TLS、设备证书、数据库、对象存储、组织权限和审计联调仍属于阶段 7。

## 已知限制

- 开发板没有摄像头，因此没有真实照片、1080P 录像、音频轨、帧率、长时间稳定性、存储耗尽、温升和播放证据。
- 没有真实外部按键模块。当前只验证 HSL 按键映射、模拟按键语义及无摄像头失败路径。
- 生产运行配置可绑定人员编号，前台服务已在 H618 自动状态心跳中把该编号传至后台；相机控制器会把同一编号写入拍照和录像元数据。当前没有摄像头和 GNSS，不能取得真实媒体、人员和位置组合证据。
- 应用私有配置中的 Bearer token 只用于开发联调，外部环境应使用受控设备凭据。
- 开发板系统时间仍不正确，板端日志日期为 2026-07-29。证据目录的验证日期使用主机时间。

## 最终验收缺口

阶段 3 的软件、开发板无摄像头故障处理和本地端到端上传门禁已通过。OQ-001、OQ-002、OQ-010、OQ-012 和 OQ-013 仍阻塞真实物理键、13MP 照片、连续 1080P、音频、长稳、温升、真实断网补传、真实媒体与人员和位置的组合验证及外部对象存储。因此 REQ-5.2-01 至 REQ-5.2-06 仍不得标记为最终完成。
