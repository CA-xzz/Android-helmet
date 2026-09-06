package com.example.helmet.hardware.api

enum class BoardInterfaceKind {
    ANDROID_API,
    GPIO_INPUT,
    GPIO_OUTPUT,
    GPIO_POWER,
    I2C,
    I2S,
    SD_BUS,
    UART,
    USB,
}

enum class BoardDirection {
    INPUT,
    OUTPUT,
    BIDIRECTIONAL,
    BUS,
    NOT_APPLICABLE,
    UNKNOWN,
}

enum class BoardActiveLevel {
    HIGH,
    LOW,
    NOT_APPLICABLE,
    UNKNOWN,
}

enum class BoardSafeState {
    INACTIVE,
    INPUT,
    KERNEL_MANAGED,
    NOT_APPLICABLE,
    UNKNOWN,
}

enum class BoardControlPolicy {
    ANDROID_SYSTEM_SERVICE,
    BLOCKED_PENDING_CONFIRMATION,
    HARDWARE_SERVICE,
    KERNEL_DRIVER,
    READ_ONLY_DIAGNOSTIC,
}

data class HardwareContractVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    init {
        require(major in 1..0xFF)
        require(minor in 0..0xFF)
        require(patch in 0..0xFF)
    }

    override fun toString(): String = "$major.$minor.$patch"
}

enum class BoardIntegrationOwner {
    ANDROID_FRAMEWORK,
    HSL_UART2,
    DIRECT_RTK_UART4,
    BSP_MANAGED,
    BOOTLOADER_DIAGNOSTIC,
}

enum class HardwareCapability {
    FIRMWARE_FLASH,
    DEBUG_CONSOLE,
    PERIPHERAL_POWER,
    CAMERA,
    AUDIO,
    CELLULAR_NETWORK,
    REMOVABLE_STORAGE,
    PHYSICAL_KEYS,
    LED_OUTPUT,
    VIBRATION_OUTPUT,
    BUZZER_OUTPUT,
    MMA8452_ACCELEROMETER,
    RTK_NMEA,
    RTK_CORRECTION,
    LOCAL_INTERCOM,
}

enum class BoardVerificationState {
    MAPPED_ON_H618,
    MAPPING_PENDING,
    SOFTWARE_PATH_VERIFIED,
    WAITING_EXTERNAL_DEVICE,
}

enum class BoardAvailability {
    AVAILABLE,
    RUNNING,
    WAITING_EXTERNAL,
    NOT_CONFIGURED,
    STALE,
    DRIVER_MISSING,
    PROTOCOL_INCOMPATIBLE,
    PIN_CONFLICT,
    PERMISSION_DENIED,
    FAULT,
}

data class BoardResourceStatus(
    val resourceId: String,
    val availability: BoardAvailability,
    val detail: String,
    val evidence: String? = null,
    val updatedAtEpochMillis: Long? = null,
)

data class BoardInitializationStep(
    val action: String,
    val delayAfterMillis: Long = 0,
)

data class BoardResourceDefinition(
    val id: String,
    val module: String,
    val spreadsheetRows: Set<Int>,
    val socPins: Set<String>,
    val endpoints: Set<String>,
    val kernelOwner: String,
    val kind: BoardInterfaceKind,
    val direction: BoardDirection,
    val activeLevel: BoardActiveLevel,
    val safeState: BoardSafeState,
    val initialization: List<BoardInitializationStep>,
    val required: Boolean,
    val enabled: Boolean,
    val controlPolicy: BoardControlPolicy,
    val verification: BoardVerificationState,
    val defaultAvailability: BoardAvailability,
    val requiresHardwareConfirmation: Boolean,
    val sharedClaimGroup: String? = null,
    val notes: String,
    val integrationOwner: BoardIntegrationOwner,
    val capabilities: Set<HardwareCapability>,
    val readinessEvidence: String,
    val readinessTimeoutMillis: Long?,
    val hardwareResponsibility: String,
)

data class BoardProfile(
    val id: String,
    val model: String,
    val contractVersion: HardwareContractVersion,
    val spreadsheetRows: IntRange,
    val resources: List<BoardResourceDefinition>,
    val observedFacts: List<String>,
    val hardwareQuestions: List<String>,
)

enum class BoardValidationSeverity {
    INFO,
    BLOCKED,
    ERROR,
}

data class BoardValidationIssue(
    val code: String,
    val severity: BoardValidationSeverity,
    val resourceIds: Set<String>,
    val detail: String,
)

