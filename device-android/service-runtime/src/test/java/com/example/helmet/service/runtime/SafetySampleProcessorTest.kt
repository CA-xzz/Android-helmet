package com.example.helmet.service.runtime

import com.example.helmet.core.model.SafetyThresholdConfig
import com.example.helmet.core.model.SafetySensorTelemetry
import com.example.helmet.core.model.canonicalFingerprint
import com.example.helmet.core.protocol.HslFlags
import com.example.helmet.core.protocol.HslMessageType
import com.example.helmet.core.protocol.HslOutputPayloadCodec
import com.example.helmet.hardware.api.HardwareAlarmOrigin
import com.example.helmet.hardware.api.HardwareEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetySampleProcessorTest {
    @Test
    fun rawImuSamplesProduceAndroidFallAlarm() {
        val processor = SafetySampleProcessor(SafetyThresholdConfig())
        processor.process(imu(1, 1, 0, 0, 1_000))
        processor.process(imu(2, 101, 0, 0, 100))
        processor.process(imu(3, 241, 0, 0, 100))

        val alarm = processor.process(imu(4, 301, 2_400, 0, 0)).alarms.single()

        assertEquals("FALL", alarm.event.alarmType)
        assertEquals("CRITICAL", alarm.event.severity)
        assertEquals(HardwareAlarmOrigin.ANDROID_DETECTION, alarm.event.origin)
        assertFalse(alarm.event.simulated)
        assertEquals("FREE_FALL_THEN_IMPACT", alarm.decision.reasonCode)
        assertTrue(alarm.event.alarmId != null)
    }

    @Test
    fun electricAlarmAndClearShareEpisodeIdAndEvidence() {
        val processor = SafetySampleProcessor(
            SafetyThresholdConfig(
                electricCalibrationSamples = 3,
                electricConfirmationSamples = 2,
                heightCalibrationSamples = 3,
            ),
        )
        (1L..3L).forEach { processor.process(field(it, it * 100, 100)) }
        processor.process(field(4, 400, 300))
        val active = processor.process(field(5, 500, 300)).alarms.single()
        processor.process(field(6, 600, 100))
        val clear = processor.process(field(7, 700, 100)).alarms.single()

        assertTrue(active.event.active)
        assertFalse(clear.event.active)
        assertEquals(active.event.alarmId, clear.event.alarmId)
        assertEquals(100, active.evaluation.electricFieldBaselineMilliVolts)
        assertTrue(active.evaluation.electricFieldExposureMilliVoltMillis > 0)
    }

    @Test
    fun inactivityEpisodeSurvivesCheckpointAndClearsWithTheSameAlarmId() {
        val config = SafetyThresholdConfig(inactivityMinimumMillis = 1_000)
        val first = SafetySampleProcessor(config)
        first.process(imu(1, 100, 0, 0, 1_000))
        val active = first.process(imu(2, 1_100, 0, 0, 1_000)).alarms.single().event

        val restored = SafetySampleProcessor(config).also {
            it.restoreCheckpoint(first.checkpoint().toRecord("device", 1_000))
        }
        val clear = restored.process(imu(3, 1_200, 1_400, 0, 0)).alarms.single().event

        assertEquals("INACTIVITY", active.alarmType)
        assertTrue(active.active)
        assertFalse(clear.active)
        assertEquals(active.alarmId, clear.alarmId)
    }

    @Test
    fun moduleClockRollbackResetsDetectionState() {
        val processor = SafetySampleProcessor(
            SafetyThresholdConfig(electricCalibrationSamples = 3, heightCalibrationSamples = 3),
        )
        processor.process(field(1, 100, 100))
        processor.process(field(2, 200, 100))
        processor.process(field(3, 300, 100))

        val result = processor.process(field(1, 10, 100))

        assertTrue(result.engineReset)
        assertTrue(result.alarms.isEmpty())
        assertEquals("CALIBRATING", result.evaluation.electricFieldLevel.name)
    }

    @Test
    fun restoreRebuildsHeightEpisodeAndReturnsHistoricalAlarmForIdempotentPersistence() {
        val config = SafetyThresholdConfig(
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 2,
            heightConfirmationMillis = 100,
        )
        val processor = SafetySampleProcessor(config)
        val restored = processor.restore(
            listOf(
                heightTelemetry(1, 100, 100_000, config),
                heightTelemetry(2, 200, 100_000, config),
                heightTelemetry(3, 300, 100_000, config),
                heightTelemetry(4, 400, 102_500, config),
                heightTelemetry(5, 500, 102_500, config),
            ),
        )

        assertEquals(5, restored.restoredSamples)
        assertEquals(0, restored.rejectedSamples)
        assertEquals(1, restored.alarms.size)
        assertTrue(restored.alarms.single().event.active)
        assertTrue(processor.process(heightEvent(6, 600, 100_000)).alarms.isEmpty())
        val clear = processor.process(heightEvent(7, 700, 100_000)).alarms.single().event
        assertFalse(clear.active)
        assertEquals(5L, requireNotNull(clear.alarmId) and 0xFFFF_FFFFL)
    }

    @Test
    fun crashAfterSampleCommitCanReplayExactlyTheSameDerivedAlarmOnRestart() {
        val telemetry = listOf(
            imuTelemetry(1, 1, 0, 0, 1_000),
            imuTelemetry(2, 101, 0, 0, 100),
            imuTelemetry(3, 241, 0, 0, 100),
            imuTelemetry(4, 301, 2_400, 0, 0),
        )

        val firstRestart = SafetySampleProcessor(SafetyThresholdConfig()).restore(telemetry)
        val secondRestart = SafetySampleProcessor(SafetyThresholdConfig()).restore(telemetry)

        assertEquals(1, firstRestart.alarms.size)
        assertEquals(firstRestart.alarms.single().event, secondRestart.alarms.single().event)
        assertEquals("FALL", firstRestart.alarms.single().event.alarmType)
    }

    @Test
    fun androidAlarmCreatesReliableLatchedOutputCommand() {
        val processor = SafetySampleProcessor(SafetyThresholdConfig())
        processor.process(imu(1, 1, 0, 0, 1_000))
        val alarm = processor.process(imu(2, 50, 3_200, 0, 0)).alarms.single().event

        val command = requireNotNull(safetyOutputCommand(alarm, 0x8123, requestId = 44))
        val output = HslOutputPayloadCodec.decode(command.payload)

        assertEquals(HslMessageType.SET_OUTPUT, command.type)
        assertEquals(HslFlags.ACK_REQUIRED, command.flags)
        assertEquals(0x8123, command.sequence)
        assertTrue(output.active)
        assertEquals(alarm.localActions, output.actionMask)
        assertEquals(44, output.requestId)
    }

    @Test
    fun simulatedOrExternalAlarmDoesNotCommandOutputs() {
        val event = HardwareEvent.Alarm(
            monotonicMillis = 1,
            alarmType = "IMPACT",
            severity = "HIGH",
            simulated = true,
            alarmId = 1,
            localActions = 7,
            origin = HardwareAlarmOrigin.ANDROID_DETECTION,
        )

        assertNull(safetyOutputCommand(event, 1, requestId = 1))
        assertNull(
            safetyOutputCommand(
                event.copy(simulated = false, origin = HardwareAlarmOrigin.EXTERNAL_MODULE),
                1,
                requestId = 1,
            ),
        )
    }

    @Test
    fun checkpointSurvivesMoreThanRawRetentionWindowAndClearsSameEpisode() {
        val config = SafetyThresholdConfig(
            version = 12,
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 2,
            heightCalibrationSamples = 3,
        )
        val beforeRestart = SafetySampleProcessor(config)
        (1L..3L).forEach { beforeRestart.process(field(it, it * 100, 100)) }
        beforeRestart.process(field(4, 400, 350))
        val active = beforeRestart.process(field(5, 500, 350)).alarms.single().event
        (6L..10_050L).forEach { reference ->
            assertTrue(beforeRestart.process(field(reference, reference * 100, 350)).alarms.isEmpty())
        }

        val record = beforeRestart.checkpoint().toRecord("device", 1_000)
        val afterRestart = SafetySampleProcessor(config).also { it.restoreCheckpoint(record) }
        afterRestart.process(field(10_051, 1_005_100, 100))
        val clear = afterRestart.process(field(10_052, 1_005_200, 100)).alarms.single()

        assertFalse(clear.event.active)
        assertEquals(active.alarmId, clear.event.alarmId)
        assertTrue(clear.evaluation.electricFieldExposureMilliVoltMillis > 0)
        assertEquals(12, clear.event.configVersion)
    }

    @Test
    fun checkpointRoundTripPreservesHeightActiveEpisodeAndCandidateState() {
        val config = SafetyThresholdConfig(
            version = 4,
            electricCalibrationSamples = 3,
            heightCalibrationSamples = 3,
            heightConfirmationSamples = 2,
            heightConfirmationMillis = 100,
        )
        val first = SafetySampleProcessor(config)
        (1L..3L).forEach { first.process(heightEvent(it, it * 100, 100_000)) }
        first.process(heightEvent(4, 400, 102_500))
        val active = first.process(heightEvent(5, 500, 102_500)).alarms.single().event

        val restored = SafetySampleProcessor(config).also {
            it.restoreCheckpoint(first.checkpoint().toRecord("device", 1_000))
        }
        restored.process(heightEvent(6, 600, 100_000))
        val clear = restored.process(heightEvent(7, 700, 100_000)).alarms.single().event

        assertFalse(clear.active)
        assertEquals(active.alarmId, clear.alarmId)
    }

    @Test
    fun thresholdIdentityChangeAndFutureCheckpointAreRejectedWithoutReplay() {
        val oldProcessor = SafetySampleProcessor(SafetyThresholdConfig(version = 2))
        oldProcessor.process(imu(1, 100, 0, 0, 1_000))
        val old = oldProcessor.checkpoint().toRecord("device", 1_000)

        assertThrows(IllegalArgumentException::class.java) {
            SafetySampleProcessor(SafetyThresholdConfig(version = 3)).restoreCheckpoint(old)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafetySampleProcessor(
                SafetyThresholdConfig(version = 2, heightThresholdMillimetres = 3_000),
            ).restoreCheckpoint(old)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafetySampleProcessor(SafetyThresholdConfig(version = 2)).restoreCheckpoint(
                old.copy(schemaVersion = 2),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            SafetySampleProcessor(SafetyThresholdConfig(version = 2)).restoreCheckpoint(
                old.copy(schemaVersion = CHECKPOINT_SCHEMA_VERSION + 1),
            )
        }
    }

    @Test
    fun corruptCheckpointPayloadIsRejected() {
        val processor = SafetySampleProcessor(SafetyThresholdConfig())
        processor.process(imu(1, 100, 0, 0, 1_000))
        val record = processor.checkpoint().toRecord("device", 1_000)

        assertThrows(IllegalArgumentException::class.java) {
            SafetySampleProcessor(SafetyThresholdConfig()).restoreCheckpoint(
                record.copy(payload = "not-base64"),
            )
        }
    }

    @Test
    fun u32ClockWrapDurablyTerminatesLatchedEpisodeBeforeFreshBaseline() {
        val config = SafetyThresholdConfig(
            version = 6,
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 1,
            heightCalibrationSamples = 3,
        )
        val processor = SafetySampleProcessor(config)
        val start = 0xF000_0000L
        (1L..3L).forEach { reference -> processor.process(field(reference, start + reference * 100, 100)) }
        val active = processor.process(field(4, start + 400, 350)).alarms.single().event

        val wrapped = processor.process(field(5, 10, 100))
        val clear = wrapped.alarms.single()

        assertTrue(wrapped.engineReset)
        assertFalse(clear.event.active)
        assertEquals(active.alarmId, clear.event.alarmId)
        assertEquals("MODULE_MONOTONIC_U32_WRAP", clear.decision.reasonCode)
        assertEquals("CALIBRATING", wrapped.evaluation.electricFieldLevel.name)
    }

    @Test
    fun clockWrapCannotClearEpisodeUntilSampleReferenceStrictlyAdvances() {
        val config = SafetyThresholdConfig(
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 1,
            heightCalibrationSamples = 3,
        )
        val processor = SafetySampleProcessor(config)
        val start = 0xF000_0000L
        (97L..99L).forEach { reference ->
            processor.process(field(reference, start + (reference - 96) * 100, 100))
        }
        val active = processor.process(field(100, start + 400, 350)).alarms.single().event

        assertThrows(IllegalArgumentException::class.java) {
            processor.process(field(99, 10, 100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            processor.process(field(100, 11, 100))
        }
        assertEquals(100L, processor.checkpoint().lastSampleReference)

        val clear = processor.process(field(101, 12, 100)).alarms.single().event
        assertFalse(clear.active)
        assertEquals(active.alarmId, clear.alarmId)
        assertEquals(101L, clear.sampleReference)
    }

    @Test
    fun moduleClockRollbackTerminatesLatchedEpisodeBeforeRecalibration() {
        val config = SafetyThresholdConfig(
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 1,
            heightCalibrationSamples = 3,
        )
        val processor = SafetySampleProcessor(config)
        (1L..3L).forEach { processor.process(field(it, it * 100, 100)) }
        val active = processor.process(field(4, 400, 350)).alarms.single().event

        val reset = processor.process(field(5, 20, 100))

        assertEquals(active.alarmId, reset.alarms.single().event.alarmId)
        assertEquals("MODULE_MONOTONIC_RESET", reset.alarms.single().decision.reasonCode)
        assertFalse(reset.alarms.single().event.active)
    }

    @Test
    fun checkpointedElectricEpisodeClearsOnRangeFaultAndFaultClearsAfterRestart() {
        val config = SafetyThresholdConfig(
            version = 8,
            electricCalibrationSamples = 3,
            electricConfirmationSamples = 1,
            heightCalibrationSamples = 3,
        )
        val first = SafetySampleProcessor(config)
        (1L..3L).forEach { first.process(field(it, it * 100, 100)) }
        val nearActive = first.process(field(4, 400, 350)).alarms.single().event
        val restoredNear = SafetySampleProcessor(config).also {
            it.restoreCheckpoint(first.checkpoint().toRecord("device", 1_000))
        }

        val fault = restoredNear.process(field(5, 500, 6_000)).alarms
        val nearClear = fault.single { it.event.alarmType == "NEAR_ELECTRIC" }.event
        val faultActive = fault.single { it.event.alarmType == "SENSOR_FAULT" }.event
        assertFalse(nearClear.active)
        assertEquals(nearActive.alarmId, nearClear.alarmId)
        assertTrue(faultActive.active)

        val restoredFault = SafetySampleProcessor(config).also {
            it.restoreCheckpoint(restoredNear.checkpoint().toRecord("device", 1_100))
        }
        val faultClear = restoredFault.process(field(6, 600, 100)).alarms.single().event
        assertFalse(faultClear.active)
        assertEquals(faultActive.alarmId, faultClear.alarmId)
    }

    private fun imu(reference: Long, time: Long, x: Int, y: Int, z: Int) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0001,
        accelerationXMilliG = x,
        accelerationYMilliG = y,
        accelerationZMilliG = z,
        gyroXMilliDegreesPerSecond = 0,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
        electricFieldMilliVolts = null,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
    )

    private fun field(reference: Long, time: Long, value: Int) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0002,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = value,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
    )

    private fun heightEvent(reference: Long, time: Long, value: Int) = HardwareEvent.SensorSample(
        monotonicMillis = time,
        sampleReference = reference,
        validFlags = 0x0008,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = null,
        pressurePascals = 101_325,
        temperatureCentiCelsius = null,
        altitudeMillimetres = value,
        simulated = false,
    )

    private fun heightTelemetry(
        reference: Long,
        time: Long,
        value: Int,
        config: SafetyThresholdConfig,
    ) = SafetySensorTelemetry(
        sampleId = "device:$reference",
        deviceId = "device",
        sampleReference = reference,
        monotonicMillis = time,
        validFlags = 0x0008,
        accelerationXMilliG = null,
        accelerationYMilliG = null,
        accelerationZMilliG = null,
        gyroXMilliDegreesPerSecond = null,
        gyroYMilliDegreesPerSecond = null,
        gyroZMilliDegreesPerSecond = null,
        electricFieldMilliVolts = null,
        pressurePascals = 101_325,
        temperatureCentiCelsius = null,
        altitudeMillimetres = value,
        simulated = false,
        recordedAtEpochMillis = time,
        thresholdConfigVersion = config.version,
        thresholdConfigFingerprint = config.canonicalFingerprint(),
    )

    private fun imuTelemetry(reference: Long, time: Long, x: Int, y: Int, z: Int) = SafetySensorTelemetry(
        sampleId = "device:$reference",
        deviceId = "device",
        sampleReference = reference,
        monotonicMillis = time,
        validFlags = 0x0001,
        accelerationXMilliG = x,
        accelerationYMilliG = y,
        accelerationZMilliG = z,
        gyroXMilliDegreesPerSecond = 0,
        gyroYMilliDegreesPerSecond = 0,
        gyroZMilliDegreesPerSecond = 0,
        electricFieldMilliVolts = null,
        pressurePascals = null,
        temperatureCentiCelsius = null,
        altitudeMillimetres = null,
        simulated = false,
        recordedAtEpochMillis = time,
        thresholdConfigVersion = 1,
        thresholdConfigFingerprint = SafetyThresholdConfig().canonicalFingerprint(),
    )
}
