package com.example.helmet.service.runtime

import com.example.helmet.core.model.CallState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStatePromptTest {
    @Test
    fun everyCallStateHasAnExplicitChineseMessage() {
        assertEquals(
            listOf(
                "正在呼叫",
                "等待接听",
                "呼叫已接听",
                "正在连接通话",
                "视频通话已接通",
                "呼叫已拒绝",
                "视频通话已挂断",
                "呼叫失败",
            ),
            CallState.entries.map(CallStatePrompt::message),
        )
    }

    @Test
    fun completedSpeechDoesNotUseFallback() = runBlocking {
        var fallbackCalled = false
        val prompt = CallStatePrompt(
            playback = TextPlayback { _, _, _ -> TextPlaybackResult(true) },
            fallbackTone = { fallbackCalled = true; true },
        )

        val result = prompt.play(CallState.CONNECTED)

        assertTrue(result.ttsCompleted)
        assertFalse(result.fallbackToneStarted)
        assertFalse(fallbackCalled)
    }

    @Test
    fun failedSpeechRecordsErrorAndFallbackResult() = runBlocking {
        val prompt = CallStatePrompt(
            playback = TextPlayback { _, _, _ -> TextPlaybackResult(false, "TTS_UNAVAILABLE") },
            fallbackTone = { true },
        )

        val result = prompt.play(CallState.REJECTED)

        assertFalse(result.ttsCompleted)
        assertTrue(result.fallbackToneStarted)
        assertEquals("TTS_UNAVAILABLE", result.error)
    }

    @Test
    fun playbackExceptionBecomesAuditableFailure() = runBlocking {
        val prompt = CallStatePrompt(
            playback = TextPlayback { _, _, _ -> error("engine crashed") },
            fallbackTone = { false },
        )

        val result = prompt.play(CallState.FAILED)

        assertFalse(result.ttsCompleted)
        assertFalse(result.fallbackToneStarted)
        assertEquals("TTS_EXCEPTION_IllegalStateException", result.error)
    }
}
