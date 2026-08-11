package com.example.helmet

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioHardwareInstrumentedTest {
    @Test(timeout = 15_000)
    fun outputHalConsumesPlaybackPcm() {
        requireExplicitProbe()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val audioManager = context.getSystemService(AudioManager::class.java)
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        assertTrue(outputDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER })

        val playback = playLowAmplitudeTone()
        val status = Bundle().apply {
            putString("audioOutputDeviceTypes", outputDevices.joinToString(",") { it.type.toString() })
            putInt("audioPlaybackSamplesWritten", playback.samplesWritten)
            putInt("audioPlaybackHeadFrames", playback.playbackHeadFrames)
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(STATUS_CODE, status)

        assertEquals(PLAYBACK_SAMPLE_COUNT, playback.samplesWritten)
        assertTrue(
            "audio HAL did not consume enough playback frames",
            playback.playbackHeadFrames >= PLAYBACK_SAMPLE_COUNT - PLAYBACK_HEAD_TOLERANCE_FRAMES,
        )
    }

    @Test(timeout = 15_000)
    fun builtInMicrophoneProducesPcm() {
        requireExplicitProbe()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val permissionWasGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!permissionWasGranted) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.RECORD_AUDIO,
            )
        }
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO),
        )

        val audioManager = context.getSystemService(AudioManager::class.java)
        val inputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        assertTrue(inputDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC })

        val capture = captureMicrophonePcm()
        val status = Bundle().apply {
            putString("audioInputDeviceTypes", inputDevices.joinToString(",") { it.type.toString() })
            putBoolean("recordAudioPermissionAlreadyGranted", permissionWasGranted)
            putBoolean("microphoneMuted", audioManager.isMicrophoneMute)
            putInt("audioCaptureRoutedDeviceType", capture.routedDeviceType)
            putInt("audioCaptureSamples", capture.sampleCount)
            putInt("audioCaptureNonZeroSamples", capture.nonZeroSamples)
            putInt("audioCapturePeak", capture.peak)
            putDouble("audioCaptureRms", capture.rms)
        }
        instrumentation.sendStatus(STATUS_CODE, status)

        assertEquals(CAPTURE_SAMPLE_COUNT, capture.sampleCount)
        assertTrue("microphone PCM is entirely zero", capture.nonZeroSamples >= capture.sampleCount / 100)
        assertTrue("microphone PCM peak is zero", capture.peak > 0)
    }

    private fun requireExplicitProbe() {
        assumeTrue(
            "explicit hardware audio probe is required",
            InstrumentationRegistry.getArguments().getString(PROBE_ARGUMENT) == "true",
        )
    }

    private fun captureMicrophonePcm(): CaptureResult {
        val minimumBytes = AudioRecord.getMinBufferSize(
            CAPTURE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assertTrue("invalid AudioRecord minimum buffer: $minimumBytes", minimumBytes > 0)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(CAPTURE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minimumBytes, CAPTURE_SAMPLE_COUNT * Short.SIZE_BYTES))
            .build()
        try {
            assertEquals(AudioRecord.STATE_INITIALIZED, recorder.state)
            val samples = ShortArray(CAPTURE_SAMPLE_COUNT)
            recorder.startRecording()
            assertEquals(AudioRecord.RECORDSTATE_RECORDING, recorder.recordingState)
            val routedDeviceType = recorder.routedDevice?.type ?: AudioDeviceInfo.TYPE_UNKNOWN
            var offset = 0
            while (offset < samples.size) {
                val read = recorder.read(
                    samples,
                    offset,
                    samples.size - offset,
                    AudioRecord.READ_BLOCKING,
                )
                assertTrue("AudioRecord read failed: $read", read > 0)
                offset += read
            }
            val nonZero = samples.count { it.toInt() != 0 }
            val peak = samples.maxOf { abs(it.toInt()) }
            val meanSquare = samples.fold(0.0) { total, sample ->
                val value = sample.toDouble()
                total + value * value
            } / samples.size
            return CaptureResult(
                sampleCount = offset,
                nonZeroSamples = nonZero,
                peak = peak,
                rms = sqrt(meanSquare),
                routedDeviceType = routedDeviceType,
            )
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }
    }

    private fun playLowAmplitudeTone(): PlaybackResult {
        val minimumBytes = AudioTrack.getMinBufferSize(
            PLAYBACK_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        assertTrue("invalid AudioTrack minimum buffer: $minimumBytes", minimumBytes > 0)
        val samples = ShortArray(PLAYBACK_SAMPLE_COUNT) { index ->
            (sin(2.0 * PI * TONE_HERTZ * index / PLAYBACK_SAMPLE_RATE) * TONE_AMPLITUDE).roundToInt().toShort()
        }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(PLAYBACK_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minimumBytes, samples.size * Short.SIZE_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            assertEquals(AudioTrack.STATE_INITIALIZED, track.state)
            track.setVolume(PLAYBACK_VOLUME)
            track.play()
            val written = track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            assertTrue("AudioTrack write failed: $written", written > 0)
            val deadline = SystemClock.elapsedRealtime() + PLAYBACK_DEADLINE_MILLIS
            while (
                track.playbackHeadPosition < written - PLAYBACK_HEAD_TOLERANCE_FRAMES &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(20)
            }
            return PlaybackResult(written, track.playbackHeadPosition)
        } finally {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.release()
        }
    }

    private data class CaptureResult(
        val sampleCount: Int,
        val nonZeroSamples: Int,
        val peak: Int,
        val rms: Double,
        val routedDeviceType: Int,
    )

    private data class PlaybackResult(
        val samplesWritten: Int,
        val playbackHeadFrames: Int,
    )

    companion object {
        private const val PROBE_ARGUMENT = "hardwareAudioProbe"
        private const val STATUS_CODE = 2
        private const val CAPTURE_SAMPLE_RATE = 16_000
        private const val CAPTURE_SAMPLE_COUNT = 16_000
        private const val PLAYBACK_SAMPLE_RATE = 44_100
        private const val PLAYBACK_SAMPLE_COUNT = 22_050
        private const val PLAYBACK_HEAD_TOLERANCE_FRAMES = 512
        private const val PLAYBACK_DEADLINE_MILLIS = 2_000L
        private const val TONE_HERTZ = 1_000.0
        private const val TONE_AMPLITUDE = 3_276.0
        private const val PLAYBACK_VOLUME = 0.10f
    }
}
