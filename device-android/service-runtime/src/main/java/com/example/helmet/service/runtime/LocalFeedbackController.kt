package com.example.helmet.service.runtime

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.example.helmet.hardware.api.SimulatedInput
import com.example.helmet.core.model.CallState
import java.util.Locale

data class LocalAlarmFeedbackResult(
    val prompt: String,
    val vibrationRequested: Boolean,
    val vibrationStarted: Boolean,
)

internal fun planAbsoluteCallVolume(
    input: SimulatedInput,
    current: Int,
    minimum: Int,
    maximum: Int,
): Int? {
    require(minimum >= 0 && maximum >= minimum && current in minimum..maximum)
    return when (input) {
        SimulatedInput.VOLUME_UP -> if (current < maximum) current + 1 else maximum
        SimulatedInput.VOLUME_DOWN -> if (current > minimum) current - 1 else minimum
        else -> null
    }
}

class LocalFeedbackController(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val vibrator = applicationContext.getSystemService(VibratorManager::class.java).defaultVibrator
    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    }.getOrNull()
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var ttsInitializationComplete = false
    private val ttsStateLock = Any()
    private val pendingAnnouncements = ArrayDeque<String>()
    private val callTextPlayback = AndroidTextPlayback(applicationContext)
    private val callStatePrompt = CallStatePrompt(callTextPlayback, ::playFallbackTone)

    init {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            val languageResult = if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.ERROR
            } else {
                TextToSpeech.ERROR
            }
            val pending = synchronized(ttsStateLock) {
                ttsReady = status == TextToSpeech.SUCCESS && languageResult >= TextToSpeech.LANG_AVAILABLE
                ttsInitializationComplete = true
                pendingAnnouncements.toList().also { pendingAnnouncements.clear() }
            }
            StructuredLogger.info(
                event = "tts_initialized",
                fields = mapOf(
                    "status" to status,
                    "languageResult" to languageResult,
                    "ready" to ttsReady,
                ),
            )
            pending.forEach(::deliverAnnouncement)
        }
    }

    fun announceStartup(battery: DeviceBatteryStatus) {
        playFallbackTone()
        announce(startupBatteryPrompt(battery.present, battery.percent))
    }

    fun announceNetwork(validated: Boolean) {
        announce(if (validated) "组网成功" else "组网失败")
    }

    fun announceLowBattery(threshold: Int) {
        require(threshold in setOf(20, 10, 5))
        announce("电量低，请充电")
    }

    fun announceShutdown() {
        announce("设备已关机")
    }

    fun announceAlarmUploaded() {
        announce("报警信息已上传")
    }

    fun handleKey(input: SimulatedInput) {
        when (input) {
            SimulatedInput.VOLUME_UP,
            SimulatedInput.VOLUME_DOWN,
            -> announceCallVolume()
            SimulatedInput.PHOTO_SHORT,
            SimulatedInput.RECORD_LONG,
            SimulatedInput.CALL,
            SimulatedInput.SOS,
            SimulatedInput.FALL,
            SimulatedInput.NEAR_ELECTRIC,
            SimulatedInput.HEIGHT_LIMIT,
            -> Unit
        }
    }

    fun announceAlarm(alarmType: String, simulated: Boolean): LocalAlarmFeedbackResult {
        val vibrationRequested = !simulated
        val vibrationStarted = vibrationRequested && vibrateAlarm()
        val prompt = safetyAlarmPrompt(alarmType, simulated)
        announce(prompt)
        StructuredLogger.info(
            event = "local_alarm_feedback_requested",
            fields = mapOf(
                "alarmType" to alarmType,
                "simulated" to simulated,
                "vibrationRequested" to vibrationRequested,
                "vibrationStarted" to vibrationStarted,
                "vibrationUnavailable" to (vibrationRequested && !vibrationStarted),
            ),
        )
        return LocalAlarmFeedbackResult(prompt, vibrationRequested, vibrationStarted)
    }

    fun announceMediaResult(kind: String, success: Boolean) {
        val action = when (kind) {
            "PHOTO" -> "已拍摄"
            "VIDEO_START" -> "视频录制已开启"
            "VIDEO_STOP" -> "视频录制已结束"
            else -> "媒体操作"
        }
        announce(if (success) action else "$action 失败")
    }

    suspend fun announceCallState(state: CallState): CallPromptResult {
        val result = callStatePrompt.play(state)
        StructuredLogger.info(
            event = "call_state_prompt_result",
            fields = mapOf(
                "state" to state.name,
                "message" to result.message,
                "ttsCompleted" to result.ttsCompleted,
                "fallbackToneStarted" to result.fallbackToneStarted,
                "error" to result.error,
            ),
        )
        return result
    }

    fun announceLocalIntercomState(state: LocalIntercomState) {
        val message = when (state) {
            LocalIntercomState.JOINING -> "正在连接本地对讲"
            LocalIntercomState.READY -> "本地对讲已就绪"
            LocalIntercomState.REQUESTING_TRANSMIT -> "正在开启本地讲话"
            LocalIntercomState.TRANSMITTING -> "本地讲话已开启"
            LocalIntercomState.RECEIVING -> "正在接收本地对讲"
            LocalIntercomState.REQUESTING_STOP -> "正在结束本地讲话"
            LocalIntercomState.FAULT -> "本地对讲故障"
            LocalIntercomState.UNAVAILABLE -> "本地对讲不可用"
            LocalIntercomState.DISABLED -> return
        }
        announce(message)
    }

    fun planCallVolumeTarget(input: SimulatedInput): Int {
        val minimum = audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        return requireNotNull(planAbsoluteCallVolume(input, current, minimum, maximum)) {
            "input is not a call volume key"
        }
    }

    fun setCallVolume(target: Int) {
        val minimum = audioManager.getStreamMinVolume(AudioManager.STREAM_VOICE_CALL)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
        require(target in minimum..maximum) { "planned call volume is outside the current stream range" }
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
        val applied = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        StructuredLogger.info(
            event = "call_volume_target_applied",
            fields = mapOf("target" to target, "applied" to applied, "maximum" to maximum),
        )
    }

    fun close() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        callTextPlayback.close()
        toneGenerator?.release()
        synchronized(ttsStateLock) {
            pendingAnnouncements.clear()
            ttsReady = false
            ttsInitializationComplete = true
        }
    }

    private fun announceCallVolume() {
        val current = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
        playFallbackTone()
        announce("音量 $current")
    }

    private fun announce(message: String) {
        val queued = synchronized(ttsStateLock) {
            if (!ttsInitializationComplete) {
                if (pendingAnnouncements.size >= MAX_PENDING_ANNOUNCEMENTS) pendingAnnouncements.removeFirst()
                pendingAnnouncements.addLast(message)
                true
            } else {
                false
            }
        }
        if (queued) {
            StructuredLogger.info(event = "local_prompt_queued", fields = mapOf("message" to message))
            return
        }
        deliverAnnouncement(message)
    }

    private fun deliverAnnouncement(message: String) {
        val utteranceId = "helmet-${System.nanoTime()}"
        val ready = synchronized(ttsStateLock) { ttsReady }
        val speechResult = if (ready) {
            textToSpeech?.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId)
        } else {
            TextToSpeech.ERROR
        }
        val fallbackUsed = speechResult == null || speechResult == TextToSpeech.ERROR
        if (fallbackUsed) playFallbackTone()
        StructuredLogger.info(
            event = "local_prompt_requested",
            fields = mapOf(
                "message" to message,
                "ttsReady" to ready,
                "fallbackTone" to fallbackUsed,
            ),
        )
    }

    private companion object {
        const val MAX_PENDING_ANNOUNCEMENTS = 16
    }

    private fun playFallbackTone(): Boolean {
        val started = runCatching {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) == true
        }.getOrElse { error ->
            StructuredLogger.error(event = "fallback_tone_failed", error = error)
            false
        }
        if (!started) {
            StructuredLogger.info(event = "fallback_tone_unavailable")
        }
        return started
    }

    private fun vibrateAlarm(): Boolean {
        if (!vibrator.hasVibrator()) return false
        return runCatching {
            vibrator.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 180, 100, 180),
                    -1,
                ),
            )
            true
        }.getOrElse { error ->
            StructuredLogger.error(event = "local_alarm_vibration_failed", error = error)
            false
        }
    }
}