data class BoardProfileValidation(
    val issues: List<BoardValidationIssue>,
) {
    val productionSafe: Boolean = issues.none { it.severity == BoardValidationSeverity.ERROR }

    fun issuesFor(resourceId: String): List<BoardValidationIssue> =
        issues.filter { resourceId in it.resourceIds }
}

object BoardProfileValidator {
    private val pinPattern = Regex("P[A-I](?:[0-9]|[12][0-9]|3[01])")
    private val deviceEndpointPattern = Regex(
        "/dev/(?:tty(?:AS|S|USB|ACM)[0-9]{1,3}|i2c-[0-9]{1,3}|video[0-9]{1,3}|input/event[0-9]{1,3})",
    )
    private val logicalEndpointPattern = Regex(
        "(?:android|kernel|usb)://[a-zA-Z0-9._/@-]+",
    )

    fun validate(profile: BoardProfile): BoardProfileValidation {
        val issues = mutableListOf<BoardValidationIssue>()
        val ids = mutableSetOf<String>()
        val spreadsheetClaims = profile.resources
            .flatMap { resource -> resource.spreadsheetRows.map { row -> row to resource.id } }
            .groupBy({ it.first }, { it.second })
        val missingRows = profile.spreadsheetRows.filterNot(spreadsheetClaims::containsKey).toSet()
        if (missingRows.isNotEmpty()) {
            issues += BoardValidationIssue(
                code = "MISSING_SPREADSHEET_ROWS",
                severity = BoardValidationSeverity.ERROR,
                resourceIds = emptySet(),
                detail = "spreadsheet rows are missing: ${missingRows.sorted().joinToString()}",
            )
        }
        spreadsheetClaims.filterValues { it.size > 1 }.forEach { (row, resourceIds) ->
            issues += BoardValidationIssue(
                code = "DUPLICATE_SPREADSHEET_ROW",
                severity = BoardValidationSeverity.ERROR,
                resourceIds = resourceIds.toSet(),
                detail = "spreadsheet row $row is assigned more than once",
            )
        }
        val outOfRangeRows = spreadsheetClaims.keys.filterNot(profile.spreadsheetRows::contains).toSet()
        if (outOfRangeRows.isNotEmpty()) {
            issues += BoardValidationIssue(
                code = "UNEXPECTED_SPREADSHEET_ROWS",
                severity = BoardValidationSeverity.ERROR,
                resourceIds = emptySet(),
                detail = "spreadsheet rows are outside the contract: ${outOfRangeRows.sorted().joinToString()}",
            )
        }
        profile.resources.forEach { resource ->
            if (!ids.add(resource.id)) {
                issues += BoardValidationIssue(
                    code = "DUPLICATE_RESOURCE_ID",
                    severity = BoardValidationSeverity.ERROR,
                    resourceIds = setOf(resource.id),
                    detail = "board resource id is duplicated",
                )
            }
            resource.socPins.filterNot(pinPattern::matches).forEach { pin ->
                issues += BoardValidationIssue(
                    code = "INVALID_SOC_PIN",
                    severity = BoardValidationSeverity.ERROR,
                    resourceIds = setOf(resource.id),
                    detail = "invalid SoC pin: $pin",
                )
            }
            resource.endpoints.filterNot(::validEndpoint).forEach { endpoint ->
                issues += BoardValidationIssue(
                    code = "INVALID_ENDPOINT",
                    severity = BoardValidationSeverity.ERROR,
                    resourceIds = setOf(resource.id),
                    detail = "endpoint is outside the fixed board whitelist: $endpoint",
                )
            }
            if (resource.readinessEvidence.isBlank() || resource.hardwareResponsibility.isBlank()) {
                issues += BoardValidationIssue(
                    code = "INCOMPLETE_INTEGRATION_CONTRACT",
                    severity = BoardValidationSeverity.ERROR,
                    resourceIds = setOf(resource.id),
                    detail = "resource lacks readiness evidence or hardware responsibility",
                )
            }
            if (resource.readinessTimeoutMillis != null && resource.readinessTimeoutMillis <= 0) {
                issues += BoardValidationIssue(
                    code = "INVALID_READINESS_TIMEOUT",
                    severity = BoardValidationSeverity.ERROR,
                    resourceIds = setOf(resource.id),
                    detail = "readiness timeout must be positive",
                )
            }
            validateControl(resource)?.let(issues::add)
        }

        profile.resources
            .flatMap { resource -> resource.socPins.map { pin -> pin to resource } }
            .groupBy({ it.first }, { it.second })
            .forEach { (pin, claims) ->
                val independentClaims = claims.distinctBy { it.sharedClaimGroup ?: it.id }
                if (independentClaims.size <= 1) return@forEach
                val activeClaims = independentClaims.filter(BoardResourceDefinition::enabled)
                val severity = if (activeClaims.size > 1) {
                    BoardValidationSeverity.ERROR
                } else {
                    BoardValidationSeverity.BLOCKED
                }
                issues += BoardValidationIssue(
                    code = if (severity == BoardValidationSeverity.ERROR) {
                        "ACTIVE_PIN_CONFLICT"
                    } else {
                        "BLOCKED_PIN_CONFLICT"
                    },
                    severity = severity,
                    resourceIds = independentClaims.mapTo(linkedSetOf(), BoardResourceDefinition::id),
                    detail = "$pin is declared by ${independentClaims.joinToString { it.id }}",
                )
            }

        profile.resources
            .filter(BoardResourceDefinition::enabled)
            .flatMap { resource ->
                resource.endpoints.filter { it.startsWith("/dev/") }.map { endpoint -> endpoint to resource }
            }
            .groupBy({ it.first }, { it.second })
            .filterValues { resources -> resources.distinctBy(BoardResourceDefinition::id).size > 1 }
            .forEach { (endpoint, resources) ->
                issues += BoardValidationIssue(
                    code = "ACTIVE_ENDPOINT_CONFLICT",
                    severity = BoardValidationSeverity.ERROR,
                    resourceIds = resources.mapTo(linkedSetOf(), BoardResourceDefinition::id),
                    detail = "$endpoint is assigned to more than one active resource",
                )
            }

        val hslPath = profile.resources.firstOrNull { it.id == "hsl_uart2" }?.endpoints?.singleOrNull()
        val rtkPath = profile.resources.firstOrNull { it.id == "rtk_uart4" }?.endpoints?.singleOrNull()
        if (hslPath != null && rtkPath != null && hslPath == rtkPath) {
            issues += BoardValidationIssue(
                code = "RTK_HSL_UART_NOT_ISOLATED",
                severity = BoardValidationSeverity.ERROR,
                resourceIds = setOf("hsl_uart2", "rtk_uart4"),
                detail = "HSL and independent RTK cannot share one UART device",
            )
        }
        return BoardProfileValidation(issues.distinct())
    }

