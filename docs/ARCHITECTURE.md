# 架构

## 运行边界

```text
H618 Android App
  ├─ 前台服务：按键、状态机、恢复、语音反馈
  ├─ Camera2 / Audio / Connectivity / Storage
  ├─ Room：配置、队列、轨迹、告警、媒体和呼叫
  ├─ WebRTC：麦克风、摄像头、Offer/Answer/ICE
  ├─ HSL AIDL → JNI → /dev/ttyAS2 → 外部模块
  └─ RTK AIDL → JNI → /dev/ttyAS4 → RTK 接收机（可选）
              │
              └─ HTTP
                 └─ SQLite 本地联调服务
```

Android App 是产品主体。仓库中的轻量 HTTP 服务只作为 App 自动化测试和本地联调夹具，不是生产后台，也不包含 Web 管理或查看页面。

## Android 模块

- `app`：配置页、权限申请、状态显示和 debug 测试入口。
- `service-runtime`：前台服务、硬件事件、媒体动作、WebRTC 协调、广播、告警和恢复。
- `core-model`、`core-protocol`：共享模型和 HSL 协议。
- `data-local`：Room、加密配置和持久队列。
- `feature-camera`、`feature-location`、`feature-connectivity`：Android 标准能力。
- `safety-detection`：跌落、冲击、晃动、近电和高度判断。
- `media-sync`、`location-sync`、`alert-sync`、`communication-sync`：HTTP 上行和命令同步。
- `webrtc-runtime`：真实音频轨、可用时的视频轨及会话资源。
- `hardware-api`、`native-hardware-service`：跨进程硬件接口和 UART 原生层。

HTTP 轮询是最小联调服务的默认命令通道。App 中现有的 MQTT mTLS 客户端只作为可选的直接命令唤醒适配器；本仓库不提供 MQTT 网关、Broker 或生产部署栈，未配置时不启动该客户端。

## 按键音视频路径

呼叫键生成持久动作。无活动通话时创建上行视频呼叫，有活动通话时结束呼叫。后台接听后，App 获取 ICE 配置、创建麦克风和可用摄像头轨、发送 Offer，随后处理 Answer 和 ICE。状态统一为 `IDLE`、`STARTING`、`STREAMING`、`STOPPING`、`FAILED`。

麦克风权限或硬件不存在时启动失败，不能报告成功。摄像头不可用时明确显示 `video=false`，音频仍可单独建立。结束、失败、服务关闭和新呼叫替换路径均关闭 PeerConnection、音视频轨、Camera capturer、SurfaceTextureHelper 和 EGL 资源。

## 离线与恢复

状态、轨迹、媒体、告警、呼叫和广播回执先写入本地持久队列。网络恢复后 Worker 分批重试。按键动作在副作用前持久化，进程恢复后按原参数继续，避免重复拍照、录制、呼叫或音量变化。

## 硬件

用户态硬件服务只允许受控 UART 节点。HSL 与独立 RTK 使用不同 AIDL、文件描述符、读取线程、状态和重连流程。HSL 解码、HELLO 契约与能力校验、可靠确认、事件去重、模块重启和链路重连在 App 内处理。无对应外设时保留软件接口和测试模拟器，但不把模拟结果记为真实硬件通过。

`hardware-api` 中的 H618 板型配置是硬件契约唯一数据源，保存契约版本、Excel 行号、逻辑能力、接入方式、固定端点、责任方和验收规则。构建时生成固定 JSON 和 Markdown，并检查 Excel 行遗漏或重复、引脚冲突、端点冲突、HSL/RTK 串口隔离及未确认控制误启用。冲突和证据不足的资源不能启用。

主进程通过 Android 标准服务和只读板型探针生成 UI 状态。Camera、Audio、Connectivity、Sensor、Storage 和 Input 的系统结果是能力证据；UART 心跳、有效 RTK Fix 和真实挂载结果是运行证据。`File.exists()`、模拟样本或 PTY 结果不能把外设状态提升为运行。

电源脉冲、上电回滚、按键消抖、长短按判定、LED 优先级和 MMA 坐标换算在 `hardware-api` 中保持为纯状态机。未确认 GPIO 不连接执行后端。硬件确认后，实际 GPIO 和电源资源仍应由 BSP 或独立硬件服务独占，Activity 不直接访问。
