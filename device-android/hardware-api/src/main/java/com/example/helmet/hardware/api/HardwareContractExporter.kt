package com.example.helmet.hardware.api

import com.example.helmet.core.protocol.HslCapability
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFrameCodec
import com.example.helmet.core.protocol.HslHelloCodec
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslModuleHello

/** Deterministic hand-off artifacts generated from the same profile used by the App. */
object HardwareContractExporter {
    fun toJson(profile: BoardProfile): String = buildString {
        appendLine("{")
        appendLine("  \"profileId\": ${profile.id.json()},")
        appendLine("  \"model\": ${profile.model.json()},")
        appendLine("  \"contractVersion\": ${profile.contractVersion.toString().json()},")
        appendLine("  \"spreadsheetRows\": [${profile.spreadsheetRows.joinToString()}],")
        appendLine("  \"appBoundary\": \"Android framework APIs, HSL UART2, and optional direct RTK UART4\",")
        appendLine("  \"hsl\": {")
        appendLine("    \"devicePath\": \"/dev/ttyAS2\",")
        appendLine("    \"baudRate\": 115200,")
        appendLine("    \"serialFormat\": \"8N1\",")
        appendLine("    \"helloMessageType\": 2,")
        appendLine("    \"helloSchemaVersion\": ${HslHelloCodec.SCHEMA_VERSION},")
        appendLine("    \"helloPayloadBytes\": ${HslHelloCodec.ENCODED_SIZE},")
        appendLine("    \"helloTimeoutMillis\": 5000,")
        appendLine("    \"capabilities\": [")
        CAPABILITY_DEFINITIONS.forEachIndexed { index, capability ->
            append("      {\"bit\": ${capability.bit}, \"mask\": ${capability.mask}, ")
            append("\"name\": ${capability.name.json()}, \"requiredBase\": ${capability.requiredBase}, ")
            append("\"use\": ${capability.use.json()}}")
            if (index != CAPABILITY_DEFINITIONS.lastIndex) append(',')
            appendLine()
        }
        appendLine("    ]")
        appendLine("  },")
        appendLine("  \"rtkModes\": [")
        appendLine("    {\"name\": \"HSL\", \"default\": true, \"endpoint\": \"/dev/ttyAS2\", \"framed\": true},")
        appendLine("    {\"name\": \"DIRECT_UART4\", \"default\": false, \"endpoint\": \"/dev/ttyAS4\", \"framed\": false}")
        appendLine("  ],")
        appendLine("  \"resources\": [")
        profile.resources.sortedBy(BoardResourceDefinition::id).forEachIndexed { index, resource ->
            appendLine("    {")
            appendLine("      \"id\": ${resource.id.json()},")
            appendLine("      \"module\": ${resource.module.json()},")
            appendLine("      \"spreadsheetRows\": [${resource.spreadsheetRows.sorted().joinToString()}],")
            appendLine("      \"socPins\": ${resource.socPins.sorted().jsonArray()},")
            appendLine("      \"endpoints\": ${resource.endpoints.sorted().jsonArray()},")
            appendLine("      \"integrationOwner\": ${resource.integrationOwner.name.json()},")
            appendLine("      \"capabilities\": ${resource.capabilities.map { it.name }.sorted().jsonArray()},")
            appendLine("      \"hardwareResponsibility\": ${resource.hardwareResponsibility.json()},")
            appendLine("      \"direction\": ${resource.direction.name.json()},")
            appendLine("      \"activeLevel\": ${resource.activeLevel.name.json()},")
            appendLine("      \"safeState\": ${resource.safeState.name.json()},")
            appendLine("      \"required\": ${resource.required},")
            appendLine("      \"enabled\": ${resource.enabled},")
            appendLine("      \"requiresHardwareConfirmation\": ${resource.requiresHardwareConfirmation},")
            appendLine("      \"readinessEvidence\": ${resource.readinessEvidence.json()},")
            appendLine("      \"readinessTimeoutMillis\": ${resource.readinessTimeoutMillis ?: "null"},")
            appendLine("      \"defaultAvailability\": ${resource.defaultAvailability.name.json()},")
            appendLine("      \"notes\": ${resource.notes.json()}")
            append("    }")
            if (index != profile.resources.lastIndex) append(',')
            appendLine()
        }
        appendLine("  ],")
        appendLine("  \"hardwareQuestions\": ${profile.hardwareQuestions.jsonArray()}")
        appendLine("}")
    }