    private fun validEndpoint(endpoint: String): Boolean =
        deviceEndpointPattern.matches(endpoint) || logicalEndpointPattern.matches(endpoint)

    private fun validateControl(resource: BoardResourceDefinition): BoardValidationIssue? {
        val controlled = resource.kind in setOf(
            BoardInterfaceKind.GPIO_OUTPUT,
            BoardInterfaceKind.GPIO_POWER,
        )
        if (!resource.enabled || !controlled) return null
        if (resource.controlPolicy == BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION) {
            return BoardValidationIssue(
                code = "ENABLED_CONTROL_IS_BLOCKED",
                severity = BoardValidationSeverity.ERROR,
                resourceIds = setOf(resource.id),
                detail = "a blocked GPIO control cannot be enabled",
            )
        }
        if (resource.activeLevel in setOf(BoardActiveLevel.UNKNOWN, BoardActiveLevel.NOT_APPLICABLE) ||
            resource.safeState == BoardSafeState.UNKNOWN || resource.initialization.isEmpty()
        ) {
            return BoardValidationIssue(
                code = "INCOMPLETE_SAFE_CONTROL_SEQUENCE",
                severity = BoardValidationSeverity.ERROR,
                resourceIds = setOf(resource.id),
                detail = "enabled GPIO control lacks active level, safe state, or initialization sequence",
            )
        }
        return null
    }
}

