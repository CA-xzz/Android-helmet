package com.example.helmet.core.protocol

object HslFlags {
    const val ACK_REQUIRED = 0x01
    const val ACK = 0x02
    const val ERROR = 0x04
}

object HslMessageType {
    const val HEARTBEAT = 0x01
    const val HELLO = 0x02
    const val KEY_EVENT = 0x10
    const val SENSOR_SAMPLE = 0x11
    const val ALARM_EVENT = 0x12

    // Reserved protocol-v1 identifiers. Android does not define their payload schema and rejects
    // them with UNSUPPORTED_TYPE instead of acknowledging unparsed bytes.
    const val BATTERY_STATUS = 0x13
    const val SET_CONFIG = 0x20
    const val GET_CONFIG = 0x21
    const val SET_OUTPUT = 0x22
    const val TIME_SYNC = 0x23
    const val LOG_REQUEST = 0x24
    const val LORA_MESSAGE = 0x30
    const val RTK_NMEA = 0x31
    const val RTK_CORRECTION = 0x32
    const val RTK_STATUS = 0x33
    const val LOCAL_INTERCOM_COMMAND = 0x34
    const val LOCAL_INTERCOM_STATUS = 0x35
    const val ACK = 0x7F
}

enum class HslLinkState {
    DISCONNECTED,
    CONNECTED,
    DEGRADED,
    FAULT,
}
