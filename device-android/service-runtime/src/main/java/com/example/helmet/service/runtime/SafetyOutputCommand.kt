package com.example.helmet.service.runtime

import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslOutputCommand
import com.example.helmet.core.protocol.HslOutputPayloadCodec
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareCommand
import com.example.helmet.hardware.api.HardwareEvent

internal fun safetyOutputCommand(
    event: HardwareEvent.Alarm,
    sequence: Int,
    requestId: Long,
): HardwareCommand? {
    if (event.origin != HardwareAlarmOrigin.ANDROID_DETECTION || event.simulated || event.localActions == 0) {
        return null
    }
    require(sequence in 0..0xFFFF)
    requireNotNull(event.alarmId) { "Android safety alarm requires a stable alarm ID" }
    require(requestId in 1..0xFFFF_FFFFL)
    val output = HslOutputCommand(
        requestId = requestId,
        active = event.active,
        actionMask = event.localActions,
    )
    return HardwareCommand(
        type = HslMessageType.SET_OUTPUT,
        flags = HslFlags.ACK_REQUIRED,
        sequence = sequence,
        payload = HslOutputPayloadCodec.encode(output),
    )
}
