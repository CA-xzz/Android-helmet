# 阶段 1 验证记录

执行日期：2026-08-10

设备：`2c001031774186e21d3`，H618，Android 12，API 31。

## 结论

阶段 1 软件门禁通过。最终调试 APK 的 SHA-256 为：

```text
8c6c408c21803ca9688c313e909dce5e4730ead6185f3f4e4f4f2745c5bab4ed
```

- `./tools/test-android.sh` 通过，共 258 个 Gradle 任务。
- `./tools/build-android.sh` 通过，共 119 个 Gradle 任务。
- 开发板 instrumentation 测试通过 3 项：稳定 `deviceId`、配置持久化、本地事件日志重复 `messageId` 幂等。
- APK 覆盖安装成功，最终冷启动成功。
- 最终 APK 重启后由开发板启动脚本自动拉起前台服务，进程 PID 为 1672。
- 重启后状态为 `OFFLINE_READY`，`networkAvailable=false`，应用未退出。
- `deviceId` 在覆盖安装和多次重启前后均为 `c1083e42-c9da-4839-91e4-62e90eb805e4`。
- 本地事件日志在重启前后保留，最终调试页显示 19 条历史记录。
- 模拟拍照、跌落、近电、高度、音量和 SOS 事件均写入 Room；模拟结果不作为真实硬件证据。
- 音量模拟命令使媒体音量从 5 降至 4，再恢复至 5。
- 板卡未安装 TTS 引擎，初始化返回 `status=-1`；程序自动使用提示音，日志存在 `AudioTrack` 播放记录。麦克风和扬声器实物仍未验收。
- 前台服务进程 PID 1650 被终止后约 1 秒以 PID 2297 重建，`restartCount=1`，本地事件日志继续保留。

## 开机配置

该固件发送系统 `BOOT_COMPLETED` 时未将数据分区应用加入接收器列表。应用清单仍保留标准 `BootReceiver`，开发板同时使用已有 `/data/myautorun.sh` 作为固件兼容入口。

配置保留原厂 GPIO 203 初始化和 `com.hy.yesobox.NoScreenActivity` 启动动作，并在 `sys.boot_completed=1` 后启动：

```text
com.example.helmet/com.example.helmet.service.runtime.HelmetService
```

文件摘要：

| 文件 | SHA-256 |
|---|---|
| 原厂脚本 | `f03b811aa7547a1873336189fd4576a409f44edf7b4bfbb5d7d681da421a6191` |
| 当前脚本 | `5eec0279292f48b1d42151a25d67a01f60c975585f7f6349919f8f3b63391c52` |

板端原厂备份为 `/data/myautorun.sh.vendor-backup`，项目内备份为 `vendor-myautorun-original.sh`。安装和恢复命令：

```sh
./tools/device/install-autostart.sh
./tools/device/restore-vendor-autostart.sh
```

## 证据文件

- `helmet-stage1-final-reboot.log`：自动启动、版本、TTS 和离线状态日志。
- `helmet-stage1-final.log`：音量、提示音和 SOS 状态转换日志。
- `helmet-process-recovery.log`：进程终止和系统重建日志。
- `helmet-service-after-recovery.txt`：恢复后的前台服务状态。
- `helmet-runtime-after-reboot.log`、`helmet-service-after-reboot.txt`：首次兼容启动验证。
- `vendor-myautorun-original.sh`：原厂脚本备份。

开发板系统时间比主机当前日期落后，日志中的板端日期为 2026-07-29。时间同步在定位与网络阶段处理，最终事件时间验收不得使用当前板端时间作为正确时间证据。