    fun toMarkdown(profile: BoardProfile): String = buildString {
        appendLine("# H618 App 硬件对接契约")
        appendLine()
        appendLine("- 板型：`${profile.id}`")
        appendLine("- 契约版本：`${profile.contractVersion}`")
        appendLine("- 原始清单行：`${profile.spreadsheetRows.first}-${profile.spreadsheetRows.last}`")
        appendLine("- App 边界：Android 标准接口、HSL UART2 和可选独立 RTK UART4。")
        appendLine("- App 不直接切换 GPIO、I2C、I2S、SD 或 USB 电源。")
        appendLine()
        appendLine("## 接口映射")
        appendLine()
        appendLine("| 行 | 资源 | 引脚 | App 接口 | 能力 | 必需 | 当前策略 | 验收依据 |")
        appendLine("|---|---|---|---|---|---|---|---|")
        profile.resources.sortedWith(
            compareBy<BoardResourceDefinition> { it.spreadsheetRows.minOrNull() ?: Int.MAX_VALUE }
                .thenBy(BoardResourceDefinition::id),
        ).forEach { resource ->
            append("| ${resource.spreadsheetRows.sorted().joinToString().ifBlank { "App" }.cell()} ")
            append("| ${resource.id.cell()} ")
            append("| ${resource.socPins.sorted().joinToString().ifBlank { "无" }.cell()} ")
            append("| ${(resource.integrationOwner.name + ": " + resource.endpoints.sorted().joinToString()).cell()} ")
            append("| ${resource.capabilities.map { it.name }.sorted().joinToString().ifBlank { "无" }.cell()} ")
            append("| ${if (resource.required) "是" else "否"} ")
            append("| ${(if (resource.enabled) "启用" else "禁用") + "/" + resource.controlPolicy.name} ")
            appendLine("| ${resource.readinessEvidence.cell()} |")
        }
        appendLine()
        appendLine("## HSL 量产握手")
        appendLine()
        appendLine("- 串口固定为 `/dev/ttyAS2`、115200、8N1。串口节点存在不等于可用。")
        appendLine("- 模块每次启动或串口重连后，必须先发送 `HELLO 0x02`，并设置 `ACK_REQUIRED`。")
        appendLine("- HELLO 载荷固定 20 字节：`schema[1] + contract[3] + capabilities[8] + firmware[3] + hardwareRevision[1] + bootSessionId[4]`，多字节字段为小端序。")
        appendLine("- App 要求契约主版本一致、模块次版本不低于 App 次版本、启动会话号非零，并且当前配置所需能力全部存在。")
        appendLine("- 兼容 HELLO 通过后还必须收到有效心跳，App 才会执行输出、传感器、RTK 或对讲操作。")
        appendLine("- 全能力 HELLO 示例载荷：`${helloPayload(profile).hex()}`。")
        appendLine("- 完整帧示例，序号 1：`${helloFrame(profile).hex()}`。")
        appendLine()
        appendLine("| 位 | 能力 | 基础必需 | 用途 |")
        appendLine("|---|---|---|---|")
        CAPABILITY_DEFINITIONS.forEach { capability ->
            appendLine("| ${capability.bit} | ${capability.name} | ${if (capability.requiredBase) "是" else "按配置"} | ${capability.use} |")
        }
        appendLine()
        appendLine("## RTK 路径")
        appendLine()
        appendLine("| 模式 | 端点 | 数据 | 启用条件 |")
        appendLine("|---|---|---|---|")
        appendLine("| `HSL`，默认 | `/dev/ttyAS2` | `RTK_NMEA 0x31` 输入，`RTK_CORRECTION 0x32` 输出 | HELLO 声明对应能力并通过心跳 |")
        appendLine("| `DIRECT_UART4` | `/dev/ttyAS4` | 原始 NMEA 输入和原始 RTCM3 输出 | 硬件确认波特率后通过 App 受控配置选择 |")
        appendLine()
        appendLine("HSL 和 UART4 由硬件进程中的独立服务、文件描述符、读取线程、状态和重连流程持有。release 版本固定端点，不能输入任意串口或执行 shell。两种路径汇入相同的 NMEA、定位、RTCM3 和 NTRIP 校验流程。")
        appendLine()
        appendLine("## 状态和验收判定")
        appendLine()
        appendLine("| 接口 | 可用或运行依据 | 不通过状态 |")
        appendLine("|---|---|---|")
        appendLine("| 摄像头 | Camera2 枚举 USB UVC 摄像头并完成实际采集 | 等待外设、权限不足、驱动缺失、明确故障 |")
        appendLine("| 音频 | Android Audio API 录到非零数据并可输出 | 等待外设、权限不足、驱动缺失、明确故障 |")
        appendLine("| 4G | ConnectivityManager 报告经过验证的蜂窝网络 | 等待外设、驱动缺失、明确故障 |")
        appendLine("| SD1/SD2 | StorageManager 枚举挂载卷并完成读写 | 等待外设、权限不足、驱动缺失、明确故障 |")
        appendLine("| HSL | HELLO 兼容、必需能力齐全、心跳有效 | 等待外设、协议不兼容、数据过期、权限不足、明确故障 |")
        appendLine("| RTK | NMEA 校验通过并形成有效定位 | 等待外设、未配置、数据过期、协议不兼容、明确故障 |")
        appendLine()
        appendLine("## 断线恢复")
        appendLine()
        appendLine("- HSL 或 UART4 断开后关闭原文件描述符，并按 250 ms 起步、最长 30 s 的退避重新打开。")
        appendLine("- HSL 重连后必须重新完成 HELLO 和心跳；旧启动会话的数据和确认不得沿用。")
        appendLine("- UART4 重连不占用 `/dev/ttyAS2`，HSL 重连也不占用 `/dev/ttyAS4`。")
        appendLine("- 未确认控制、冲突引脚和任意 GPIO 操作保持禁用。模拟端结果只证明软件协议路径，不计为真实硬件验收。")
        appendLine()
        appendLine("## 硬件方确认项")
        appendLine()
        profile.hardwareQuestions.forEachIndexed { index, question ->
            appendLine("${index + 1}. [ ] $question")
        }
        appendLine()
        appendLine("全部确认项关闭并完成真实外设测试前，不得把对应接口标记为硬件验收通过。")
        appendLine()
        appendLine("## 联调签字")
        appendLine()
        appendLine("| 角色 | 姓名 | 日期 | 契约版本 | 结论 |")
        appendLine("|---|---|---|---|---|")
        appendLine("| 硬件负责人 |  |  | `${profile.contractVersion}` |  |")
        appendLine("| BSP 负责人 |  |  | `${profile.contractVersion}` |  |")
        appendLine("| Android App 负责人 |  |  | `${profile.contractVersion}` |  |")
    }

