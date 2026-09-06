# H618 硬件适配验证结果

验证日期：2026-08-19。目标设备：`2c001031774186e21d3`，`QUAD-CORE H618 p1`，Android 12，API 31。

## 实现结果

- `H618BoardProfile` 覆盖硬件表第 2 至 45 行，保存引脚、端点、owner、方向、电平、安全态、时序、控制策略和验证状态。
- 冲突校验覆盖重复启用引脚、重复设备节点、HSL/RTK 串口隔离和不完整输出时序。
- 未确认 GPIO 全部禁用。测试过程未改变 GPIO、设备树、BSP 或 `/data/myautorun.sh`。
- 新增脉冲复位、上电回滚、按键消抖与长短按、LED 优先级、MMA 单位和坐标转换状态机。
- UI 新增 H618 资源状态。设备节点存在不等于运行；缺失外设保持等待外设、驱动缺失、冲突或未配置。
- 设备检测状态卡支持方向键焦点和自动滚动，720p 无触摸设备可查看完整列表。

## 主机回归

| 命令 | 结果 |
|---|---|
| `./tools/build-android.sh` | 通过，debug/release 和 release R8 检查完成 |
| `./tools/test-android.sh` | 通过，1815 项 Gradle 任务完成 |
| `./tools/test-backend.sh` | 通过，后端 11 项及工具脚本 34 项通过 |
| H618 板型和安全状态机单元测试 | 通过 |
| 设备检测方向键专项 androidTest | 通过，1 项 |

首次完整 Android 测试在 `hardware-api` 的 Gradle 可再生中间目录报告重复 `AxisSource.class`。源码只有一个定义。执行模块 `clean` 后完整测试通过，未修改用户数据或源码历史。

## H618 联机测试

指定命令通过：

```sh
./tools/run-board-connected-software-tests.sh \
  --adb-serial 2c001031774186e21d3 \
  --hardware-device-path /dev/ttyAS2 \
  --hardware-baud-rate 115200
```

| 测试组 | 数量 |
|---|---:|
| data-local | 120 |
| feature-camera | 6 |
| feature-connectivity | 1 |
| feature-location | 1 |
| safety-detection | 3 |
| webrtc-runtime | 2 |
| service-runtime-offline | 25 |
| app-manifest-policy，含 H618 被动探针 | 5 |
| app-real-hardware-mode-preparation | 1 |
| app-hardware-provider-safe | 5 |
| 合计 | 169 |

硬件安全自检通过 5 项：策略限制、PTY 打开、并发帧、代际隔离和非法路径拒绝。PTY 和模拟结果只证明软件路径，不作为真实外设通过。

## 安装、配置和重启

- 主应用仅使用 `adb -s 2c001031774186e21d3 install -r` 更新，没有卸载或清除数据。
- 最终 debug APK SHA-256 为 `13a7d5549b8534743eaad3f457de5c78bbc5ff2a0185ff5eb844a5394e402abf`。板端 `base.apk` 哈希与本地一致。
- 应用版本为 0.3.0，versionCode 3。
- 最终整板回归后的正常重启前 boot ID 为 `178e95c2-a1d5-4fcf-814b-060efae46599`，重启后为 `9985e28c-85c2-42d4-a08f-0a53754abb97`。
- `sys.boot_completed=1` 后首次检查即发现主进程、独立硬件进程和 `HelmetService isForeground=true`，没有手动恢复服务。
- 重启后的持久配置判定为 `changed=false`、revision 11、`/dev/ttyAS2`、115200、`simulator_enabled=false`。

## 最终状态和限制

- CameraService 枚举 0 个摄像头。
- 系统未声明麦克风，没有确认目标 NAU88C2x codec。
- SensorService 没有 MMA8452 或其他系统传感器。
- 没有已验证蜂窝网络、可移动存储卷或可确认的 RTK 接收机数据。
- PI4、PI5/PI6 和 PG8/PG9 冲突保持禁用。LED、按键、PG13、PG10、codec 型号和 RTK 拓扑保持未配置。
- 测试包、测试结果、ADB reverse 和板端截图临时文件均已清理。主应用、现场配置和业务数据保留。
- 板卡系统时间仍为 2026-07-29，早于验证日期。联网或量产授时后需要复核。

## 截图

- [重启前设备总览](h618-hardware-adaptation-before-reboot.png)
- [3.3 V 状态](h618-board-status-before-reboot.png)
- [Camera 电源冲突和方向键滚动](h618-board-status-focus-lower-before-reboot.png)
- [codec、4G、MMA、RTK、SD、按键、LED 和 HSL](h618-board-status-focus-final-before-reboot.png)
- [正常重启后最终总览](h618-after-normal-reboot-final.png)
- [最终整板回归和再次重启后的总览](h618-after-final-board-reboot.png)

修改前证据见 [baseline](baseline/README.md)。
