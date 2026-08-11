# 阶段 5 验证记录

日期：2026-08-10；补充验证：2026-08-11

设备：H618 Android 12，序列号 `2c001031774186e21d3`

## 结论

阶段 5 软件门禁通过。呼叫状态、WebRTC 信令、短期 TURN 凭据、HTTP 设备命令自动轮询、文字广播回执、语音消息权限，以及专用 LoRa 语音模块入组、密钥槽、半双工 PTT、状态和质量遥测均有自动测试或开发板夹具记录。新增夹具证据见 `../stage-7/rtk-local-intercom-board-test.txt`。`webrtc-bandwidth-policy-board-test.txt` 进一步验证初始网络快照、通话建立前弱网策略保留和 PeerConnection 带宽模式切换。开发板没有摄像头、可用音频采集节点、已验证扬声器、异机浏览器、生产 TURN 或专用无线语音硬件，因此真实双向音视频、弱网恢复、距离、穿透、时延和可懂度尚未验收。

WebRTC 依赖为 `io.github.webrtc-sdk:android:144.7559.09`。arm64 原生库可在开发板加载。板端探针明确设置 `captureAudio=false`，只验证 PeerConnection 初始化、DTLS-SRTP SDP Offer 和释放流程。`dashboard-webrtc-board-e2e.txt` 补充验证管理端接听后 H618 无需手工服务指令即可自动提交 Offer，浏览器提交 Answer 和 ICE，H618 应用 Answer，挂断后回到 `OFFLINE_READY`。`webrtc-connected-recovery-board-test.txt` 验证服务从持久 `CONNECTED` 状态恢复时创建新 Offer，管理端按后台返回的精确序号选择该 Offer，设备跳过旧 Answer。两项闭环均没有建立真实媒体连接。

## 自动检查

- `android-test-lint-final.log`：Android 单元测试与 Lint 共 638 个任务通过。
- `backend-tests-final.log`：后台 9 项测试通过。
- `board-data-local-final.log`：开发板数据层 11 项测试通过。
- `board-webrtc-runtime-final.log`：开发板 WebRTC 2 项测试通过。
- `board-service-runtime-final.log`：开发板运行服务 3 项测试通过。

## 开发板和后台证据

| 证据 | 内容 |
|---|---|
| `board-backend-webrtc-query.txt` | 呼叫会话、短期 ICE 配置和序号为 1 的 Offer 信令 |
| `board-backend-communication-query.txt` | 呼叫状态、设备命令、文字广播和播放回执 |
| `android-http-command-polling-board-test.txt` | 无 MQTT、无 Android 默认网络时，通过 ADB 回环自动获取 HTTP 命令；覆盖安装后按序处理接听、挂断、拒绝和文字广播，并提交回执和命令确认 |
| `board-main-database-query-final.txt` | Room v5 表结构和主应用迁移结果 |
| `board-main-service-restarted-final.txt` | 最终 APK 重启后的前台服务状态 |
| `board-install-final.txt` | 最终 APK 覆盖安装结果 |
| `apk-sha256-final.txt` | APK SHA-256：`0708ba64454d801a07c8c16db7f5c3f29dc109da17a62b220216a687a2ec3641` |
| `webrtc-bandwidth-policy-board-test.txt` | H618 验证初始无网络快照入库、WebRTC 原生库、DTLS-SRTP Offer、低带宽与普通模式切换；当前 APK SHA-256 为 `5b4626026569958cb8d554c803099a7975f534c90e246401ce1baf68c59dd9a1` |
| `dashboard-webrtc-board-e2e.txt` | H618、后台和同源浏览器完成 REQUESTED、ACCEPTED、CONNECTING、ENDED 与 Offer、Answer、ICE 信令闭环；APK SHA-256 为 `fe8a971cba23ae2237bbbdbe32e2221cdd0bda37a3850e0cad65847da0f0a2c1` |
| `webrtc-connected-recovery-board-test.txt` | H618 从持久 `CONNECTED` 呼叫恢复，创建序号 5 的新 Offer；管理端提交序号 6 的新 Answer，设备跳过旧 Answer，挂断后恢复 `OFFLINE_READY`；APK SHA-256 为 `f82c157264a5b28f046de23a9788aac6aa856693ada509fe214925e09b9315ce` |

文字广播在没有 TTS 引擎的开发板上按序产生 `RECEIVED`、`PLAYING` 和 `FAILED` 回执，失败原因为 `TTS_UNAVAILABLE`。该结果证明失败可观测，不证明扬声器播放。

## 语音消息证据

`voice-message-fixture.wav` 是 16 kHz、单声道、16 位 PCM WAV，时长约 1.747 秒，大小 59998 字节。上传和下载 SHA-256 均为 `efe256e8223ca2636f5c8b683ee420bde4e04611549c1c74765ea9822dd66b07`。

- `voice-upload-session.json`、`voice-upload-chunk.json`、`voice-upload-complete.json`：断点上传和完成记录。
- `voice-query.json`、`voice-playback-headers.txt`：调度角色检索与内容下载。
- `voice-sha256.txt`：本地文件与下载内容摘要一致。
- `voice-forbidden-status.txt`、`voice-forbidden-response.json`：越权角色被 403 拒绝。
- `voice-backend-query.txt`：后台消息和读取、播放审计记录。

阶段 7 补充了 Android 到管理端的语音软件闭环，包括 VOICE 媒体模型、Room v7、授权元数据和 H618 仪器测试。开发板生成 48,044 字节的 16 kHz 单声道 PCM 合成 WAV，经真实 `MediaUploadWorker` 上传；本地 schema 2 管理员浏览器完成认证读取、摘要核对、解码和播放控件验证，VIEWER 与跨组织访问被隔离。完整记录和截图见 `../stage-7/dashboard-voice-player-regression.txt`。该结果不是麦克风录音或扬声器声学证据。

## 限制

- 音频 HAL 指向不存在的 `/dev/snd/pcmC16D0c`，不能采集真实麦克风音频。
- CameraService 报告 0 个摄像头。
- STUN/TURN 使用本地测试配置，没有验证公网 NAT 穿透或真实中继。
- 同源浏览器已建立 PeerConnection 并提交 Answer，但 ICE 只到 `CHECKING`，没有远端视频轨道或媒体连接。
- 没有专用无线语音硬件，不能执行覆盖距离、遮挡、时延、丢包和可懂度测试。
- 开发板系统时间落后于主机，短期凭据校验采用服务器签发时间保护测试有效期；生产环境仍需可靠 RTC 或网络校时。
- 不带 `-final` 的早期日志保留诊断过程，其中包含已修复的测试失败，不作为门禁证据。

阶段 5 软件门禁通过不改变各 REQ 的最终完成判定。真实硬件和端到端证据齐备前，Goal 保持进行中。
