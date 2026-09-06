package com.example.helmet.debug

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import com.example.helmet.core.protocol.HslDecoderStats
import com.example.helmet.core.protocol.HslFrame
import com.example.helmet.core.protocol.HslFrameCodec
import com.example.helmet.core.protocol.HslStreamDecoder
import com.example.helmet.hardware.api.BoundHardwareGateway
import com.example.helmet.hardware.api.HardwareProviderConnection
import com.example.helmet.hardware.api.IHelmetHardwareCallback
import com.example.helmet.hardware.api.IHelmetHardwareService
import com.example.helmet.service.runtime.HardwareRuntimeMaintenance
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Root-triggered debug verification of the private production hardware provider and AIDL runtime.
 * This component is absent from release builds and deliberately has no intent filter or exported
 * entry point.
 */
class HardwareSelfTestService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "helmet-hardware-self-test")
    }
    private val running = AtomicBoolean()
    private val cancellationRequested = AtomicBoolean()
    private val releaseObservationWindow = CountDownLatch(1)

    override fun onCreate() {
        super.onCreate()
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Hardware self-test",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        startForeground(
            NOTIFICATION_ID,
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("Hardware self-test")
                .setContentText("Debug verification is running")
                .setOngoing(true)
                .build(),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY

        val suppliedNonce = intent?.getStringExtra(EXTRA_NONCE).orEmpty()
        val nonce = suppliedNonce.takeIf(NONCE_PATTERN::matches) ?: INVALID_NONCE
        val recoveryOnly = intent?.getBooleanExtra(EXTRA_RECOVERY_ONLY, false) == true
        val writer = HardwareSelfTestResultWriter(this)
        val recoveryWriter = HardwareSelfTestRecoveryWriter(this)
        runCatching {
            if (recoveryOnly) {
                recoveryWriter.rotatePreviousResult()
            } else {
                writer.rotatePreviousResult()
                recoveryWriter.rotatePreviousResult()
            }
        }.onFailure {
            running.set(false)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        executor.execute {
            executionLock.lock()
            try {
                if (recoveryOnly) {
                    val recoveryResult = if (nonce == INVALID_NONCE) {
                        HardwareSelfTestRecoveryResult.failure(nonce, "REQUEST_FAILED")
                    } else {
                        HardwareSelfTestRecoveryRunner(this).run(nonce)
                    }
                    runCatching { recoveryWriter.write(recoveryResult) }
                } else {
                    val result = if (nonce == INVALID_NONCE) {
                        HardwareSelfTestRunOutcome.noResult()
                    } else {
                        HardwareSelfTestRunner(this, cancellationRequested::get).run(nonce)
                    }
                    result.result?.let { completed -> runCatching { writer.write(completed) } }
                    result.recoveryDirective?.let { directive ->
                        runCatching { recoveryWriter.write(directive) }
                    }
                }

                // Keep the verified provider client open long enough for the host to verify its
                // PID and UID. onDestroy releases this wait without interrupting recovery.
                runCatching {
                    releaseObservationWindow.await(
                        HOST_OBSERVATION_WINDOW_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                }
            } finally {
                HardwareSelfTestRunner.releaseActiveConnection()
                running.set(false)
                executionLock.unlock()
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        cancellationRequested.set(true)
        releaseObservationWindow.countDown()
        // Do not interrupt the runner or close its provider client. Its bounded finally path owns
        // maintenance resume and client release even when the Android component is destroyed.
        executor.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_NONCE = "nonce"
        const val EXTRA_RECOVERY_ONLY = "recovery_only"
        private const val INVALID_NONCE = "INVALID"
        private const val NOTIFICATION_CHANNEL_ID = "helmet-hardware-self-test"
        private const val NOTIFICATION_ID = 1901
        private const val HOST_OBSERVATION_WINDOW_MILLIS = 30_000L
        private val NONCE_PATTERN = Regex("^[a-f0-9]{32}$")
        private val executionLock = ReentrantLock()
    }
}

private class HardwareSelfTestRunner(
    private val context: Context,
    private val cancellationRequested: () -> Boolean,
) {
    fun run(nonce: String): HardwareSelfTestRunOutcome {
        val progress = HardwareSelfTestProgress()
        var service: IHelmetHardwareService? = null
        var pauseLease: HardwareRuntimeMaintenance.PauseLease? = null
        var ownsProviderPort = false
        var processRecoveryRequired = false
        var result = try {
            selfTestCheck(!cancellationRequested())
            val connection = try {
                HardwareProviderConnection.connect(context).also(::setActiveConnection)
            } catch (_: Throwable) {
                throw HardwareSelfTestStageException(HardwareSelfTestStage.PROVIDER_CONNECT)
            }
            val connectedService = connection.service
            service = connectedService

            // The foreground service stays alive. Its production gateway releases the provider
            // callback and UART through the same deterministic stop barrier used at shutdown.
            runSelfTestStage(HardwareSelfTestStage.MAINTENANCE_PAUSE) {
                val pauseOutcome = runBlocking { HardwareRuntimeMaintenance.pause() }
                pauseLease = pauseOutcome.lease
                selfTestCheck(pauseOutcome.failure == null)
                progress.registeredCallbacksBeforePty =
                    awaitRegisteredCallbacks(connectedService, 0)
                ownsProviderPort = true
                connectedService.closePort()
            }
            selfTestCheck(ownsProviderPort)
            selfTestCheck(!cancellationRequested())

            runSelfTestStage(HardwareSelfTestStage.POLICY) {
                verifyPolicy(connection)
                progress.policyPass = 1
            }

            runSelfTestStage(HardwareSelfTestStage.PTY_OPEN) {
                selfTestCheck(!cancellationRequested())
                verifyPtyOpen(connectedService)
                progress.ptyOpenPass = 1
            }

            progress.framesObserved = verifyConcurrentFrames(connectedService, progress)
            progress.concurrentFramesPass = 1

            runSelfTestStage(HardwareSelfTestStage.GENERATION_ISOLATION) {
                selfTestCheck(!cancellationRequested())
                val generationCounts = verifyGenerationIsolation(connectedService)
                progress.generationOldFrames = generationCounts.first
                progress.generationNewFrames = generationCounts.second
                progress.generationIsolationPass = 1
            }

            runSelfTestStage(HardwareSelfTestStage.INVALID_PATH) {
                selfTestCheck(!cancellationRequested())
                verifyInvalidPathRejected(connectedService)
                progress.invalidPathPass = 1
            }

            HardwareSelfTestResult.success(nonce, progress)
        } catch (_: HardwareSelfTestProcessRecoveryRequiredException) {
            markProcessRecoveryRequired()
            processRecoveryRequired = true
            HardwareSelfTestResult.failure(
                nonce,
                HardwareSelfTestStage.EXECUTOR_DRAIN,
                progress,
            )
        } catch (failure: HardwareSelfTestStageException) {
            HardwareSelfTestResult.failure(nonce, failure.stage, progress)
        } catch (_: Throwable) {
            HardwareSelfTestResult.failure(nonce, HardwareSelfTestStage.INTERNAL, progress)
        }

        val capturedService = service
        val capturedLease = pauseLease
        if (processRecoveryRequired) {
            return HardwareSelfTestRunOutcome.processRestartRequired(nonce)
        }
        if (capturedService == null || capturedLease == null) {
            return HardwareSelfTestRunOutcome.noResult()
        }
        val resumed = runCatching {
            // Every self-test port and callback is released before production UART ownership
            // resumes. The retained provider client is closed later without touching the port.
            if (ownsProviderPort) runCatching { capturedService.closePort() }
            progress.registeredCallbacksAfterResume = resumeProductionGateway(
                capturedService,
                capturedLease,
            )
            ownsProviderPort = false
        }.isSuccess
        if (!resumed || progress.registeredCallbacksAfterResume != 1) {
            return HardwareSelfTestRunOutcome.noResult()
        }
        return HardwareSelfTestRunOutcome.completed(result)
    }

    private fun resumeProductionGateway(
        service: IHelmetHardwareService,
        lease: HardwareRuntimeMaintenance.PauseLease,
    ): Int {
        var accepted = false
        repeat(MAINTENANCE_RESUME_ATTEMPTS) {
            accepted = runCatching {
                runBlocking {
                    withContext(NonCancellable) {
                        HardwareRuntimeMaintenance.resume(lease)
                    }
                }
            }.getOrDefault(false)
            if (accepted) return awaitRegisteredCallbacks(service, expected = 1)
        }
        selfTestCheck(accepted)
        return -1
    }

    @Suppress("DEPRECATION")
    private fun verifyPolicy(connection: HardwareProviderConnection) {
        val component = ComponentName(
            TARGET_PACKAGE,
            BoundHardwareGateway.HARDWARE_PROVIDER_CLASS,
        )
        val providerInfo = context.packageManager.getProviderInfo(component, 0)
        selfTestCheck(Application.getProcessName() == TARGET_PACKAGE)
        selfTestCheck(providerInfo.packageName == TARGET_PACKAGE)
        selfTestCheck(providerInfo.processName == TARGET_HARDWARE_PROCESS)
        selfTestCheck(!providerInfo.exported)
        selfTestCheck(!providerInfo.grantUriPermissions)
        selfTestCheck(providerInfo.readPermission == BIND_PERMISSION)
        selfTestCheck(providerInfo.writePermission == BIND_PERMISSION)
        selfTestCheck(providerInfo.applicationInfo.uid == Process.myUid())
        selfTestCheck(providerInfo.authority == BoundHardwareGateway.HARDWARE_PROVIDER_AUTHORITY)
        selfTestCheck(connection.authority == providerInfo.authority)
        selfTestCheck(connection.binder !is Binder)
        selfTestCheck(
            connection.binder.interfaceDescriptor == IHelmetHardwareService.Stub.DESCRIPTOR,
        )

        val permissionInfo = context.packageManager.getPermissionInfo(BIND_PERMISSION, 0)
        val baseProtection = permissionInfo.protectionLevel and PermissionInfo.PROTECTION_MASK_BASE
        selfTestCheck(baseProtection == PermissionInfo.PROTECTION_SIGNATURE)
        selfTestCheck(
            context.checkSelfPermission(BIND_PERMISSION) == PackageManager.PERMISSION_GRANTED,
        )
    }

    private fun verifyPtyOpen(service: IHelmetHardwareService) {
        HardwareSelfTestPtyFixture().use { fixture ->
            try {
                service.openPort(fixture.slavePath, TEST_BAUD_RATE)
                val diagnostics = service.diagnostics
                selfTestCheck(diagnostics.getBoolean("isOpen"))
                selfTestCheck(diagnostics.getInt("baudRate") == TEST_BAUD_RATE)
            } finally {
                service.closePort()
            }
        }
    }

    private fun awaitRegisteredCallbacks(
        service: IHelmetHardwareService,
        expected: Int,
    ): Int {
        val deadline = SystemClock.elapsedRealtime() + CALLBACK_RELEASE_TIMEOUT_MILLIS
        var registeredCallbacks = service.diagnostics.getInt("registeredCallbacks", -1)
        while (registeredCallbacks != expected && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
            registeredCallbacks = service.diagnostics.getInt("registeredCallbacks", -1)
        }
        selfTestCheck(registeredCallbacks == expected)
        return registeredCallbacks
    }

    private fun verifyConcurrentFrames(
        service: IHelmetHardwareService,
        progress: HardwareSelfTestProgress,
    ): Int = try {
        HardwareSelfTestPtyFixture().use { fixture ->
            val expectedPayloads = (1..FRAME_COUNT).associateWith { sequence ->
                ByteArray(PAYLOAD_SIZE) { offset -> (sequence * 31 + offset).toByte() }
            }
            val reader = Executors.newSingleThreadExecutor()
            val writers = Executors.newFixedThreadPool(8)
            val observedBytes = AtomicLong()
            val observedFrames = AtomicInteger()
            val crcErrors = AtomicLong()
            val lengthErrors = AtomicLong()
            val versionErrors = AtomicLong()
            val discardedBytes = AtomicLong()
            var readResult: Future<Pair<List<HslFrame>, HslDecoderStats>>? = null
            val writeResults = mutableListOf<Future<*>>()
            try {
                service.openPort(fixture.slavePath, TEST_BAUD_RATE)
                val transmitFramesBefore = service.diagnostics.getLong("transmitFrames")
                readResult = reader.submit<Pair<List<HslFrame>, HslDecoderStats>> {
                    val decoder = HslStreamDecoder()
                    val frames = mutableListOf<HslFrame>()
                    val deadline = SystemClock.elapsedRealtime() + SERIAL_TEST_TIMEOUT_MILLIS
                    while (
                        frames.size < FRAME_COUNT &&
                        !cancellationRequested() &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        val bytes = fixture.read(timeoutMillis = 250)
                        if (bytes.isNotEmpty()) {
                            observedBytes.addAndGet(bytes.size.toLong())
                            frames += decoder.feed(bytes)
                            observedFrames.set(frames.size)
                            val stats = decoder.stats
                            crcErrors.set(stats.crcErrors)
                            lengthErrors.set(stats.lengthErrors)
                            versionErrors.set(stats.versionErrors)
                            discardedBytes.set(stats.discardedBytes)
                        }
                    }
                    frames to decoder.stats
                }
                val start = CountDownLatch(1)
                val completedWrites = AtomicInteger()
                expectedPayloads.forEach { (sequence, payload) ->
                    writeResults += writers.submit {
                        selfTestCheck(!cancellationRequested())
                        selfTestCheck(start.await(5, TimeUnit.SECONDS))
                        service.sendFrame(TEST_FRAME_TYPE, 0, sequence, payload)
                        completedWrites.incrementAndGet()
                    }
                }
                start.countDown()
                try {
                    writeResults.forEach { future ->
                        future.get(SERIAL_TEST_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                    }
                } catch (_: Throwable) {
                    progress.concurrentWritesCompleted = completedWrites.get()
                    throw HardwareSelfTestStageException(HardwareSelfTestStage.CONCURRENT_WRITES)
                }
                progress.concurrentWritesCompleted = completedWrites.get()

                val (frames, stats) = try {
                    checkNotNull(readResult).get(
                        SERIAL_TEST_TIMEOUT_MILLIS,
                        TimeUnit.MILLISECONDS,
                    )
                } catch (error: Throwable) {
                    progress.concurrentBytesObserved = observedBytes.get()
                    progress.framesObserved = observedFrames.get()
                    progress.decoderCrcErrors = crcErrors.get()
                    progress.decoderLengthErrors = lengthErrors.get()
                    progress.decoderVersionErrors = versionErrors.get()
                    progress.decoderDiscardedBytes = discardedBytes.get()
                    progress.concurrentReadFailure = concurrentReadFailureCode(error)
                    throw HardwareSelfTestStageException(HardwareSelfTestStage.CONCURRENT_READ)
                }
                progress.concurrentBytesObserved = observedBytes.get()
                progress.framesObserved = frames.size
                progress.decoderCrcErrors = stats.crcErrors
                progress.decoderLengthErrors = stats.lengthErrors
                progress.decoderVersionErrors = stats.versionErrors
                progress.decoderDiscardedBytes = stats.discardedBytes
                runSelfTestStage(HardwareSelfTestStage.CONCURRENT_CONTENT) {
                    selfTestCheck(stats.crcErrors == 0L)
                    selfTestCheck(stats.lengthErrors == 0L)
                    selfTestCheck(stats.versionErrors == 0L)
                    selfTestCheck(frames.size == FRAME_COUNT)
                    selfTestCheck(frames.map { it.sequence }.toSet() == (1..FRAME_COUNT).toSet())
                    frames.forEach { frame ->
                        selfTestCheck(frame.type == TEST_FRAME_TYPE)
                        selfTestCheck(
                            frame.payload.contentEquals(expectedPayloads.getValue(frame.sequence)),
                        )
                    }
                }
                runSelfTestStage(HardwareSelfTestStage.CONCURRENT_DIAGNOSTICS) {
                    selfTestCheck(
                        service.diagnostics.getLong("transmitFrames") ==
                            transmitFramesBefore + FRAME_COUNT,
                    )
                }
                frames.size
            } finally {
                writeResults.forEach { future -> future.cancel(true) }
                readResult?.cancel(true)
                writers.shutdownNow()
                reader.shutdownNow()
                if (!awaitExecutorDrain(writers, reader)) {
                    markProcessRecoveryRequired()
                    throw HardwareSelfTestProcessRecoveryRequiredException()
                }
                runCatching { service.closePort() }
            }
        }
    } catch (failure: HardwareSelfTestProcessRecoveryRequiredException) {
        throw failure
    } catch (failure: HardwareSelfTestStageException) {
        throw failure
    } catch (_: Throwable) {
        throw HardwareSelfTestStageException(HardwareSelfTestStage.CONCURRENT_FRAMES)
    }

    private fun awaitExecutorDrain(
        writers: java.util.concurrent.ExecutorService,
        reader: java.util.concurrent.ExecutorService,
    ): Boolean = try {
        val deadline = SystemClock.elapsedRealtime() + EXECUTOR_DRAIN_TIMEOUT_MILLIS
        val writersDrained = writers.awaitTermination(
            EXECUTOR_DRAIN_TIMEOUT_MILLIS,
            TimeUnit.MILLISECONDS,
        )
        val remainingMillis = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        val readerDrained = reader.awaitTermination(remainingMillis, TimeUnit.MILLISECONDS)
        writersDrained && readerDrained
    } catch (_: InterruptedException) {
        false
    }

    private fun verifyGenerationIsolation(service: IHelmetHardwareService): Pair<Int, Int> {
        HardwareSelfTestPtyFixture().use { firstFixture ->
            HardwareSelfTestPtyFixture().use { secondFixture ->
                val received = CopyOnWriteArrayList<HslFrame>()
                val receivedNewFrame = CountDownLatch(1)
                val callback = object : IHelmetHardwareCallback.Stub() {
                    override fun onFrame(
                        version: Int,
                        flags: Int,
                        type: Int,
                        sequence: Int,
                        payload: ByteArray,
                    ) {
                        received += HslFrame(version, flags, type, sequence, payload.copyOf())
                        if (sequence == NEW_GENERATION_SEQUENCE) receivedNewFrame.countDown()
                    }

                    override fun onLinkStateChanged(state: Int, detail: String) = Unit
                }
                service.registerCallback(callback)
                try {
                    service.openPort(firstFixture.slavePath, TEST_BAUD_RATE)
                    val partialOldFrame = HslFrameCodec.encode(
                        HslFrame(
                            flags = 0,
                            type = TEST_FRAME_TYPE,
                            sequence = OLD_GENERATION_SEQUENCE,
                            payload = ByteArray(700) { 0x55 },
                        ),
                    ).copyOf(300)
                    val receiveBytesBefore = service.diagnostics.getLong("receiveBytes")
                    firstFixture.writeFully(partialOldFrame)
                    awaitReceiveBytes(service, receiveBytesBefore + partialOldFrame.size)

                    service.openPort(secondFixture.slavePath, TEST_BAUD_RATE)
                    val newPayload = byteArrayOf(1, 3, 5, 7, 9)
                    secondFixture.writeFully(
                        HslFrameCodec.encode(
                            HslFrame(
                                flags = 0,
                                type = TEST_FRAME_TYPE,
                                sequence = NEW_GENERATION_SEQUENCE,
                                payload = newPayload,
                            ),
                        ),
                    )
                    selfTestCheck(receivedNewFrame.await(5, TimeUnit.SECONDS))
                    val oldFrames = received.count { it.sequence == OLD_GENERATION_SEQUENCE }
                    val newFrames = received.filter { it.sequence == NEW_GENERATION_SEQUENCE }
                    selfTestCheck(oldFrames == 0)
                    selfTestCheck(newFrames.size == 1)
                    selfTestCheck(newFrames.single().payload.contentEquals(newPayload))
                    selfTestCheck(service.diagnostics.getLong("crcErrors") == 0L)
                    return oldFrames to newFrames.size
                } finally {
                    runCatching { service.unregisterCallback(callback) }
                    service.closePort()
                }
            }
        }
    }

    private fun verifyInvalidPathRejected(service: IHelmetHardwareService) {
        val rejected = runCatching {
            service.openPort(INVALID_DEVICE_PATH, TEST_BAUD_RATE)
        }.isFailure
        selfTestCheck(rejected)
        selfTestCheck(!service.diagnostics.getBoolean("isOpen"))
    }

    private fun awaitReceiveBytes(service: IHelmetHardwareService, expected: Long) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (
            service.diagnostics.getLong("receiveBytes") < expected &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            SystemClock.sleep(25)
        }
        selfTestCheck(service.diagnostics.getLong("receiveBytes") >= expected)
    }

    companion object {
        private const val TARGET_PACKAGE = "com.example.helmet"
        private const val TARGET_HARDWARE_PROCESS = "$TARGET_PACKAGE:hardware"
        private const val BIND_PERMISSION =
            "com.example.helmet.permission.BIND_HARDWARE_SERVICE"
        private const val TEST_BAUD_RATE = 115_200
        private const val TEST_FRAME_TYPE = 0x20
        private const val FRAME_COUNT = 96
        private const val PAYLOAD_SIZE = 1_024
        private const val OLD_GENERATION_SEQUENCE = 40_001
        private const val NEW_GENERATION_SEQUENCE = 40_002
        private const val SERIAL_TEST_TIMEOUT_MILLIS = 20_000L
        private const val CALLBACK_RELEASE_TIMEOUT_MILLIS = 5_000L
        private const val EXECUTOR_DRAIN_TIMEOUT_MILLIS = 5_000L
        private const val MAINTENANCE_RESUME_ATTEMPTS = 2
        private const val INVALID_DEVICE_PATH = "/data/local/tmp/not-a-uart"
        private val activeConnection = AtomicReference<HardwareProviderConnection?>()

        private fun setActiveConnection(connection: HardwareProviderConnection) {
            selfTestCheck(activeConnection.compareAndSet(null, connection))
        }

        fun releaseActiveConnection() {
            activeConnection.getAndSet(null)?.close()
        }

        private val processRecoveryRequired = AtomicBoolean()

        fun markProcessRecoveryRequired() {
            processRecoveryRequired.set(true)
        }

        fun isProcessRecoveryRequired(): Boolean = processRecoveryRequired.get()
    }
}

private data class HardwareSelfTestRunOutcome(
    val result: HardwareSelfTestResult?,
    val recoveryDirective: HardwareSelfTestRecoveryResult?,
) {
    companion object {
        fun completed(result: HardwareSelfTestResult) = HardwareSelfTestRunOutcome(
            result = result,
            recoveryDirective = null,
        )

        fun noResult() = HardwareSelfTestRunOutcome(
            result = null,
            recoveryDirective = null,
        )

        fun processRestartRequired(nonce: String) = HardwareSelfTestRunOutcome(
            result = null,
            recoveryDirective = HardwareSelfTestRecoveryResult.failure(
                nonce = nonce,
                errorCode = "EXECUTOR_DRAIN_FAILED",
                status = "RESTART_REQUIRED",
            ),
        )
    }
}

private class HardwareSelfTestRecoveryRunner(private val context: Context) {
    fun run(nonce: String): HardwareSelfTestRecoveryResult {
        if (HardwareSelfTestRunner.isProcessRecoveryRequired()) {
            return HardwareSelfTestRecoveryResult.failure(
                nonce = nonce,
                errorCode = "PROCESS_RESTART_REQUIRED",
                status = "RESTART_REQUIRED",
            )
        }
        var registeredCallbacks = -1
        val recovered = runCatching {
            HardwareProviderConnection.connect(context).use { connection ->
                var accepted = false
                for (attempt in 0 until RECOVERY_ATTEMPTS) {
                    accepted = runCatching {
                        runBlocking {
                            withContext(NonCancellable) {
                                HardwareRuntimeMaintenance.resumeCurrent()
                            }
                        }
                    }.getOrDefault(false)
                    if (accepted) break
                }
                selfTestCheck(accepted)
                val deadline = SystemClock.elapsedRealtime() + RECOVERY_TIMEOUT_MILLIS
                registeredCallbacks = connection.service.diagnostics.getInt(
                    "registeredCallbacks",
                    -1,
                )
                while (
                    registeredCallbacks != 1 &&
                    SystemClock.elapsedRealtime() < deadline
                ) {
                    SystemClock.sleep(25)
                    registeredCallbacks = connection.service.diagnostics.getInt(
                        "registeredCallbacks",
                        -1,
                    )
                }
                selfTestCheck(registeredCallbacks == 1)
            }
        }.isSuccess
        return if (recovered) {
            HardwareSelfTestRecoveryResult.success(nonce, registeredCallbacks)
        } else {
            HardwareSelfTestRecoveryResult.failure(
                nonce,
                errorCode = "RECOVERY_FAILED",
                registeredCallbacks = registeredCallbacks,
            )
        }
    }

    companion object {
        private const val RECOVERY_ATTEMPTS = 3
        private const val RECOVERY_TIMEOUT_MILLIS = 10_000L
    }
}

private data class HardwareSelfTestRecoveryResult(
    val nonce: String,
    val status: String,
    val registeredCallbacks: Int,
    val errorCode: String,
) {
    companion object {
        fun success(nonce: String, registeredCallbacks: Int) =
            HardwareSelfTestRecoveryResult(
                nonce = nonce,
                status = "PASS",
                registeredCallbacks = registeredCallbacks,
                errorCode = "NONE",
            )

        fun failure(
            nonce: String,
            errorCode: String,
            registeredCallbacks: Int = -1,
            status: String = "FAIL",
        ) = HardwareSelfTestRecoveryResult(
            nonce = nonce,
            status = status,
            registeredCallbacks = registeredCallbacks,
            errorCode = errorCode,
        )
    }
}

private data class HardwareSelfTestProgress(
    var policyPass: Int = 0,
    var ptyOpenPass: Int = 0,
    var concurrentFramesPass: Int = 0,
    var generationIsolationPass: Int = 0,
    var invalidPathPass: Int = 0,
    var framesObserved: Int = 0,
    var concurrentWritesCompleted: Int = 0,
    var concurrentBytesObserved: Long = 0,
    var concurrentReadFailure: String = "NONE",
    var decoderCrcErrors: Long = 0,
    var decoderLengthErrors: Long = 0,
    var decoderVersionErrors: Long = 0,
    var decoderDiscardedBytes: Long = 0,
    var registeredCallbacksBeforePty: Int = -1,
    var registeredCallbacksAfterResume: Int = -1,
    var generationOldFrames: Int = 0,
    var generationNewFrames: Int = 0,
) {
    val passCount: Int
        get() = policyPass + ptyOpenPass + concurrentFramesPass +
            generationIsolationPass + invalidPathPass
}

private data class HardwareSelfTestResult(
    val nonce: String,
    val status: String,
    val progress: HardwareSelfTestProgress,
    val errorCode: String,
    val errorType: String,
) {
    companion object {
        fun success(nonce: String, progress: HardwareSelfTestProgress) = HardwareSelfTestResult(
            nonce = nonce,
            status = "PASS",
            progress = progress,
            errorCode = "NONE",
            errorType = "NONE",
        )

        fun failure(
            nonce: String,
            stage: HardwareSelfTestStage,
            progress: HardwareSelfTestProgress,
        ) = HardwareSelfTestResult(
            nonce = nonce,
            status = "FAIL",
            progress = progress,
            errorCode = "${stage.name}_FAILED",
            errorType = stage.errorType,
        )
    }
}

private enum class HardwareSelfTestStage(val errorType: String) {
    REQUEST("REQUEST"),
    MAINTENANCE_PAUSE("RUNTIME"),
    MAINTENANCE_RESUME("RUNTIME"),
    PROVIDER_CONNECT("PROVIDER"),
    POLICY("POLICY"),
    PTY_OPEN("PTY"),
    CONCURRENT_FRAMES("FRAME"),
    CONCURRENT_WRITES("FRAME"),
    CONCURRENT_READ("FRAME"),
    CONCURRENT_CONTENT("FRAME"),
    CONCURRENT_DIAGNOSTICS("FRAME"),
    EXECUTOR_DRAIN("RECOVERY"),
    GENERATION_ISOLATION("GENERATION"),
    INVALID_PATH("INPUT"),
    INTERNAL("INTERNAL"),
}

private class HardwareSelfTestResultWriter(context: Context) {
    private val directory = File(context.filesDir, RESULT_DIRECTORY)
    private val resultFile = File(directory, RESULT_FILE)
    private val staleFile = File(directory, STALE_FILE)
    private val atomicNewFile = File(directory, ATOMIC_NEW_FILE)
    private val atomicBackupFile = File(directory, ATOMIC_BACKUP_FILE)

    fun rotatePreviousResult() {
        selfTestCheck(directory.isDirectory || directory.mkdirs())
        if (staleFile.exists()) selfTestCheck(staleFile.delete())
        if (resultFile.exists()) {
            selfTestCheck(resultFile.renameTo(staleFile) || resultFile.delete())
        }
        if (atomicNewFile.exists()) selfTestCheck(atomicNewFile.delete())
        if (atomicBackupFile.exists()) selfTestCheck(atomicBackupFile.delete())
    }

    fun write(result: HardwareSelfTestResult) {
        val progress = result.progress
        val content = buildString {
            appendLine("schema=3")
            appendLine("nonce=${result.nonce}")
            appendLine("status=${result.status}")
            appendLine("pass=${progress.passCount}")
            appendLine("fail=${5 - progress.passCount}")
            appendLine("policy_pass=${progress.policyPass}")
            appendLine("pty_open_pass=${progress.ptyOpenPass}")
            appendLine("concurrent_frames_pass=${progress.concurrentFramesPass}")
            appendLine("generation_isolation_pass=${progress.generationIsolationPass}")
            appendLine("invalid_path_pass=${progress.invalidPathPass}")
            appendLine("frames_expected=96")
            appendLine("frames_observed=${progress.framesObserved}")
            appendLine("concurrent_writes_completed=${progress.concurrentWritesCompleted}")
            appendLine("concurrent_bytes_observed=${progress.concurrentBytesObserved}")
            appendLine("concurrent_read_failure=${progress.concurrentReadFailure}")
            appendLine("decoder_crc_errors=${progress.decoderCrcErrors}")
            appendLine("decoder_length_errors=${progress.decoderLengthErrors}")
            appendLine("decoder_version_errors=${progress.decoderVersionErrors}")
            appendLine("decoder_discarded_bytes=${progress.decoderDiscardedBytes}")
            appendLine("registered_callbacks_before_pty=${progress.registeredCallbacksBeforePty}")
            appendLine("registered_callbacks_after_resume=${progress.registeredCallbacksAfterResume}")
            appendLine("generation_old_frames=${progress.generationOldFrames}")
            appendLine("generation_new_frames=${progress.generationNewFrames}")
            appendLine("error_code=${result.errorCode}")
            appendLine("error_type=${result.errorType}")
        }
        val atomicFile = AtomicFile(resultFile)
        var output: FileOutputStream? = atomicFile.startWrite()
        try {
            val stream = checkNotNull(output)
            stream.write(content.toByteArray(StandardCharsets.US_ASCII))
            stream.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            output = null
        } finally {
            output?.let(atomicFile::failWrite)
        }
        if (staleFile.exists()) staleFile.delete()
    }

    companion object {
        private const val RESULT_DIRECTORY = "hardware-self-test"
        private const val RESULT_FILE = "result.properties"
        private const val STALE_FILE = "result.properties.stale"
        private const val ATOMIC_NEW_FILE = "result.properties.new"
        private const val ATOMIC_BACKUP_FILE = "result.properties.bak"
    }
}

private class HardwareSelfTestRecoveryWriter(context: Context) {
    private val directory = File(context.filesDir, RESULT_DIRECTORY)
    private val resultFile = File(directory, RESULT_FILE)
    private val staleFile = File(directory, STALE_FILE)
    private val atomicNewFile = File(directory, ATOMIC_NEW_FILE)
    private val atomicBackupFile = File(directory, ATOMIC_BACKUP_FILE)

    fun rotatePreviousResult() {
        selfTestCheck(directory.isDirectory || directory.mkdirs())
        if (staleFile.exists()) selfTestCheck(staleFile.delete())
        if (resultFile.exists()) {
            selfTestCheck(resultFile.renameTo(staleFile) || resultFile.delete())
        }
        if (atomicNewFile.exists()) selfTestCheck(atomicNewFile.delete())
        if (atomicBackupFile.exists()) selfTestCheck(atomicBackupFile.delete())
    }

    fun write(result: HardwareSelfTestRecoveryResult) {
        val content = buildString {
            appendLine("schema=1")
            appendLine("nonce=${result.nonce}")
            appendLine("status=${result.status}")
            appendLine("registered_callbacks=${result.registeredCallbacks}")
            appendLine("error_code=${result.errorCode}")
        }
        val atomicFile = AtomicFile(resultFile)
        var output: FileOutputStream? = atomicFile.startWrite()
        try {
            val stream = checkNotNull(output)
            stream.write(content.toByteArray(StandardCharsets.US_ASCII))
            stream.flush()
            stream.fd.sync()
            atomicFile.finishWrite(stream)
            output = null
        } finally {
            output?.let(atomicFile::failWrite)
        }
        if (staleFile.exists()) staleFile.delete()
    }

    companion object {
        private const val RESULT_DIRECTORY = "hardware-self-test"
        private const val RESULT_FILE = "recovery.properties"
        private const val STALE_FILE = "recovery.properties.stale"
        private const val ATOMIC_NEW_FILE = "recovery.properties.new"
        private const val ATOMIC_BACKUP_FILE = "recovery.properties.bak"
    }
}

private class HardwareSelfTestCheckException : IllegalStateException()

private class HardwareSelfTestStageException(
    val stage: HardwareSelfTestStage,
) : IllegalStateException()

private class HardwareSelfTestProcessRecoveryRequiredException : IllegalStateException()

private inline fun <T> runSelfTestStage(stage: HardwareSelfTestStage, action: () -> T): T =
    try {
        action()
    } catch (_: Throwable) {
        throw HardwareSelfTestStageException(stage)
    }

private fun selfTestCheck(condition: Boolean) {
    if (!condition) throw HardwareSelfTestCheckException()
}

private fun concurrentReadFailureCode(error: Throwable): String = when (error) {
    is TimeoutException -> "TIMEOUT"
    is InterruptedException -> "INTERRUPTED"
    is ExecutionException -> when (error.cause) {
        is IOException -> "EXECUTION_IO"
        is SecurityException -> "EXECUTION_SECURITY"
        is IllegalArgumentException -> "EXECUTION_ARGUMENT"
        is IllegalStateException -> "EXECUTION_STATE"
        is InterruptedException -> "EXECUTION_INTERRUPTED"
        is RuntimeException -> "EXECUTION_RUNTIME"
        is Error -> "EXECUTION_ERROR"
        null -> "EXECUTION_NO_CAUSE"
        else -> "EXECUTION_OTHER"
    }
    else -> "OTHER"
}
