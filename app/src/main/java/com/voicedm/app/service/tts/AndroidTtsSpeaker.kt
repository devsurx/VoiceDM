package com.voicedm.app.service.tts

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * [TextToSpeech] behind the [TtsSpeaker] port.
 *
 * Two quirks are handled here because they would otherwise hang the conversation loop:
 *  - `TextToSpeech.stop()` does not reliably deliver a callback, so pending calls are
 *    completed manually;
 *  - engine callbacks arrive on the main thread, so stopping is marshalled there too.
 */
class AndroidTtsSpeaker(context: Context) : TtsSpeaker {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ConcurrentHashMap<String, (SpeakResult) -> Unit>()

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var initSignal: CompletableDeferred<Boolean>? = null

    private fun engine(): TextToSpeech? {
        tts?.let { return it }
        synchronized(this) {
            tts?.let { return it }
            val signal = CompletableDeferred<Boolean>()
            initSignal = signal
            val created = TextToSpeech(appContext) { status ->
                val ok = status == TextToSpeech.SUCCESS
                if (!ok) Log.w(TAG, "TTS init failed with status $status")
                signal.complete(ok)
            }
            created.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let { complete(it, SpeakResult.Done) }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    utteranceId?.let { complete(it, SpeakResult.Error) }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    utteranceId?.let { complete(it, SpeakResult.Error) }
                }
            })
            tts = created
            return created
        }
    }

    private fun complete(utteranceId: String, result: SpeakResult) {
        pending.remove(utteranceId)?.invoke(result)
    }

    override suspend fun prepare(rate: Float, languageTag: String, voiceName: String): Boolean {
        val created = engine() ?: return false
        if (initSignal?.await() != true) return false

        val locale = Locale.forLanguageTag(languageTag)
        if (locale.language.isNotEmpty()) {
            val code = created.setLanguage(locale)
            if (code == TextToSpeech.LANG_MISSING_DATA || code == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "Language $languageTag not usable (code $code), keeping engine default")
            }
        }

        if (voiceName.isNotBlank()) {
            val voice = pickVoice(created.voices, voiceName, languageTag)
            if (voice == null) Log.w(TAG, "Voice $voiceName unavailable, keeping engine default")
            else created.voice = voice
        }

        created.setSpeechRate(rate.coerceIn(MIN_RATE, MAX_RATE))
        return true
    }

    override suspend fun speak(text: String): SpeakResult {
        val created = engine() ?: return SpeakResult.Error
        if (initSignal?.await() != true) return SpeakResult.Error
        if (text.isBlank()) return SpeakResult.Done

        return suspendCancellableCoroutine { continuation ->
            val utteranceId = UUID.randomUUID().toString()
            pending[utteranceId] = { result ->
                if (!continuation.isCompleted) continuation.resume(result)
            }
            val outcome = created.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (outcome != TextToSpeech.SUCCESS) {
                pending.remove(utteranceId)
                if (!continuation.isCompleted) continuation.resume(SpeakResult.Error)
            }
        }
    }

    override fun stop() {
        mainHandler.post {
            tts?.stop()
            pending.keys.toList().forEach { complete(it, SpeakResult.Stopped) }
        }
    }

    override fun shutdown() {
        mainHandler.post {
            pending.keys.toList().forEach { complete(it, SpeakResult.Stopped) }
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }

    private fun pickVoice(voices: Set<Voice>?, name: String, languageTag: String): Voice? {
        if (voices.isNullOrEmpty()) return null
        return voices.firstOrNull { it.name == name }
            ?: voices.firstOrNull {
                !it.features.contains(FEATURE_NOT_IGNORE) ||
                    it.locale.toLanguageTag().equals(languageTag, ignoreCase = true)
            }
    }

    companion object {
        private const val TAG = "VoiceDM"
        private const val MIN_RATE = 0.5f
        private const val MAX_RATE = 2.5f
        private const val FEATURE_NOT_IGNORE = "notIgnore"
    }
}
