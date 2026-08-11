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

class LocalFeedbackController(context: Context) {
    private val applicationContext = context.applicationContext
    private val audioManager = applicationContext.getSystemService(AudioManager::class.java)
    private val vibrator = applicationContext.getSystemService(VibratorManager::class.java).defaultVibrator
    private val toneGenerator = runCatching {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
    }.getOrNull()
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private val callTextPlayback = AndroidTextPlayback(applicationContext)
    private val callStatePrompt = CallStatePrompt(callTextPlayback, ::playFallbackTone)

    init {
        textToSpeech = TextToSpeech(applicationContext) { status ->
            val languageResult = if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.setLanguage(Locale.SIMPLIFIED_CHINESE) ?: TextToSpeech.ERROR
            } else {
                TextToSpeech.ERROR
            }
            ttsReady = status == TextToSpeech.SUCCESS && languageResult >= TextToSpeech.LANG_AVAILABLE
            StructuredLogger.info(
                event = "tts_initialized",
                fields = mapOf(
                    "status" to status,
                    "languageResult" to languageResult,
                    "ready" to ttsReady,
                ),
            )
        }
    }

    fun handleKey(input: SimulatedInput, simulated: Boolean) {
        val prefix = if (simulated) "模拟" else ""
        when (input) {
            SimulatedInput.PHOTO_SHORT -> announce("${prefix}拍照按键")
            SimulatedInput.RECORD_LONG -> announce("${prefix}录像按键")
            SimulatedInput.CALL -> announce("${prefix}呼叫按键")
            SimulatedInput.SOS -> announce("${prefix}紧急报警")
            SimulatedInput.VOLUME_UP -> adjustMusicVolume(AudioManager.ADJUST_RAISE)
            SimulatedInput.VOLUME_DOWN -> adjustMusicVolume(AudioManager.ADJUST_LOWER)
            SimulatedInput.FALL,
            SimulatedInput.NEAR_ELECTRIC,
            SimulatedInput.HEIGHT_LIMIT,
            -> Unit
        }
    }

    fun announceAlarm(alarmType: String, simulated: Boolean) {
        val prefix = if (simulated) "模拟" else ""
        val message = when (alarmType) {
            "FALL" -> "检测到${prefix}跌落报警"
            "NEAR_ELECTRIC" -> "检测到${prefix}近电报警"
            "HEIGHT_LIMIT" -> "检测到${prefix}高度报警"
            "GEOFENCE_EXIT" -> "检测到${prefix}电子围栏越界报警"
            else -> "检测到${prefix}报警"
        }
        val vibrationStarted = if (simulated) false else vibrateAlarm()
        announce(message)
        StructuredLogger.info(
            event = "local_alarm_feedback_requested",
            fields = mapOf(
                "alarmType" to alarmType,
                "simulated" to simulated,
                "vibrationStarted" to vibrationStarted,
            ),
        )
    }

    fun announceMediaResult(kind: String, success: Boolean) {
        val action = when (kind) {
            "PHOTO" -> "拍照"
            "VIDEO_START" -> "开始录像"
            "VIDEO_STOP" -> "录像已保存"
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

    fun close() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        callTextPlayback.close()
        toneGenerator?.release()
        ttsReady = false
    }

    private fun adjustMusicVolume(direction: Int) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        StructuredLogger.info(
            event = "music_volume_changed",
            fields = mapOf("current" to current, "maximum" to maximum),
        )
        announce("音量 $current")
    }

    private fun announce(message: String) {
        val utteranceId = "helmet-${System.nanoTime()}"
        val speechResult = if (ttsReady) {
            textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        } else {
            TextToSpeech.ERROR
        }
        val fallbackUsed = speechResult == null || speechResult == TextToSpeech.ERROR
        if (fallbackUsed) playFallbackTone()
        StructuredLogger.info(
            event = "local_prompt_requested",
            fields = mapOf(
                "message" to message,
                "ttsReady" to ttsReady,
                "fallbackTone" to fallbackUsed,
            ),
        )
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
