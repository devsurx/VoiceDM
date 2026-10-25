package com.voicedm.app.domain.model

/**
 * Everything the user can configure. Mirrored into DataStore by
 * [com.voicedm.app.data.SettingsRepository] as a single flow so the UI and the
 * services always observe the same snapshot.
 */
data class AppSettings(
    /** Display name of the one Instagram chat we act on, exactly as notifications show it. */
    val targetChatName: String = "",
    /** Reply delivery mechanism. */
    val replyMode: ReplyMode = ReplyMode.INLINE_TEXT,
    /** Speak the transcript and wait for an explicit Send before delivering it. */
    val confirmBeforeSend: Boolean = false,
    /** Start listening right after the message has been read. */
    val autoListenAfterRead: Boolean = true,
    /** Honour the headset play/pause hook button (and media keys) as a trigger. */
    val headsetButtonTrigger: Boolean = true,
    /** TTS speed, 1.0 == normal. */
    val ttsRate: Float = 1.0f,
    /** BCP-47 tag used for speech synthesis, e.g. "en-US". */
    val ttsLanguageTag: String = "en-US",
    /** TTS voice name, "" means "whatever the engine picks for the language". */
    val ttsVoiceName: String = "",
    /** BCP-47 tag used for speech recognition, e.g. "en-US". */
    val recognitionLanguageTag: String = "en-US",
    /** Prefer an on-device recogniser when the phone offers one. */
    val offlineRecognition: Boolean = true,
    /** Speak "<name> says" before the message body. */
    val announceSender: Boolean = false,
    /** One retry when nothing was recognised. */
    val retryOnNoSpeech: Boolean = true,
    /** Read messages that arrived while the engine was off (up to a short window). */
    val catchUpOnStart: Boolean = true,
    /** How long Mode B holds Instagram's record button, in seconds. */
    val voiceNoteHoldSeconds: Int = DEFAULT_VOICE_NOTE_HOLD_SECONDS,
) {
    companion object {
        const val DEFAULT_VOICE_NOTE_HOLD_SECONDS = 8
    }
}

/** Which onboarding step the user is on; steps are shown in this order. */
enum class OnboardingStep { NOTIFICATIONS, MICROPHONE, ACCESSIBILITY, CONTACT }
