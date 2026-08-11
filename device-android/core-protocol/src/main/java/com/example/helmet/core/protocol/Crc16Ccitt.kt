package com.example.helmet.core.protocol

object Crc16Ccitt {
    fun compute(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): Int {
        require(offset >= 0 && length >= 0 && offset + length <= bytes.size)
        var crc = 0xFFFF
        for (index in offset until offset + length) {
            crc = crc xor ((bytes[index].toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) {
                    ((crc shl 1) xor 0x1021) and 0xFFFF
                } else {
                    (crc shl 1) and 0xFFFF
                }
            }
        }
        return crc
    }
}