    private fun helloPayload(profile: BoardProfile): ByteArray = HslHelloCodec.encode(
        HslModuleHello(
            contractMajor = profile.contractVersion.major,
            contractMinor = profile.contractVersion.minor,
            contractPatch = profile.contractVersion.patch,
            capabilityMask = HslCapability.KNOWN_MASK,
            firmwareMajor = 1,
            firmwareMinor = 0,
            firmwarePatch = 0,
            hardwareRevision = 1,
            bootSessionId = 1,
        ),
    )

    private fun helloFrame(profile: BoardProfile): ByteArray = HslFrameCodec.encode(
        HslFrame(
            flags = HslFlags.ACK_REQUIRED,
            type = HslMessageType.HELLO,
            sequence = 1,
            payload = helloPayload(profile),
        ),
    )

    private fun String.json(): String = buildString {
        append('"')
        this@json.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }

    private fun Collection<String>.jsonArray(): String = joinToString(prefix = "[", postfix = "]") { it.json() }

    private fun String.cell(): String = replace("|", "\\|").replace("\n", " ")

    private fun ByteArray.hex(): String = joinToString(" ") { byte ->
        (byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
    }

    private data class CapabilityDefinition(
        val bit: Int,
        val mask: Long,
        val name: String,
        val requiredBase: Boolean,
        val use: String,
    )

    private val CAPABILITY_DEFINITIONS = listOf(
        CapabilityDefinition(0, HslCapability.PHYSICAL_KEYS, "PHYSICAL_KEYS", true, "物理按键事件"),
        CapabilityDefinition(1, HslCapability.LED_OUTPUT, "LED_OUTPUT", true, "LED 输出及确认"),
        CapabilityDefinition(2, HslCapability.VIBRATION_OUTPUT, "VIBRATION_OUTPUT", true, "振动输出及确认"),
        CapabilityDefinition(3, HslCapability.BUZZER_OUTPUT, "BUZZER_OUTPUT", true, "蜂鸣输出及确认"),
        CapabilityDefinition(4, HslCapability.MMA8452_ACCELEROMETER, "MMA8452_ACCELEROMETER", true, "MMA8452 样本"),
        CapabilityDefinition(5, HslCapability.RTK_NMEA, "RTK_NMEA", false, "HSL 模式 NMEA"),
        CapabilityDefinition(6, HslCapability.RTK_CORRECTION, "RTK_CORRECTION", false, "HSL 模式 RTCM3"),
        CapabilityDefinition(7, HslCapability.LOCAL_INTERCOM, "LOCAL_INTERCOM", false, "本地对讲"),
    )
}
