# 当前状态

更新时间：2026-08-20。

## 范围

当前目标只开发 Android App，包括 App 内独立硬件进程、测试工具和硬件交接文档。未修改硬件、设备树、BSP、系统镜像或启动脚本。

## 已实现

- `HELMET_v3_footpin.xlsx` 第 2 至 45 行已逐行进入 H618 Kotlin 板型契约，构建检查遗漏、重复、冲突和未确认控制。
- HSL 固定为 `/dev/ttyAS2`、115200、8N1。模块必须完成 HELLO 契约和能力握手，再通过心跳进入可用状态。
- RTK 默认通过 HSL，同时支持受控配置选择独立 `/dev/ttyAS4`。两个串口运行时完全隔离。
- Camera2、Audio、ConnectivityManager 和 StorageManager 继续作为摄像头、音频、4G 和存储的标准 Android 接口。
- 设备检测显示契约版本、模块固件、能力、RTK 模式和逐项状态。模拟或 PTY 结果不会记录为真实硬件通过。
- 固定 JSON 和 Markdown 交接文件由 Kotlin 契约生成，测试会阻止文档漂移。
- 完整 Android 测试和构建通过；指定 H618 联机软件回归 174 项通过。普通重启后主进程、独立硬件进程和前台服务自动恢复，主应用数据保持不变。

## 当前开发板限制

- 当前没有真实摄像头、有效麦克风、已验证蜂窝网络、MMA8452、RTK 接收机、按键和 LED 模块。
- 因此只能确认 App 软件路径、协议、恢复和安全降级，不能标记整机硬件验收通过。
- 硬件按 `hardware-contract/H618_APP_HARDWARE_CONTRACT.md` 实现并关闭确认项后，App 不需要修改源码，只需选择 RTK 模式并完成实物验收。
- 本轮验收记录位于 `verification/hardware-contract-app-2026-08-20/README.md`。
