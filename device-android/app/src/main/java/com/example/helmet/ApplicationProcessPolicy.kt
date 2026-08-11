package com.example.helmet

internal object ApplicationProcessPolicy {
    fun shouldInitialize(processName: String?, packageName: String): Boolean =
        processName == packageName
}