object H618BoardProfile {
    val profile: BoardProfile = BoardProfile(
        id = "h618-helmet-v3",
        model = "QUAD-CORE H618 p1",
        contractVersion = HardwareContractVersion(1, 0, 0),
        spreadsheetRows = 2..45,
        resources = listOf(
            resource(
                id = "debug_usb0", module = "DEBUG", rows = setOf(2), endpoints = setOf("usb://usb0"),
                kind = BoardInterfaceKind.USB, owner = "bootloader/BSP", enabled = true,
                policy = BoardControlPolicy.KERNEL_DRIVER, verification = BoardVerificationState.MAPPED_ON_H618,
                integrationOwner = BoardIntegrationOwner.BOOTLOADER_DIAGNOSTIC,
                capabilities = setOf(HardwareCapability.FIRMWARE_FLASH),
                availability = BoardAvailability.AVAILABLE, notes = "firmware flashing interface",
            ),
            resource(
                id = "debug_uart0", module = "DEBUG", rows = setOf(3, 4), pins = setOf("PH1", "PH0"),
                endpoints = setOf("/dev/ttyAS0"), kind = BoardInterfaceKind.UART,
                direction = BoardDirection.BIDIRECTIONAL, owner = "sunxi-uart/console", enabled = true,
                policy = BoardControlPolicy.KERNEL_DRIVER, verification = BoardVerificationState.MAPPED_ON_H618,
                integrationOwner = BoardIntegrationOwner.BOOTLOADER_DIAGNOSTIC,
                capabilities = setOf(HardwareCapability.DEBUG_CONSOLE),
                availability = BoardAvailability.AVAILABLE, notes = "UART0 debug only",
            ),
            blockedPower(
                id = "peripheral_3v3_enable", module = "SYSTEM", rows = setOf(5), pin = "PG13",
                activeLevel = BoardActiveLevel.HIGH, notes = "shared 3.3 V rail; GPIO number is not confirmed",
            ),
            resource(
                id = "camera_usb1", module = "CAMERA", rows = setOf(6), endpoints = setOf("android://camera/usb1"),
                kind = BoardInterfaceKind.ANDROID_API, owner = "CameraService/Camera2", required = true, enabled = true,
                policy = BoardControlPolicy.ANDROID_SYSTEM_SERVICE,
                capabilities = setOf(HardwareCapability.CAMERA), readinessTimeoutMillis = 15_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL, notes = "UVC must enumerate through Camera2",
            ),
            blockedPower(
                id = "camera_power_enable", module = "CAMERA", rows = setOf(7), pin = "PI4",
                activeLevel = BoardActiveLevel.HIGH, availability = BoardAvailability.PIN_CONFLICT,
                notes = "conflicts with MMA8452 INT2 in the spreadsheet",
            ),
            resource(
                id = "sd2_usb3", module = "SD2", rows = setOf(8), endpoints = setOf("android://storage/usb3"),
                kind = BoardInterfaceKind.ANDROID_API, owner = "vold/StorageManager", required = true, enabled = true,
                policy = BoardControlPolicy.ANDROID_SYSTEM_SERVICE,
                capabilities = setOf(HardwareCapability.REMOVABLE_STORAGE), readinessTimeoutMillis = 15_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL, notes = "GL823K mount state is authoritative",
            ),
            blockedInput("sd2_insert_detect", "SD2", setOf(9), "PG12", BoardActiveLevel.HIGH),
            blockedPower(
                id = "sd2_power_enable", module = "SD2", rows = setOf(10), pin = "PH10",
                activeLevel = BoardActiveLevel.HIGH, notes = "live DT also defines PH10 for s_cir0; owner is unconfirmed",
            ),
            blockedPower(
                id = "sd1_reset_power", module = "SD1", rows = setOf(11), pin = "PI16",
                activeLevel = BoardActiveLevel.LOW, notes = "low disconnects SD1 power; no switching allowed",
            ),
            blockedInput("sd1_insert_detect", "SD1", setOf(12), "PF6", BoardActiveLevel.HIGH),
            resource(
                id = "sd1_bus", module = "SD1", rows = (13..18).toSet(),
                pins = setOf("PF0", "PF1", "PF2", "PF3", "PF4", "PF5"),
                endpoints = setOf("kernel://sdmmc@4020000", "android://storage/sd1"),
                kind = BoardInterfaceKind.SD_BUS, direction = BoardDirection.BUS, owner = "sunxi-mmc/vold",
                required = true, enabled = true, policy = BoardControlPolicy.KERNEL_DRIVER,
                capabilities = setOf(HardwareCapability.REMOVABLE_STORAGE), readinessTimeoutMillis = 15_000,
                verification = BoardVerificationState.MAPPED_ON_H618,
                availability = BoardAvailability.DRIVER_MISSING,
                notes = "live pin group matches SDC0; no removable block device was present",
            ),
            resource(
                id = "audio_codec_i2c2", module = "AUDIO", rows = setOf(19, 20), pins = setOf("PH2", "PH3"),
                endpoints = setOf("kernel://i2c2"), kind = BoardInterfaceKind.I2C,
                direction = BoardDirection.BUS, owner = "codec kernel driver", enabled = false,
                policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
                capabilities = setOf(HardwareCapability.AUDIO),
                verification = BoardVerificationState.MAPPING_PENDING,
                availability = BoardAvailability.DRIVER_MISSING,
                notes = "live board exposes no i2c-2; codec model is inconsistent in the spreadsheet",
            ),
            blockedPower(
                id = "audio_codec_power", module = "AUDIO", rows = setOf(21), pin = "PH4",
                activeLevel = BoardActiveLevel.HIGH, notes = "codec identity and pin owner are unconfirmed",
            ),
            resource(
                id = "audio_i2s3", module = "AUDIO", rows = (22..26).toSet(),
                pins = setOf("PH5", "PH6", "PH7", "PH8", "PH9"), endpoints = setOf("kernel://i2s3"),
                kind = BoardInterfaceKind.I2S, direction = BoardDirection.BUS, owner = "ALSA/Audio HAL",
                enabled = false, policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
                capabilities = setOf(HardwareCapability.AUDIO),
                verification = BoardVerificationState.MAPPING_PENDING,
                availability = BoardAvailability.DRIVER_MISSING,
                notes = "live DT only names PH8/PH9 for I2S3 data; clocks require BSP confirmation",
            ),
            resource(
                id = "android_audio", module = "AUDIO", rows = emptySet(),
                endpoints = setOf("android://audio/input", "android://audio/output"),
                kind = BoardInterfaceKind.ANDROID_API, owner = "AudioService/AudioRecord/AudioTrack",
                required = true, enabled = true, policy = BoardControlPolicy.ANDROID_SYSTEM_SERVICE,
                capabilities = setOf(HardwareCapability.AUDIO), readinessTimeoutMillis = 10_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "codec and I2S must be exposed through Android audio APIs",
            ),
            blockedOutput(
                id = "modem_wakeup", module = "4G", rows = setOf(27), pin = "PG8",
                activeLevel = BoardActiveLevel.UNKNOWN, availability = BoardAvailability.PIN_CONFLICT,
                notes = "live UART1 pin group also claims PG8",
            ),
            blockedOutput(
                id = "modem_pwrkey", module = "4G", rows = setOf(28), pin = "PG9",
                activeLevel = BoardActiveLevel.LOW, availability = BoardAvailability.PIN_CONFLICT,
                initialization = listOf(BoardInitializationStep("inactive"), BoardInitializationStep("low pulse", 1_000)),
                notes = "one-second pulse is modeled but GPIO execution is blocked; live UART1 also claims PG9",
            ),
            blockedOutput(
                id = "modem_reset", module = "4G", rows = setOf(29), pin = "PG10",
                activeLevel = BoardActiveLevel.LOW, notes = "reset versus power-off semantics and pulse width are unknown",
            ),
            resource(
                id = "modem_uart1", module = "4G", rows = setOf(30, 31),
                pins = setOf("PG6", "PG7", "PG8", "PG9"), endpoints = setOf("/dev/ttyAS1"),
                kind = BoardInterfaceKind.UART, direction = BoardDirection.BIDIRECTIONAL,
                owner = "sunxi-uart/bluetooth-or-modem", enabled = true,
                policy = BoardControlPolicy.KERNEL_DRIVER, verification = BoardVerificationState.MAPPED_ON_H618,
                capabilities = setOf(HardwareCapability.CELLULAR_NETWORK),
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "live UART1 pinmux uses PG6-PG9; board role still requires hardware confirmation",
            ),
            resource(
                id = "modem_usb2", module = "4G", rows = setOf(32), endpoints = setOf("android://network/usb2"),
                kind = BoardInterfaceKind.ANDROID_API, owner = "ConnectivityService/USB driver", required = true,
                enabled = true,
                policy = BoardControlPolicy.ANDROID_SYSTEM_SERVICE,
                capabilities = setOf(HardwareCapability.CELLULAR_NETWORK), readinessTimeoutMillis = 30_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "effective cellular or USB network capability is authoritative",
            ),
            blockedPower(
                id = "mma8452_power", module = "MMA8452", rows = setOf(33), pin = "PI3",
                activeLevel = BoardActiveLevel.HIGH, notes = "kernel driver is absent; power switching is blocked",
            ),
            blockedInput(
                id = "mma8452_int2", module = "MMA8452", rows = setOf(34), pin = "PI4",
                activeLevel = BoardActiveLevel.UNKNOWN, availability = BoardAvailability.PIN_CONFLICT,
                notes = "conflicts with camera power enable",
            ),
            resource(
                id = "mma8452_i2c0", module = "MMA8452", rows = setOf(35, 36), pins = setOf("PI5", "PI6"),
                endpoints = setOf("kernel://i2c0", "android://sensor/accelerometer"),
                kind = BoardInterfaceKind.I2C, direction = BoardDirection.BUS, owner = "IIO/input/Sensor HAL",
                enabled = false, policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
                capabilities = setOf(HardwareCapability.MMA8452_ACCELEROMETER),
                verification = BoardVerificationState.MAPPING_PENDING,
                availability = BoardAvailability.PIN_CONFLICT,
                notes = "PI5/PI6 are actively assigned to UART2 on this H618 image",
            ),
            resource(
                id = "hsl_uart2", module = "HSL", rows = emptySet(), pins = setOf("PI5", "PI6"),
                endpoints = setOf("/dev/ttyAS2"), kind = BoardInterfaceKind.UART,
                direction = BoardDirection.BIDIRECTIONAL, owner = "native-hardware-service", required = true,
                enabled = true,
                policy = BoardControlPolicy.HARDWARE_SERVICE,
                capabilities = setOf(
                    HardwareCapability.PHYSICAL_KEYS,
                    HardwareCapability.LED_OUTPUT,
                    HardwareCapability.VIBRATION_OUTPUT,
                    HardwareCapability.BUZZER_OUTPUT,
                    HardwareCapability.MMA8452_ACCELEROMETER,
                    HardwareCapability.RTK_NMEA,
                    HardwareCapability.RTK_CORRECTION,
                    HardwareCapability.LOCAL_INTERCOM,
                ),
                readinessTimeoutMillis = 5_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "existing production HSL port; live device maps ttyAS2 to UART2",
            ),
            resource(
                id = "hsl_physical_keys", module = "LED_BUTTON", rows = emptySet(),
                endpoints = setOf("android://hsl/keys"), kind = BoardInterfaceKind.ANDROID_API,
                owner = "HSL KEY_EVENT", required = true, enabled = true,
                policy = BoardControlPolicy.HARDWARE_SERVICE,
                capabilities = setOf(HardwareCapability.PHYSICAL_KEYS), readinessTimeoutMillis = 5_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "logical key capability; physical GPIO mapping remains a hardware responsibility",
            ),
            resource(
                id = "hsl_local_outputs", module = "LED_BUTTON", rows = emptySet(),
                endpoints = setOf("android://hsl/outputs"), kind = BoardInterfaceKind.ANDROID_API,
                owner = "HSL SET_OUTPUT", required = true, enabled = true,
                policy = BoardControlPolicy.HARDWARE_SERVICE,
                capabilities = setOf(
                    HardwareCapability.LED_OUTPUT,
                    HardwareCapability.VIBRATION_OUTPUT,
                    HardwareCapability.BUZZER_OUTPUT,
                ),
                readinessTimeoutMillis = 5_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "logical output capability; physical GPIO mapping remains a hardware responsibility",
            ),
            resource(
                id = "hsl_mma8452", module = "MMA8452", rows = emptySet(),
                endpoints = setOf("android://hsl/mma8452"), kind = BoardInterfaceKind.ANDROID_API,
                owner = "HSL SENSOR_SAMPLE", required = true, enabled = true,
                policy = BoardControlPolicy.HARDWARE_SERVICE,
                capabilities = setOf(HardwareCapability.MMA8452_ACCELEROMETER), readinessTimeoutMillis = 5_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "MMA8452 samples use milli-g units and monotonic sample references",
            ),
            resource(
                id = "hsl_rtk_bridge", module = "RTK", rows = emptySet(),
                endpoints = setOf("android://hsl/rtk"), kind = BoardInterfaceKind.ANDROID_API,
                owner = "HSL RTK_NMEA/RTK_CORRECTION", required = false, enabled = true,
                policy = BoardControlPolicy.HARDWARE_SERVICE,
                capabilities = setOf(HardwareCapability.RTK_NMEA, HardwareCapability.RTK_CORRECTION),
                readinessTimeoutMillis = 15_000,
                verification = BoardVerificationState.SOFTWARE_PATH_VERIFIED,
                availability = BoardAvailability.WAITING_EXTERNAL,
                notes = "default RTK mode; direct UART4 remains selectable",
            ),
            resource(
                id = "rtk_uart4", module = "RTK", rows = setOf(37, 38), pins = setOf("PI13", "PI14"),
                endpoints = setOf("/dev/ttyAS4"), kind = BoardInterfaceKind.UART,
                direction = BoardDirection.BIDIRECTIONAL, owner = "native-hardware-service RTK transport",
                enabled = false, policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
                integrationOwner = BoardIntegrationOwner.DIRECT_RTK_UART4,
                capabilities = setOf(HardwareCapability.RTK_NMEA, HardwareCapability.RTK_CORRECTION),
                readinessTimeoutMillis = 15_000,
                verification = BoardVerificationState.MAPPED_ON_H618,
                availability = BoardAvailability.NOT_CONFIGURED,
                notes = "UART4 mapping is confirmed; receiver baud and direct-versus-HSL topology are not",
            ),
        ) + ledAndButtonResources(),
        observedFacts = listOf(
            "/dev/ttyAS4 maps to uart@5001000 (UART4) and PI13/PI14",
            "/dev/ttyAS2 maps to uart@5000800 (UART2) and its live pin group uses PI5/PI6",
            "UART1 live pin group uses PG6/PG7/PG8/PG9",
            "only /dev/i2c-1 with pcf8563 was present; i2c-0 and i2c-2 were absent",
            "CameraService enumerated zero cameras and SensorService reported no sensors",
            "gpio203 was already exported as output-high by /data/myautorun.sh; its circuit role was not changed",
        ),
        hardwareQuestions = listOf(
            "确认 PI4 最终用于摄像头电源还是 MMA8452 INT2，并消除复用冲突",
            "确认 PI1、PI0、PG17、PG19、PG18、PG16、PG15 分别对应哪个按键或 LED，以及方向和有效电平",
            "确认 /dev/ttyAS2 对接 HSL 模块，并确认量产 RTK 默认由 HSL 转发还是独立 UART4",
            "确认 gpio203 的原理图用途以及它与 PG13 的实际映射关系",
            "确认实际安装的 NAU88C2x codec 型号及设备树 compatible",
            "确认 4G 模块型号为 AIR780EG 还是 AIR781EG",
            "确认 PG10 的复位或关机语义、安全脉冲宽度及默认电平",
            "确认全部 GPIO 的上电默认电平、上下拉、开漏要求和最大驱动状态",
            "启用 /dev/ttyAS4 前确认 RTK 接收机波特率",
        ),
    )

