# 阶段 2 验证

阶段 2 验证 Android 用户态硬件服务和外部模块协议，不包含外部模块内部软件。

## 已验证

- `native-hardware-service` 可在独立 `:hardware` 进程中打开和关闭白名单 UART 节点。
- HSL Kotlin 测试覆盖帧编码、CRC、流解析、错误恢复、去重、ACK、超时和重试。
- H618 上 `/dev/ttyAS2` 打开关闭通过，非法设备路径被拒绝。
- 未连接外部模块时，运行时按心跳超时进入降级和故障状态，主服务保持运行。
- Room 事件和运行配置在重启后保留。

## 证据

- `android-test-lint.log`：当时的 Android 单元测试和 Lint 记录。
- `connected-hardware-tests.log`：AIDL/JNI 串口测试。
- `connected-data-tests.log`：持久化测试。
- `board-uart-inventory.txt`：开发板 UART 节点清单。
- `uart-runtime-logcat.txt`：无模块时的运行状态。

## 限制

没有外部传感器、按键、LED、振动和正式接线。当前证据只证明 Android 用户态访问和协议行为，不证明真实模块收发或本地安全输出。若现有开发板接口不可访问，需要模块或开发板供应方提供可用接口。
