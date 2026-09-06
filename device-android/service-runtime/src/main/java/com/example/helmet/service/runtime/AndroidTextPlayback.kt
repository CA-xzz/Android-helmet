package com.example.helmet.service.runtime

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

data class TextPlaybackResult(
    val success: Boolean,
    val error: String? = null,
)

fun interface TextPlayback {
    suspend fun play(text: String, language: String, utteranceId: String): TextPlaybackResult
}

class AndroidTextPlayback(context: Context) : TextPlayback, AutoCloseable {
    private val completions = ConcurrentHashMap<String, CompletableDeferred<TextPlaybackResult>>()
    private val ready = CompletableDeferred<Boolean>()
    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val initialized = status == TextToSpeech.SUCCESS && engine != null
            if (initialized) {
                engine?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String) = Unit
                        override fun onDone(utteranceId: String) {
                            completions.remove(utteranceId)?.complete(TextPlaybackResult(true))
                        }

                        @Deprecated("Deprecated by Android")
                        override fun onError(utteranceId: String) {
                            completeError(utteranceId, "TTS_PLAYBACK_ERROR")
                        }

                        override fun onError(utteranceId: String, errorCode: Int) {
                            completeError(utteranceId, "TTS_PLAYBACK_ERROR_$errorCode")
                        }
                    },
                )
            }
            if (!ready.isCompleted) ready.complete(initialized)
        }
    }

    override suspend fun play(text: String, language: String, utteranceId: String): TextPlaybackResult {
        if (withTimeoutOrNull(INIT_TIMEOUT_MILLIS) { ready.await() } != true) {
            return TextPlaybackResult(false, "TTS_UNAVAILABLE")
        }
        val tts = engine ?: return TextPlaybackResult(false, "TTS_UNAVAILABLE")
        val languageResult = tts.setLanguage(Locale.forLanguageTag(language))
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            return TextPlaybackResult(false, "TTS_LANGUAGE_UNAVAILABLE")
        }
        val completion = CompletableDeferred<TextPlaybackResult>()
        completions[utteranceId] = completion
        val result = tts.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId)
        if (result == TextToSpeech.ERROR) {
            completions.remove(utteranceId)
            return TextPlaybackResult(false, "TTS_SPEAK_REJECTED")
        }
        return withTimeoutOrNull(PLAYBACK_TIMEOUT_MILLIS) { completion.await() }
            ?: TextPlaybackResult(false, "TTS_PLAYBACK_TIMEOUT").also {
                completions.remove(utteranceId)
                tts.stop()
            }
    }

    override fun close() {
        completions.values.forEach { it.cancel() }
        completions.clear()
        engine?.stop()
        engine?.shutdown()
        engine = null
        if (!ready.isCompleted) ready.complete(false)
    }

    private fun completeError(utteranceId: String, error: String) {
        completions.remove(utteranceId)?.complete(TextPlaybackResult(false, error))
    }

    companion object {
        private const val INIT_TIMEOUT_MILLIS = 5_000L
        private const val PLAYBACK_TIMEOUT_MILLIS = 120_000L
    }
}