    val validation: BoardProfileValidation by lazy { BoardProfileValidator.validate(profile) }

    private fun ledAndButtonResources(): List<BoardResourceDefinition> =
        listOf("PI1", "PI0", "PG17", "PG19", "PG18", "PG16", "PG15").mapIndexed { index, pin ->
            resource(
                id = "led_button_gpio_${index + 1}", module = "LED_BUTTON", rows = setOf(39 + index),
                pins = setOf(pin), endpoints = setOf("kernel://gpio/$pin"), kind = BoardInterfaceKind.GPIO_INPUT,
                direction = BoardDirection.UNKNOWN, activeLevel = BoardActiveLevel.UNKNOWN,
                safeState = BoardSafeState.UNKNOWN, owner = "input/LED driver pending mapping", enabled = false,
                policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
                verification = BoardVerificationState.MAPPING_PENDING,
                availability = BoardAvailability.NOT_CONFIGURED, confirmation = true,
                capabilities = setOf(HardwareCapability.PHYSICAL_KEYS, HardwareCapability.LED_OUTPUT),
                notes = "spreadsheet does not assign this GPIO to a specific key or LED",
            )
        }

    private fun blockedPower(
        id: String,
        module: String,
        rows: Set<Int>,
        pin: String,
        activeLevel: BoardActiveLevel,
        availability: BoardAvailability = BoardAvailability.NOT_CONFIGURED,
        notes: String,
    ): BoardResourceDefinition = resource(
        id = id, module = module, rows = rows, pins = setOf(pin), endpoints = setOf("kernel://gpio/$pin"),
        kind = BoardInterfaceKind.GPIO_POWER, direction = BoardDirection.OUTPUT, activeLevel = activeLevel,
        safeState = BoardSafeState.INACTIVE, owner = "kernel driver pending mapping", enabled = false,
        policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
        verification = BoardVerificationState.MAPPING_PENDING, availability = availability,
        confirmation = true,
        capabilities = setOf(
            when (module) {
                "CAMERA" -> HardwareCapability.CAMERA
                "SD1", "SD2" -> HardwareCapability.REMOVABLE_STORAGE
                "AUDIO" -> HardwareCapability.AUDIO
                "MMA8452" -> HardwareCapability.MMA8452_ACCELEROMETER
                else -> HardwareCapability.PERIPHERAL_POWER
            },
        ),
        notes = notes,
    )

