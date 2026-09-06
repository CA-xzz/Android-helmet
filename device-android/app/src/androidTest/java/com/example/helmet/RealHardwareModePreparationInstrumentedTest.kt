package com.example.helmet

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.helmet.data.local.RuntimeConfigStore
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One explicit, debug-test-only board preparation entry point for the hardware provider test.
 * It activates only the UART configuration that the host names and never loads credentials.
 */
@RunWith(AndroidJUnit4::class)
class RealHardwareModePreparationInstrumentedTest {
    @Test
    fun persistProvisionedRealHardwareMode() {
        assertFalse("real-hardware preparation must use a debug target", BuildConfig.PRODUCTION_BUILD)
        val arguments = InstrumentationRegistry.getArguments()
        val nonce = requireNonBlankBoardTestArgument(
            arguments.getString(ARGUMENT_NONCE),
            ARGUMENT_NONCE,
        ).also { value ->
            require(NONCE_PATTERN.matches(value)) { "$ARGUMENT_NONCE is invalid" }
        }
        val confirmation = requireNonBlankBoardTestArgument(
            arguments.getString(ARGUMENT_CONFIRMATION),
            ARGUMENT_CONFIRMATION,
        )
        require(confirmation == REQUIRED_CONFIRMATION) {
            "$ARGUMENT_CONFIRMATION does not authorize real hardware mode"
        }
        val expectedDevicePath = LocalConfigurationPolicy.normalizeHardwareDevicePath(
            requireNonBlankBoardTestArgument(
                arguments.getString(ARGUMENT_DEVICE_PATH),
                ARGUMENT_DEVICE_PATH,
            ),
            productionBuild = true,
        )
        val expectedBaudRate = requireNonBlankBoardTestArgument(
            arguments.getString(ARGUMENT_BAUD_RATE),
            ARGUMENT_BAUD_RATE,
        ).toIntOrNull()?.let(LocalConfigurationPolicy::validateHardwareBaudRate)
            ?: throw IllegalArgumentException("$ARGUMENT_BAUD_RATE must be an integer")

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val persistence = RuntimeConfigStore(context).persistRealHardwareMode(
            expectedHardwareDevicePath = expectedDevicePath,
            expectedHardwareBaudRate = expectedBaudRate,
        )

        assertEquals(expectedDevicePath, persistence.hardwareDevicePath)
        assertEquals(expectedBaudRate, persistence.hardwareBaudRate)
        if (persistence.changed) {
            assertTrue(persistence.previousRevision < Long.MAX_VALUE)
            assertEquals(persistence.previousRevision + 1, persistence.revision)
        } else {
            assertEquals(persistence.previousRevision, persistence.revision)
        }
        RealHardwareModePreparationResultStore.write(
            directory = context.filesDir,
            contents = buildString {
                appendLine("schema=1")
                appendLine("nonce=$nonce")
                appendLine("status=PASS")
                appendLine("changed=${persistence.changed}")
                appendLine("previous_revision=${persistence.previousRevision}")
                appendLine("revision=${persistence.revision}")
                appendLine("hardware_device_path=${persistence.hardwareDevicePath}")
                appendLine("hardware_baud_rate=${persistence.hardwareBaudRate}")
                appendLine("simulator_enabled=false")
            },
        )
    }

    companion object {
        const val ARGUMENT_NONCE = "realHardwareModeNonce"
        const val ARGUMENT_CONFIRMATION = "realHardwareModeConfirmation"
        const val ARGUMENT_DEVICE_PATH = "realHardwareDevicePath"
        const val ARGUMENT_BAUD_RATE = "realHardwareBaudRate"
        const val REQUIRED_CONFIRMATION = "PERSIST_REAL_UART_CONFIGURATION"
        private val NONCE_PATTERN = Regex("^[a-f0-9]{32}$")
    }
}

private object RealHardwareModePreparationResultStore {
    private const val RESULT_DIRECTORY = "board-real-hardware-mode"
    private const val RESULT_FILE = "result.properties"

    fun write(directory: File, contents: String) {
        val resultDirectory = File(directory, RESULT_DIRECTORY)
        check(resultDirectory.isDirectory || resultDirectory.mkdirs()) {
            "failed to create real hardware mode result directory"
        }
        val atomicFile = AtomicFile(File(resultDirectory, RESULT_FILE))
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomicFile.finishWrite(output)
            output = null
        } catch (failure: Throwable) {
            output?.let(atomicFile::failWrite)
            throw failure
        }
    }
}
