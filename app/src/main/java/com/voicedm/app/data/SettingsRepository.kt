package com.voicedm.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voicedm.app.domain.model.AppSettings
import com.voicedm.app.domain.model.ReplyMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "voicedm_settings")

/**
 * Single source of truth for user configuration, backed by Preferences DataStore.
 *
 * Everything (UI, notification listener, conversation engine) observes the same flow so a
 * change made in Settings takes effect on the very next message.
 */
class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(current())
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.TARGET_CHAT] = next.targetChatName
            prefs[Keys.NICKNAME] = next.contactNickname
            prefs[Keys.REPLY_MODE] = next.replyMode.name
            prefs[Keys.CONFIRM_SEND] = next.confirmBeforeSend
            prefs[Keys.AUTO_LISTEN] = next.autoListenAfterRead
            prefs[Keys.HEADSET_TRIGGER] = next.headsetButtonTrigger
            prefs[Keys.TTS_RATE] = next.ttsRate
            prefs[Keys.TTS_LANGUAGE] = next.ttsLanguageTag
            prefs[Keys.TTS_VOICE] = next.ttsVoiceName
            prefs[Keys.ASR_LANGUAGE] = next.recognitionLanguageTag
            prefs[Keys.OFFLINE_ASR] = next.offlineRecognition
            prefs[Keys.ANNOUNCE_SENDER] = next.announceSender
            prefs[Keys.RETRY_NO_SPEECH] = next.retryOnNoSpeech
            prefs[Keys.CATCH_UP] = next.catchUpOnStart
            prefs[Keys.VOICE_HOLD] = next.voiceNoteHoldSeconds
            prefs[Keys.SOLO_YAP] = next.soloYapMode
            prefs[Keys.TARGET_THREAD] = next.targetThreadId
            prefs[Keys.ASSISTANT_ENABLED] = next.assistantEnabled
            prefs[Keys.ASSISTANT_PHRASE] = next.assistantPhraseId
            prefs[Keys.ASSISTANT_SENSITIVITY] = next.assistantSensitivity
            prefs[Keys.ASSISTANT_WAKES] = next.assistantFalseTriggers
        }
    }
}

private object Keys {
    val TARGET_CHAT = stringPreferencesKey("target_chat")
    val NICKNAME = stringPreferencesKey("contact_nickname")
    val REPLY_MODE = stringPreferencesKey("reply_mode")
    val CONFIRM_SEND = booleanPreferencesKey("confirm_send")
    val AUTO_LISTEN = booleanPreferencesKey("auto_listen")
    val HEADSET_TRIGGER = booleanPreferencesKey("headset_trigger")
    val TTS_RATE = floatPreferencesKey("tts_rate")
    val TTS_LANGUAGE = stringPreferencesKey("tts_language")
    val TTS_VOICE = stringPreferencesKey("tts_voice")
    val ASR_LANGUAGE = stringPreferencesKey("asr_language")
    val OFFLINE_ASR = booleanPreferencesKey("offline_asr")
    val ANNOUNCE_SENDER = booleanPreferencesKey("announce_sender")
    val RETRY_NO_SPEECH = booleanPreferencesKey("retry_no_speech")
    val CATCH_UP = booleanPreferencesKey("catch_up")
    val VOICE_HOLD = intPreferencesKey("voice_hold")
    val SOLO_YAP = booleanPreferencesKey("solo_yap")
    val TARGET_THREAD = stringPreferencesKey("target_thread")
    val ASSISTANT_ENABLED = booleanPreferencesKey("assistant_enabled")
    val ASSISTANT_PHRASE = stringPreferencesKey("assistant_phrase")
    val ASSISTANT_SENSITIVITY = floatPreferencesKey("assistant_sensitivity")
    val ASSISTANT_WAKES = intPreferencesKey("assistant_wakes")
}

private fun Preferences.toSettings(): AppSettings = AppSettings(
    targetChatName = this[Keys.TARGET_CHAT] ?: "",
    contactNickname = this[Keys.NICKNAME] ?: "",
    replyMode = runCatching { ReplyMode.valueOf(this[Keys.REPLY_MODE] ?: "") }
        .getOrDefault(ReplyMode.INLINE_TEXT),
    confirmBeforeSend = this[Keys.CONFIRM_SEND] ?: false,
    autoListenAfterRead = this[Keys.AUTO_LISTEN] ?: true,
    headsetButtonTrigger = this[Keys.HEADSET_TRIGGER] ?: true,
    ttsRate = this[Keys.TTS_RATE] ?: 1.0f,
    ttsLanguageTag = this[Keys.TTS_LANGUAGE] ?: "en-US",
    ttsVoiceName = this[Keys.TTS_VOICE] ?: "",
    recognitionLanguageTag = this[Keys.ASR_LANGUAGE] ?: "en-US",
    offlineRecognition = this[Keys.OFFLINE_ASR] ?: true,
    announceSender = this[Keys.ANNOUNCE_SENDER] ?: false,
    retryOnNoSpeech = this[Keys.RETRY_NO_SPEECH] ?: true,
    catchUpOnStart = this[Keys.CATCH_UP] ?: true,
    voiceNoteHoldSeconds = (this[Keys.VOICE_HOLD] ?: AppSettings.DEFAULT_VOICE_NOTE_HOLD_SECONDS)
        .coerceIn(3, 30),
    soloYapMode = this[Keys.SOLO_YAP] ?: false,
    targetThreadId = this[Keys.TARGET_THREAD] ?: "",
    assistantEnabled = this[Keys.ASSISTANT_ENABLED] ?: false,
    assistantPhraseId = this[Keys.ASSISTANT_PHRASE] ?: AppSettings().assistantPhraseId,
    assistantSensitivity = (this[Keys.ASSISTANT_SENSITIVITY] ?: 0.5f).coerceIn(0f, 1f),
    assistantFalseTriggers = this[Keys.ASSISTANT_WAKES] ?: 0,
)