    private fun blockedOutput(
        id: String,
        module: String,
        rows: Set<Int>,
        pin: String,
        activeLevel: BoardActiveLevel,
        availability: BoardAvailability = BoardAvailability.NOT_CONFIGURED,
        initialization: List<BoardInitializationStep> = emptyList(),
        notes: String,
    ): BoardResourceDefinition = resource(
        id = id, module = module, rows = rows, pins = setOf(pin), endpoints = setOf("kernel://gpio/$pin"),
        kind = BoardInterfaceKind.GPIO_OUTPUT, direction = BoardDirection.OUTPUT, activeLevel = activeLevel,
        safeState = BoardSafeState.INACTIVE, initialization = initialization,
        owner = "kernel driver pending mapping", enabled = false,
        policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
        verification = BoardVerificationState.MAPPING_PENDING, availability = availability,
        confirmation = true,
        capabilities = if (module == "4G") {
            setOf(HardwareCapability.CELLULAR_NETWORK)
        } else {
            emptySet()
        },
        notes = notes,
    )

    private fun blockedInput(
        id: String,
        module: String,
        rows: Set<Int>,
        pin: String,
        activeLevel: BoardActiveLevel,
        availability: BoardAvailability = BoardAvailability.NOT_CONFIGURED,
        notes: String = "input mapping and pull configuration require hardware confirmation",
    ): BoardResourceDefinition = resource(
        id = id, module = module, rows = rows, pins = setOf(pin), endpoints = setOf("kernel://gpio/$pin"),
        kind = BoardInterfaceKind.GPIO_INPUT, direction = BoardDirection.INPUT, activeLevel = activeLevel,
        safeState = BoardSafeState.INPUT, owner = "input driver pending mapping", enabled = false,
        policy = BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
        verification = BoardVerificationState.MAPPING_PENDING, availability = availability,
        confirmation = true,
        capabilities = when (module) {
            "SD1", "SD2" -> setOf(HardwareCapability.REMOVABLE_STORAGE)
            "MMA8452" -> setOf(HardwareCapability.MMA8452_ACCELEROMETER)
            else -> emptySet()
        },
        notes = notes,
    )

