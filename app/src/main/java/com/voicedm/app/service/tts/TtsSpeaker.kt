package com.voicedm.app.service.tts

/** Outcome of a single speak call. */
sealed interface SpeakResult {
    data object Done : SpeakResult

    /** Stopped on purpose: skip button, focus loss or service teardown. */
    data object Stopped : SpeakResult

    data object Error : SpeakResult
}

/** Speech synthesis port; the engine never sees the Android API directly. */
interface TtsSpeaker {
    /** Applies rate/language/voice. Returns false when no TTS engine is usable. */
    suspend fun prepare(rate: Float, languageTag: String, voiceName: String): Boolean

    /** Speaks [text] and suspends until it finishes, is stopped, or fails. */
    suspend fun speak(text: String): SpeakResult

    fun stop()

    fun shutdown()
}

/** A voice offered by the installed TTS engine. */
data class TtsVoice(val name: String, val localeTag: String, val quality: Int, val latency: Int)
