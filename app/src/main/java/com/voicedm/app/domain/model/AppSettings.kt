package com.voicedm.app.domain.model

/**
 * Everything the user can configure. Mirrored into DataStore by
 * [com.voicedm.app.data.SettingsRepository] as a single flow so the UI and the
 * services always observe the same snapshot.
 */
data class AppSettings(
    /** Display name of the one Instagram chat we act on, exactly as notifications show it. */
    val targetChatName: String = "",
    /**
     * Optional nickname shown instead of [targetChatName] everywhere the contact is
     * displayed (title, notification, widget, tile). Matching and replies always use the
     * real chat name - a nickname can never change who gets the reply.
     */
    val contactNickname: String = "",
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
    /**
     * Solo yap: a one-way monologue. Incoming messages are only logged - never read
     * aloud - and the engine records and sends voice notes back to back until paused.
     */
    val soloYapMode: Boolean = false,

    // --- voice assistant (Stage 1: wake word + foreground service) -------------------
    /** Assistant enabled: keep the wake-word service running. */
    val assistantEnabled: Boolean = false,
    /** Which bundled wake phrase to listen for - see WakePhrase.BUNDLED. */
    val assistantPhraseId: String = "hey_jarvis",
    /** 0 = hard to trigger, 1 = hair-trigger (maps to a score threshold). */
    val assistantSensitivity: Float = 0.5f,
    /** Wake-ups seen since the user last reset it - used to tune sensitivity. */
    val assistantFalseTriggers: Int = 0,
) {
    companion object {
        const val DEFAULT_VOICE_NOTE_HOLD_SECONDS = 8
    }
}

/**
 * How the contact should be *shown* to the user: the nickname when one is set,
 * otherwise the real chat name. Pure, so every display surface (title, notification,
 * widget, tile, TTS fallback) resolves it identically.
 */
fun AppSettings.displayContactName(): String =
    contactNickname.trim().ifBlank { targetChatName.trim() }

/** Which onboarding step the user is on; steps are shown in this order. */
enum class OnboardingStep { NOTIFICATIONS, MICROPHONE, ACCESSIBILITY, CONTACT }
