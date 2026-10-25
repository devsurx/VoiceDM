package com.voicedm.app.service.speech

/** Why listening produced nothing. */
enum class ListenFailure {
    /** Nothing was said, or the mic heard silence. */
    NO_SPEECH,

    /** The recogniser or its language data is unavailable on this device. */
    UNAVAILABLE,

    /** We gave up waiting. */
    TIMEOUT,

    /** Permission missing or another recogniser error. */
    ERROR,
}

sealed interface ListenResult {
    data class Heard(val text: String) : ListenResult
    data class Failed(val reason: ListenFailure) : ListenResult
}

/** Speech-to-text port used for Mode A replies. */
interface SpeechToText {
    /**
     * Starts a recognition session and suspends until it produces text or fails.
     * Cancelling the calling coroutine cancels the session.
     */
    suspend fun listen(languageTag: String, preferOffline: Boolean, timeoutMs: Long): ListenResult

    fun shutdown()
}
