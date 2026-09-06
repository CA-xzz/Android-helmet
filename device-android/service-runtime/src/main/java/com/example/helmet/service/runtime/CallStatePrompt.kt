package com.example.helmet.service.runtime

import com.example.helmet.core.model.CallState
import kotlinx.coroutines.CancellationException

data class CallPromptResult(
    val message: String,
    val ttsCompleted: Boolean,
    val fallbackToneStarted: Boolean,
    val error: String?,
)

class CallStatePrompt(
    private val playback: TextPlayback,
    private val fallbackTone: () -> Boolean,
) {
    suspend fun play(state: CallState): CallPromptResult {
        val message = message(state)
        val result = try {
            playback.play(message, LANGUAGE, "call-${state.name.lowercase()}-${System.nanoTime()}")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            TextPlaybackResult(false, "TTS_EXCEPTION_${error.javaClass.simpleName}".take(128))
        }
        if (result.success) return CallPromptResult(message, true, false, null)
        return CallPromptResult(
            message = message,
            ttsCompleted = false,
            fallbackToneStarted = fallbackTone(),
            error = result.error ?: "TTS_PLAYBACK_FAILED",
        )
    }

    companion object {
        private const val LANGUAGE = "zh-CN"

        fun message(state: CallState): String = when (state) {
            CallState.REQUESTED -> "正在呼叫"
            CallState.RINGING -> "等待接听"
            CallState.ACCEPTED -> "呼叫已接听"
            CallState.CONNECTING -> "正在连接通话"
            CallState.CONNECTED -> "视频通话已接通"
            CallState.REJECTED -> "呼叫已拒绝"
            CallState.ENDED -> "视频通话已挂断"
            CallState.FAILED -> "呼叫失败"
        }
    }
}
