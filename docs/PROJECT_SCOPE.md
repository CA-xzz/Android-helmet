# 项目边界与精简记录

## 依据

- 需求基准：`最终版安全帽说明书.docx`。
- 目标设备：H618，Android 12，SDK 31。
- 产品主体：Android App。
- 配套范围：App 直接依赖的硬件接入、自动化测试所需的最小服务夹具、构建和板测工具。

## 删除前检查

2026-08-13 检查时，工作树已有大量未提交修改。Android App、硬件接入、算法、状态恢复和板测相关修改全部保留。后台、管理端、部署和全平台审计修改经内容检查后，均属于本次目标明确要求删除或精简的平台扩展，不作为保留理由。

精简前 Android debug、release APK 和用户态硬件模块 debug、release AAR 均构建通过。

## 保留

- `device-android/`：主 App、媒体采集、WebRTC、前台服务、按键处理、本地存储、权限、网络、定位、安全检测和对应测试。
- `native-hardware-service/`：UART/JNI/AIDL 用户态硬件接入和对应测试。
- `最终版安全帽说明书.docx`：原始需求基准。
- Gradle Wrapper、根构建文件和 Android 构建配置。
- `backend/media_service.py`：仅作为 App 自动化测试和本地联调所需的服务夹具。
- Android 构建、安装、定向测试和 H618 验证脚本。
- App 架构、硬件协议、最小后台协议、测试和联调文档。

## 精简

- 后台只保留共享开发令牌、设备上行、媒体上传、设备命令、文字广播和 WebRTC 会话/信令所需的最小能力。
- README、架构、协议、测试计划和需求追踪只描述 Android App 与测试夹具。
- 板测脚本只保留 App 构建、安装、定向仪器测试、前台服务和硬件输入验证。

## 删除

- 多组织、组织树、多租户、复杂角色权限和安全审计。
- 复杂设备总览、运营地图、告警处置、媒体归档检索和后台通话管理页面。
- PostgreSQL、S3、MQTT 双向 TLS、Caddy 和容器化生产部署栈。
- 命令流后台运维、数据库适配器、对象存储适配器和 MQTT 网关。
- 复杂后台、管理端、部署和全平台审计专用测试。
- Web 管理和查看页面。
- 历史阶段证据、浏览器截图、数据库、对象、日志、全平台最终审计和 Linux 迁移审计资料。
- 失效、重复或只服务已删除平台能力的脚本与文档。

## Android 直接依赖

Android 当前直接调用以下最小服务接口：

- `POST /v1/device-status`
- `POST /v1/tracks:batch`
- `POST /v1/alerts`
- `POST /v1/media/sessions`
- `POST /v1/media/sessions/{id}/chunks`
- `POST /v1/media/sessions/{id}/complete`
- `POST /v1/calls`
- `POST /v1/calls/{id}/transitions`
- `GET /v1/calls/{id}/ice-config`
- `GET/POST /v1/calls/{id}/signals`
- `GET /v1/device-commands`
- `POST /v1/device-commands/{id}/ack`
- `POST /v1/broadcasts/{id}/receipts`

这些接口用于 App 功能和联调，不构成独立企业管理平台。

H618 定向测试另使用 `GET /v1/tracks`、`GET /v1/alerts/{id}` 和 `GET /v1/media/{id}` 核对刚上报的数据。这些接口不提供运营检索功能。

## 删除安全记录

- 未使用 `git reset`、`git checkout` 或 `git clean`。
- 未删除仓库外文件或开发板数据。
- Android 和硬件相关未提交修改全部保留。
- 未创建 Git commit。
- 大目录和生成缓存采用可恢复移动，位置为 `/Users/caxzz/.Trash/android-helmet-scope-20260813.jLPzV7` 和 `/Users/caxzz/.Trash/android-helmet-generated-20260813.daJVhW`。
