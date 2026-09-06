package com.example.helmet.testfixture

import java.io.Closeable
import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-local PTY master used by instrumentation tests. The production hardware service opens
 * [slavePath] through its normal JNI serial implementation in the separate `:hardware` process.
 */
class AndroidTestPtyFixture : Closeable {
    private val masterDescriptor = AtomicInteger(CLOSED_DESCRIPTOR)

    val slavePath: String

    init {
        val descriptor = nativeOpenMaster().also { check(it >= 0) }
        masterDescriptor.set(descriptor)
        slavePath = try {
            nativeSlavePath(descriptor).also { path ->
                check(TEST_PTY_PATH.matches(path)) { "unexpected PTY slave path: $path" }
            }
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    @Synchronized
    fun writeFully(bytes: ByteArray) {
        val descriptor = openDescriptor()
        val written = nativeWriteMaster(descriptor, bytes)
        check(written == bytes.size) { "short PTY write: $written/${bytes.size}" }
    }

    fun read(maximumBytes: Int = DEFAULT_READ_SIZE, timeoutMillis: Int = 500): ByteArray {
        require(maximumBytes in 1..MAX_READ_SIZE)
        require(timeoutMillis in 0..MAX_TIMEOUT_MILLIS)
        return nativeReadMaster(openDescriptor(), maximumBytes, timeoutMillis)
    }

    override fun close() {
        val descriptor = masterDescriptor.getAndSet(CLOSED_DESCRIPTOR)
        if (descriptor != CLOSED_DESCRIPTOR) nativeCloseMaster(descriptor)
    }

    private fun openDescriptor(): Int = masterDescriptor.get().also { descriptor ->
        check(descriptor != CLOSED_DESCRIPTOR) { "PTY fixture is closed" }
    }

    private external fun nativeOpenMaster(): Int
    private external fun nativeSlavePath(masterDescriptor: Int): String
    private external fun nativeWriteMaster(masterDescriptor: Int, bytes: ByteArray): Int
    private external fun nativeReadMaster(
        masterDescriptor: Int,
        maximumBytes: Int,
        timeoutMillis: Int,
    ): ByteArray
    private external fun nativeCloseMaster(masterDescriptor: Int)

    companion object {
        private const val CLOSED_DESCRIPTOR = -1
        private const val DEFAULT_READ_SIZE = 4_096
        private const val MAX_READ_SIZE = 65_536
        private const val MAX_TIMEOUT_MILLIS = 30_000
        private val TEST_PTY_PATH = Regex("^/dev/pts/[0-9]{1,5}$")

        init {
            System.loadLibrary("helmet_serial")
        }
    }
}
