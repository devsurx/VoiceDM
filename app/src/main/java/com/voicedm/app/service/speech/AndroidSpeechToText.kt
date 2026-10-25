package com.voicedm.app.service.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [SpeechRecognizer] behind the [SpeechToText] port.
 *
 * Everything runs on the main thread because that is what SpeechRecognizer requires; the
 * coroutine simply parks until a result or an error arrives. When the device cannot do
 * offline recognition for the requested language we retry once online.
 */
class AndroidSpeechToText(context: Context) : SpeechToText {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var pending: ((ListenResult) -> Unit)? = null

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) {
            Log.d(TAG, "recognition error $error")
            finish(ListenResult.Failed(mapError(error)))
        }

        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onResults(results: Bundle?) {
            val hypotheses = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val best = hypotheses?.firstOrNull { !it.isNullOrBlank() }?.trim()
            if (best.isNullOrEmpty()) finish(ListenResult.Failed(ListenFailure.NO_SPEECH))
            else finish(ListenResult.Heard(best))
        }
    }

    override suspend fun listen(
        languageTag: String,
        preferOffline: Boolean,
        timeoutMs: Long,
    ): ListenResult {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            return ListenResult.Failed(ListenFailure.UNAVAILABLE)
        }

        val started = System.currentTimeMillis()
        val first = attempt(languageTag, preferOffline, timeoutMs)
        if (first is ListenResult.Failed &&
            first.reason == ListenFailure.UNAVAILABLE &&
            preferOffline
        ) {
            val spent = System.currentTimeMillis() - started
            val remaining = (timeoutMs - spent).coerceAtLeast(MIN_RETRY_BUDGET_MS)
            return attempt(languageTag, preferOffline = false, remaining)
        }
        return first
    }

    private suspend fun attempt(
        languageTag: String,
        preferOffline: Boolean,
        timeoutMs: Long,
    ): ListenResult {
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<ListenResult> { continuation ->
                mainHandler.post {
                    pending = { outcome ->
                        if (continuation.isActive) continuation.resume(outcome)
                    }
                    val speech = ensureRecognizer()
                    try {
                        speech.startListening(buildIntent(languageTag, preferOffline))
                    } catch (t: Throwable) {
                        Log.w(TAG, "startListening failed", t)
                        finish(ListenResult.Failed(ListenFailure.ERROR))
                    }
                }
                continuation.invokeOnCancellation {
                    mainHandler.post { cancelSession() }
                }
            }
        }
        return result ?: ListenResult.Failed(ListenFailure.TIMEOUT)
    }

    private fun finish(result: ListenResult) {
        val callback = pending
        pending = null
        callback?.invoke(result)
    }

    private fun cancelSession() {
        pending = null
        runCatching { recognizer?.cancel() }
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        recognizer?.let { return it }
        val created = SpeechRecognizer.createSpeechRecognizer(appContext)
        created.setRecognitionListener(listener)
        recognizer = created
        return created
    }

    private fun buildIntent(languageTag: String, preferOffline: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    override fun shutdown() {
        mainHandler.post {
            pending = null
            runCatching { recognizer?.cancel() }
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
    }

    private fun mapError(code: Int): ListenFailure = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        -> ListenFailure.NO_SPEECH

        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT,
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS,
        -> ListenFailure.UNAVAILABLE

        else -> ListenFailure.ERROR
    }

    private companion object {
        const val TAG = "VoiceDM"
        const val MIN_RETRY_BUDGET_MS = 2_000L
    }
}
