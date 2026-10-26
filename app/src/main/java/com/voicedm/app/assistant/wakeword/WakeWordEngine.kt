package com.voicedm.app.assistant.wakeword

/**
 * A wake phrase the user can pick: the label shown in the UI plus the model file that
 * detects it. Bundled models are openWakeWord v0.5.1 release artifacts (Apache-2.0).
 *
 * Training a *custom* phrase ("Hey Echo") produces one more of these - drop the
 * `.tflite` into `assets/models/` and add a row here. See README, "Retraining the
 * wake phrase".
 */
data class WakePhrase(
    /** Stable id persisted in DataStore. */
    val id: String,
    /** What the user says, and what the UI shows. */
    val label: String,
    /** Asset path of the TFLite keyword-head model. */
    val asset: String,
) {
    companion object {
        const val MELSPEC_ASSET = "models/melspectrogram.tflite"
        const val EMBEDDING_ASSET = "models/embedding_model.tflite"

        /** All phrases bundled in the APK; ~4.5 MB of keyword heads on top of the shared pipeline. */
        val BUNDLED = listOf(
            WakePhrase("hey_jarvis", "Hey Jarvis", "models/hey_jarvis_v0.1.tflite"),
            WakePhrase("hey_mycroft", "Hey Mycroft", "models/hey_mycroft_v0.1.tflite"),
            WakePhrase("hey_rhasspy", "Hey Rhasspy", "models/hey_rhasspy_v0.1.tflite"),
            WakePhrase("alexa", "Alexa", "models/alexa_v0.1.tflite"),
        )

        const val DEFAULT_ID = "hey_jarvis"

        fun byId(id: String): WakePhrase = BUNDLED.firstOrNull { it.id == id } ?: BUNDLED.first()
    }
}

/** One 80 ms frame's worth of detector output. */
data class FrameResult(
    /** Keyword score in 0..1 (openWakeWord convention: ~0.5 = detection). */
    val score: Float,
    /** Whether the energy VAD considered this frame speech. */
    val speaking: Boolean,
    /** Wall time the model pipeline took for this frame, in milliseconds. */
    val inferenceMs: Float,
)

/**
 * Streaming keyword-spotting engine.
 *
 * Implementations consume 16 kHz mono PCM in 1280-sample (80 ms) frames and are free to
 * keep internal buffers. The interface exists so the engine (openWakeWord today) can be
 * swapped for Porcupine/Sherpa later without touching the service - see README,
 * "Why openWakeWord".
 */
interface WakeWordEngine : AutoCloseable {

    /** The phrase this instance detects. */
    val phrase: WakePhrase

    /** Feed exactly one frame ([STEP_SAMPLES] samples) and get back this frame's score. */
    fun processFrame(frame: ShortArray): FrameResult

    /**
     * Tell the engine that [frames] frames of audio were dropped (the service pauses the
     * detector while TTS speaks or a command is recognised). On the next speech frame the
     * engine rebuilds the feature tail so recognition starts from a consistent state.
     */
    fun markGap(frames: Int)

    /** Drop all buffers (used by the on-device test button to start from a clean slate). */
    fun reset()

    companion object {
        /** Samples per frame: 80 ms at 16 kHz. The whole pipeline is anchored to this. */
        const val STEP_SAMPLES = 1280
    }
}