    private fun resource(
        id: String,
        module: String,
        rows: Set<Int>,
        pins: Set<String> = emptySet(),
        endpoints: Set<String> = emptySet(),
        kind: BoardInterfaceKind,
        direction: BoardDirection = BoardDirection.NOT_APPLICABLE,
        activeLevel: BoardActiveLevel = BoardActiveLevel.NOT_APPLICABLE,
        safeState: BoardSafeState = BoardSafeState.NOT_APPLICABLE,
        initialization: List<BoardInitializationStep> = emptyList(),
        owner: String,
        required: Boolean = false,
        enabled: Boolean,
        policy: BoardControlPolicy,
        verification: BoardVerificationState,
        availability: BoardAvailability,
        confirmation: Boolean = false,
        sharedClaimGroup: String? = null,
        integrationOwner: BoardIntegrationOwner = ownerFor(policy),
        capabilities: Set<HardwareCapability> = emptySet(),
        readinessEvidence: String = defaultReadinessEvidence(policy, verification),
        readinessTimeoutMillis: Long? = null,
        hardwareResponsibility: String = owner,
        notes: String,
    ): BoardResourceDefinition = BoardResourceDefinition(
        id = id,
        module = module,
        spreadsheetRows = rows,
        socPins = pins,
        endpoints = endpoints,
        kernelOwner = owner,
        kind = kind,
        direction = direction,
        activeLevel = activeLevel,
        safeState = safeState,
        initialization = initialization,
        required = required,
        enabled = enabled,
        controlPolicy = policy,
        verification = verification,
        defaultAvailability = availability,
        requiresHardwareConfirmation = confirmation,
        sharedClaimGroup = sharedClaimGroup,
        notes = notes,
        integrationOwner = integrationOwner,
        capabilities = capabilities,
        readinessEvidence = readinessEvidence,
        readinessTimeoutMillis = readinessTimeoutMillis,
        hardwareResponsibility = hardwareResponsibility,
    )

