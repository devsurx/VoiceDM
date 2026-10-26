package com.voicedm.app.service.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/** Short UI tones so you always know what the loop is doing without looking at the phone. */
enum class Beep { LISTENING, SENT, FAILED, CONFIRM, WAKE, UNDERSTOOD }

/**
 * Generates its tones instead of shipping audio assets: no resources to load, works before
 * TTS is warm, and plays on the media stream so it follows the same routing as the speech.
 */
class BeepPlayer {

    @Volatile
    private var lastPlayedAtMs = 0L

    fun play(beep: Beep, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPlayedAtMs < MIN_GAP_MS) return
        lastPlayedAtMs = now

        val samples = when (beep) {
            Beep.LISTENING -> tone(880, 140) + tone(1180, 90)
            Beep.SENT -> tone(1180, 90) + tone(1560, 110)
            Beep.FAILED -> tone(320, 260)
            Beep.CONFIRM -> tone(740, 80) + tone(740, 80, gapMs = 80)
            // Assistant: rising "you have my attention" chime on wake...
            Beep.WAKE -> tone(990, 110) + tone(1320, 90) + tone(1760, 140)
            // ...and a clearly different, shorter ack when a command was understood.
            Beep.UNDERSTOOD -> tone(1560, 70) + tone(2080, 80)
        }
        play(samples)
    }

    private fun play(samples: ShortArray) {
        if (samples.isEmpty()) return
        runCatching {
            val sampleRate = SAMPLE_RATE
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            ).coerceAtLeast(samples.size * 2)

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            var released = false
            fun release() {
                if (released) return
                released = true
                runCatching { track.stop() }
                runCatching { track.release() }
            }

            track.setNotificationMarkerPosition(samples.size - 1)
            track.setPlaybackPositionUpdateListener(
                object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) = release()
                    override fun onPeriodicNotification(track: AudioTrack?) = Unit
                },
            )
            track.write(samples, 0, samples.size)
            track.play()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(::release, samples.size * 1000L / SAMPLE_RATE + 300L)
        }
    }

    private fun tone(
        frequencyHz: Int,
        durationMs: Int,
        volume: Double = 0.22,
        gapMs: Int = 0,
    ): ShortArray {
        val samples = ShortArray(SAMPLE_RATE * durationMs / 1000)
        for (i in samples.indices) {
            val time = i.toDouble() / SAMPLE_RATE
            samples[i] = (Short.MAX_VALUE * volume * sin(2.0 * PI * frequencyHz * time)).toInt().toShort()
        }
        if (gapMs <= 0) return samples
        val gap = ShortArray(SAMPLE_RATE * gapMs / 1000)
        return samples + gap
    }

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val MIN_GAP_MS = 120L
    }
}
