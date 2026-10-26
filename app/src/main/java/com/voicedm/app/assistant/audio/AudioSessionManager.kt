package com.voicedm.app.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.voicedm.app.assistant.wakeword.WakeWordEngine

/**
 * The single owner of the microphone.
 *
 * One process, one [AudioRecord]: the assistant service opens it here and the state
 * machine decides for how long. Nothing else in the app may create a competing recorder
 * while this holds the mic - Stage 3 of the assistant integration routes the conversation
 * loop's recording through this same owner. Audio *focus* for playback stays with the
 * existing [com.voicedm.app.service.audio.AudioFocusController]; this class only claims
 * focus-relevant states via [AssistantStateMachine] so a future TTS step can request it.
 *
 * The microphone is released whenever the machine enters PAUSED or STOPPED, which is also
 * what makes "pause" honest: another app can take the mic immediately.
 */
class AudioSessionManager {

    /** The transition table driving everything; pure and unit-tested. */
    val machine = AssistantStateMachine()

    private var record: AudioRecord? = null

    /** Last microphone failure, surfaced on the debug panel. */
    var micError: String? = null
        private set

    val micHeld: Boolean get() = record != null

    /** Opens and starts a 16 kHz mono PCM recorder. Returns false (with [micError]) on failure. */
    fun openMic(): Boolean {
        if (record != null) return true
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
        if (minBuf <= 0) {
            micError = "16 kHz mono PCM unsupported on this device"
            return false
        }
        val size = maxOf(minBuf, WakeWordEngine.STEP_SAMPLES * 2 * 4)
        val built = runCatching {
            AudioRecord.Builder()
                // VOICE_RECOGNITION avoids the telephony AGC/noise suppressor so the
                // wake model sees the room the way it was trained.
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(size)
                .build()
        }.getOrElse {
            micError = it.message ?: "AudioRecord build failed"
            Log.w(TAG, "openMic failed", it)
            return false
        }

        if (built.state != AudioRecord.STATE_INITIALIZED) {
            built.release()
            micError = "AudioRecord failed to initialise"
            return false
        }
        runCatching { built.startRecording() }
        record = built
        micError = null
        return true
    }

    /**
     * Blocking read of exactly one detector step ([WakeWordEngine.STEP_SAMPLES] samples).
     * Returns the number of samples read; <= 0 means the recorder is gone or errored.
     */
    fun readStep(dst: ShortArray): Int {
        val r = record ?: return -1
        var offset = 0
        while (offset < dst.size) {
            val n = r.read(dst, offset, dst.size - offset)
            if (n <= 0) return if (offset > 0) offset else n
            offset += n
        }
        return offset
    }

    fun releaseMic() {
        val r = record ?: return
        record = null
        runCatching { r.stop() }
        runCatching { r.release() }
    }

    /** Push an event through the state machine and apply mic side effects. */
    fun dispatch(event: AssistantEvent): AssistantTransition {
        val transition = machine.handle(event)
        if (!machine.micHeld) releaseMic()
        return transition
    }

    private companion object {
        const val TAG = "AudioSession"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL_IN_MONO = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING_PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT
    }
}