    private fun ownerFor(policy: BoardControlPolicy): BoardIntegrationOwner = when (policy) {
        BoardControlPolicy.ANDROID_SYSTEM_SERVICE -> BoardIntegrationOwner.ANDROID_FRAMEWORK
        BoardControlPolicy.HARDWARE_SERVICE -> BoardIntegrationOwner.HSL_UART2
        BoardControlPolicy.KERNEL_DRIVER,
        BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION,
        BoardControlPolicy.READ_ONLY_DIAGNOSTIC,
        -> BoardIntegrationOwner.BSP_MANAGED
    }

    private fun defaultReadinessEvidence(
        policy: BoardControlPolicy,
        verification: BoardVerificationState,
    ): String = when (policy) {
        BoardControlPolicy.ANDROID_SYSTEM_SERVICE -> "Android API enumeration and successful operation"
        BoardControlPolicy.HARDWARE_SERVICE -> "compatible HSL HELLO, heartbeat, and capability-specific data"
        BoardControlPolicy.KERNEL_DRIVER -> "BSP driver binding and Android-facing service availability"
        BoardControlPolicy.BLOCKED_PENDING_CONFIRMATION -> "hardware confirmation and BSP-owned safe control"
        BoardControlPolicy.READ_ONLY_DIAGNOSTIC -> "read-only diagnostic evidence: ${verification.name}"
    }
}
