package com.example.helmet.hardware.service

internal object VariantSerialDevicePathPolicy {
    private val additionalDevicePath = Regex("^/dev/pts/[0-9]{1,5}$")

    fun acceptsAdditionalPath(devicePath: String): Boolean =
        additionalDevicePath.matches(devicePath)
}
