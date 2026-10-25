package com.voicedm.app.service.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/** What happened to audio focus, so the engine can pause instead of talking over someone. */
sealed interface FocusEvent {
    data object Gained : FocusEvent
    data object LostTransient : FocusEvent
    data object LostPermanent : FocusEvent
}

/**
 * Holds audio focus for the duration of one conversation turn.
 *
 * Speech goes out on the media stream (speaker, Bluetooth headset or wired headset, whatever
 * the system has picked) and we ask for a transient focus so music ducks/stops while we talk
 * and comes back when the turn is over.
 */
class AudioFocusController(
    context: Context,
    private val onEvent: (FocusEvent) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val listener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onEvent(FocusEvent.Gained)
            AudioManager.AUDIOFOCUS_LOSS -> onEvent(FocusEvent.LostPermanent)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> onEvent(FocusEvent.LostTransient)
            else -> Unit
        }
    }

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener(listener)
        .build()

    private var held = false

    fun request(): Boolean {
        if (held) return true
        val result = audioManager.requestAudioFocus(request)
        held = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    fun abandon() {
        if (!held) return
        runCatching { audioManager.abandonAudioFocusRequest(request) }
        held = false
    }
}
