package com.example.helmet.hardware.service

internal object SerialDevicePathPolicy {
    private val productionDevicePath = Regex("^/dev/tty(?:AS|S|USB|ACM)[0-9]{1,3}$")

    fun isAllowed(devicePath: String, allowTestPty: Boolean): Boolean =
        productionDevicePath.matches(devicePath) ||
            (allowTestPty && VariantSerialDevicePathPolicy.acceptsAdditionalPath(devicePath))
}
