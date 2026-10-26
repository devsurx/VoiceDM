package com.voicedm.app.assistant.wakeword

import kotlin.math.max

/**
 * Decides when a stream of per-frame keyword scores becomes a *wake-up*.
 *
 * Pure and clock-injected so the smoothing/patience/cooldown rules are unit-testable
 * without audio hardware. Rules, in order:
 *
 *  1. Peak-hold smoothing: the score decays slowly instead of dropping frame-to-frame,
 *     which bridges the brief dips inside a spoken phrase.
 *  2. No triggers while the energy VAD says nobody is talking - a stale high score from
 *     before a pause can never fire on silence.
 *  3. The smoothed score must stay above the threshold for [patienceFrames] consecutive
 *     frames (a single spiking frame is not a phrase).
 *  4. A [cooldownMs] dead time after every trigger stops one utterance firing twice.
 */
class WakeTriggerPolicy(
    /** Score threshold in 0..1; build from sensitivity via [thresholdFor]. */
    var threshold: Float = thresholdFor(DEFAULT_SENSITIVITY),
    /** Consecutive frames above threshold required to fire. */
    val patienceFrames: Int = 2,
    /** Per-frame decay applied to the smoothed score when the raw score is lower. */
    val smoothDecay: Float = 0.85f,
    /** Dead time after a trigger. */
    val cooldownMs: Long = 2_000L,
) {

    /** Peak-hold smoothed score, exposed for the debug screen. */
    var smoothed: Float = 0f
        private set

    private var aboveCount = 0
    private var notBeforeMs = Long.MIN_VALUE

    /** Returns true exactly once per detected wake-up. */
    fun onFrame(rawScore: Float, speaking: Boolean, nowMs: Long): Boolean {
        smoothed = max(rawScore, smoothed * smoothDecay)

        if (!speaking || nowMs < notBeforeMs) {
            aboveCount = 0
            return false
        }
        if (smoothed >= threshold) {
            aboveCount++
        } else {
            aboveCount = 0
        }
        if (aboveCount >= patienceFrames) {
            aboveCount = 0
            smoothed = 0f
            notBeforeMs = nowMs + cooldownMs
            return true
        }
        return false
    }

    /** Fresh run (test button / phrase switch). */
    fun reset() {
        smoothed = 0f
        aboveCount = 0
        notBeforeMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_SENSITIVITY = 0.5f

        /**
         * Maps the settings slider (0 = hard to trigger, 1 = hair-trigger) to a score
         * threshold. 0.5 sensitivity == 0.53, right at openWakeWord's documented default.
         */
        fun thresholdFor(sensitivity: Float): Float {
            val s = sensitivity.coerceIn(0f, 1f)
            return 0.75f - s * 0.45f
        }
    }
}
