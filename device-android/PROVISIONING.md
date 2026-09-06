# Android 设备本机配置

release APK 安装或覆盖安装后，从开发板启动器打开 `Smart Helmet`。导出的启动 Activity
只启动前台服务并打开应用内的非导出配置页，不接收配置参数。配置页保留系统权限入口，release
不提供模拟硬件、模拟事件或诊断注入入口。

## 首次配置

按页面顺序配置以下内容：

1. 授予音频权限，以及设备实际存在的相机、定位和蓝牙权限。拒绝后可从页面再次申请；选择“不再询问”后从 Android 应用设置授权。
2. release 的 HSL 固定为 `/dev/ttyAS2`、115200、8N1，不提供路径输入。RTK 默认选择 `HSL`；确认独立接收机后，可在维护页选择 `DIRECT_UART4` 和白名单波特率，路径固定为 `/dev/ttyAS4`。测试 PTY 仅限 debug APK。
3. 填写人员编号。留空并保存会解除人员绑定。
4. 填写后台 HTTPS 根地址和设备 Bearer token。后台地址不得包含路径、用户信息、查询或片段。token 输入框不会显示既有值；留空保存时保留既有 token，使用清除按钮会同时清除地址和 token。
5. 如使用 MQTT 命令通道，先把正式客户端证书和私钥导入 Android KeyChain，再填写 `ssl://host:8883` 和证书别名。页面不接收或保存私钥。MQTT 地址和证书别名必须同时配置或同时清除。
6. 如使用 RTK，填写 HTTPS NTRIP mount-point URL、用户名和密码后启用。NTRIP URL 只允许一个 mount-point 路径段，不允许 URL 内嵌凭据、查询或片段。密码输入框不会显示既有值；留空保存时保留既有密码，禁用按钮会清除 URL、用户名和密码。
7. 配置本地对讲的启用状态、离线回退、组号、频道和模块密钥槽。密钥材料保留在外部模块，本应用只保存槽号。
8. 保存电子围栏 JSON 数组和完整的安全阈值 JSON。页面启动时会填入当前规范化 JSON；保存失败会在输入框和页面顶部显示原因。

每次成功保存都会增加配置版本并重启运行服务。配置版本达到 `Long.MAX_VALUE` 时保存和启动净化均会明确失败，不会回绕或以旧版本号覆盖新配置。后台配置保存后还会触发轨迹、媒体、通信和安全告警队列处理。

## release 启动净化

主进程在 `Application.attachBaseContext` 阶段同步净化运行配置。该阶段早于 Manifest
ContentProvider 和 WorkManager 初始化；`Application.onCreate` 会复用同一结果，然后才安装崩溃记录器和启动前台服务。release 会持久执行以下处理：

- 把调试遗留的 `simulatorEnabled=true` 改为真实硬件模式。
- 拒绝并清除 HTTP 后台地址；后台不可用时同时清除 Bearer token。
- 禁用并清除明文 HTTP 或其他非法 NTRIP 配置及其密码。
- 清除不完整或非 TLS 的 MQTT 配置。
- 把 HSL 测试 PTY、非 `/dev/ttyAS2` 路径或非 115200 波特率恢复为固定生产值。RTK UART4 路径始终固定，非法 RTK 波特率恢复为默认值。

净化写入使用 `RuntimeConfigStore.save`，因此禁用后台或 RTK 时，加密凭据存储也会收到空值并被清除。写入失败时 release 主进程拒绝继续初始化，不会让服务或 WorkManager 使用被拒绝的调试配置。重复启动不会反复增加配置版本。净化日志只记录字段名和配置版本，不记录 URL、用户名、token、密码或证书内容。

debug APK 只为 ADB 回环测试接受 `http://localhost`、`http://127.0.0.1` 或 IPv6 loopback；其他明文地址仍被拒绝。NTRIP 协议必须携带 mount point，因此该 URL 保留一个受限路径段；后台和 MQTT 地址不允许路径。

## 凭据和正式环境

运行配置与加密的后台、NTRIP 凭据分开存储。保存会对普通配置和两个凭据存储执行快照；任一写入失败时回滚已修改内容。读取时会隔离 SharedPreferences 类型错误、非法 JSON、越界值和凭据解密错误。恢复日志只包含字段名和问题类型。

不要把正式 token、NTRIP 密码、客户端证书或私钥写入仓库、命令历史和验证日志。正式 MQTT、NTRIP、TURN 和 TLS 参数由接入环境提供。覆盖安装保留应用私有配置和设备身份；卸载会清除二者，卸载后必须重新配置和绑定。
