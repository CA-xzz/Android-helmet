package com.example.helmet.hardware.service

internal class NativeSerialPort {
    external fun open(devicePath: String, baudRate: Int): Int
    external fun read(fileDescriptor: Int, destination: ByteArray, timeoutMillis: Int): Int
    external fun write(fileDescriptor: Int, source: ByteArray): Int
    external fun close(fileDescriptor: Int)

    companion object {
        init {
            System.loadLibrary("helmet_serial")
        }
    }
}
