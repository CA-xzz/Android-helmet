# H618 开发板基线记录

采集日期：2026-08-10

ADB 序列号：`2c001031774186e21d3`

## 系统

```text
model=QUAD-CORE H618 p1
board=exdroid
android=12
sdk=31
kernel=Linux 5.4.125 aarch64
buildType=eng
buildTags=test-keys
ro.debuggable=1
selinux=Permissive
memory=1982684 kB
dataAvailable≈11 GB
```

## 节点和权限

```text
/dev/ttyS0..3   system:system 0666
/dev/i2c-1      root:root 0600
/dev/gpiochip0  root:root 0600
/dev/gpiochip1  root:root 0600
/dev/ttyUSB*    不存在
/dev/video*     不存在
```

后续阶段 2 检查确认，以上 `/dev/ttyS0..3` 是 serial8250 占位节点。实际 Allwinner UART 为 `/dev/ttyAS0..4`，其中 `ttyAS0` 是控制台、`ttyAS1` 为蓝牙、`ttyAS2..4` 为候选。以 `verification/stage-2/board-uart-inventory.txt` 为准。

## Android 服务结果

- CameraService：External Camera HAL 已运行，摄像头数量为 0。
- SensorService：`No Sensors on the device`。
- BatteryService：`present=false`、level 0、voltage 0。
- LocationService：只有 passive 和 fused provider，无 GNSS provider，无最后位置。
- Wi-Fi：HAL 已运行，当前 Disabled，未建立 `wlan0` 数据链路。
- 网络：`eth0` 存在但 DOWN。
- 音频：发现 audiocodec、ahubdam、ahubhdmi；存在播放 PCM 和一个 AHUB 采集 PCM，真实输入输出未验证。
- RIL：Quectel RIL 服务运行，但无蜂窝 USB 设备，网络类型 Unknown。

## 当前开机脚本

`/data/myautorun.sh` 会导出 GPIO 203、置高电平，等待开机完成后启动 `com.hy.yesobox.NoScreenActivity`。本阶段未修改该脚本。

## 判定

该板卡可用于 Android 应用、UART 原型和基础音频路径开发。摄像头、GNSS/RTK、蜂窝、真实传感器、电池、LoRa 语音和本地安全输出均缺少外设，不能形成相应最终验收证据。
