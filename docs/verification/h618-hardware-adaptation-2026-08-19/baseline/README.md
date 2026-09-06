# H618 修改前基线

本目录保存 2026-08-19 在序列号 `2c001031774186e21d3` 上采集的只读基线。采集未修改设备树、BSP、GPIO、`/data/myautorun.sh` 或主应用数据，输出已过滤凭据。

主要事实：

- Android 12、API 31，型号 `QUAD-CORE H618 p1`，内核 5.4.125。
- `/dev/ttyAS0` 至 `/dev/ttyAS4` 存在。`ttyAS2` 对应 UART2 和 PI5/PI6，`ttyAS4` 对应 UART4 和 PI13/PI14。
- UART1 实时 pinmux 使用 PG6、PG7、PG8、PG9。
- 只有 `/dev/i2c-1`，其客户端为 PCF8563；没有 i2c-0 或 i2c-2。
- CameraService 枚举 0 个摄像头，SensorService 没有传感器，系统未声明麦克风。
- 没有 `/dev/video*`。ALSA 存在 `audiocodec`、`ahubdam` 和 `ahubhdmi`，但不能证明目标 codec 和麦克风链路可用。
- gpiochip 基址为 0、数量为 288。`gpio203` 已由 `/data/myautorun.sh` 导出为 output-high；本次只读取，未改变其值或脚本。
- 主应用及硬件服务进程在采集时运行。

采集期间 ADB 曾短暂断开，因此部分首次补充文件记录了退出码 1 或 255。对应的 `*-retry.txt` 是连接恢复后的成功结果，保留原文件是为了维持证据顺序。首次 `device-tree-inventory.txt` 没有跟随 `/proc/device-tree` 符号链接；`live-dt-relevant-nodes.txt`、`live-dt-relevant-properties.txt` 和 pinctrl retry 文件提供了成功的实时设备树证据。
