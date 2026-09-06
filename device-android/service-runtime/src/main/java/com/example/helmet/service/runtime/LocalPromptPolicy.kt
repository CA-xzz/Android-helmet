package com.example.helmet.service.runtime

internal fun startupBatteryPrompt(present: Boolean, percent: Int?): String = when {
    present && percent != null -> "设备开机，当前电量为百分之$percent"
    present -> "设备开机，当前电量未知"
    else -> "设备开机，未检测到电池"
}

internal fun networkPrompt(previousValidated: Boolean?, currentValidated: Boolean): String? =
    if (previousValidated == currentValidated) null
    else if (currentValidated) "组网成功" else "组网失败"

internal fun shouldAnnounceSafetyAlertUploaded(alarmType: String, active: Boolean): Boolean =
    alarmType == "SOS" && active

internal fun safetyAlarmPrompt(alarmType: String, simulated: Boolean): String {
    val prefix = if (simulated) "模拟" else ""
    return when (alarmType) {
        "FALL" -> "检测到${prefix}跌落报警"
        "IMPACT" -> "检测到${prefix}撞击报警"
        "VIOLENT_SHAKE" -> "检测到${prefix}剧烈晃动报警"
        "NEAR_ELECTRIC" -> "检测到${prefix}近电报警"
        "HEIGHT_LIMIT" -> "检测到${prefix}高度报警"
        "INACTIVITY" -> "检测到${prefix}长时间静止报警"
        "GEOFENCE_EXIT" -> "检测到${prefix}电子围栏越界报警"
        else -> "检测到${prefix}报警"
    }
}

internal class BatteryPromptTracker(initialPromptedThreshold: Int? = null) {
    var promptedThreshold: Int? = initialPromptedThreshold
        private set

    init {
        require(initialPromptedThreshold == null || initialPromptedThreshold in THRESHOLDS)
    }

    fun update(present: Boolean, percent: Int?): Int? {
        if (!present || percent == null) return null
        require(percent in 0..100)
        if (percent > 20) {
            promptedThreshold = null
            return null
        }
        val threshold = when {
            percent <= 5 -> 5
            percent <= 10 -> 10
            else -> 20
        }
        val previous = promptedThreshold
        if (previous != null && threshold >= previous) return null
        promptedThreshold = threshold
        return threshold
    }

    private companion object {
        val THRESHOLDS = setOf(20, 10, 5)
    }
}
