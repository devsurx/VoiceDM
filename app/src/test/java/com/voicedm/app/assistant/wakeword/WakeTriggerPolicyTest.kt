package com.voicedm.app.assistant.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trigger rules: peak-hold smoothing, patience (consecutive frames), VAD gating, cooldown,
 * and the sensitivity -> threshold mapping that backs the settings slider.
 */
class WakeTriggerPolicyTest {

    private val t0 = 1_000_000L

    @Test
    fun `sensitivity maps to threshold from hard to hair-trigger`() {
        assertEquals(0.75f, WakeTriggerPolicy.thresholdFor(0f), 0.001f)
        assertEquals(0.30f, WakeTriggerPolicy.thresholdFor(1f), 0.001f)
        // Default sensitivity sits right at openWakeWord's documented ~0.5 working point.
        assertEquals(0.525f, WakeTriggerPolicy.thresholdFor(0.5f), 0.001f)
        // Out-of-range slider values are clamped, not extrapolated.
        assertEquals(0.75f, WakeTriggerPolicy.thresholdFor(-3f), 0.001f)
        assertEquals(0.30f, WakeTriggerPolicy.thresholdFor(7f), 0.001f)
    }

    @Test
    fun `scores below threshold never trigger`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 2)
        repeat(50) {
            assertFalse(policy.onFrame(rawScore = 0.4f, speaking = true, nowMs = t0 + it * 80L))
        }
    }

    @Test
    fun `single spiking frame does not trigger - patience is required`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 2)
        // One frame barely above threshold, then silence: the decayed smoothed score
        // (0.55 * 0.85 = 0.47) drops below threshold before patience is met.
        assertFalse(policy.onFrame(0.55f, speaking = true, nowMs = t0))  // count=1, needs 2
        assertFalse(policy.onFrame(0.0f, speaking = true, nowMs = t0 + 80)) // count=0
        assertFalse(policy.onFrame(0.0f, speaking = true, nowMs = t0 + 160))
    }

    @Test
    fun `two consecutive frames above threshold trigger once`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 2)
        assertFalse(policy.onFrame(0.9f, speaking = true, nowMs = t0))
        assertTrue(policy.onFrame(0.9f, speaking = true, nowMs = t0 + 80))
        // And it fired exactly once: immediate follow-up is inside the cooldown.
        assertFalse(policy.onFrame(0.9f, speaking = true, nowMs = t0 + 160))
    }

    @Test
    fun `cooldown blocks re-triggering until it expires`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 2, cooldownMs = 2000)
        policy.onFrame(0.9f, true, t0)
        policy.onFrame(0.9f, true, t0 + 80) // fires

        // Just before expiry: blocked.
        assertFalse(policy.onFrame(0.9f, true, t0 + 80 + 1999))
        // After expiry: needs patience again.
        assertFalse(policy.onFrame(0.9f, true, t0 + 80 + 2000))
        assertTrue(policy.onFrame(0.9f, true, t0 + 80 + 2080))
    }

    @Test
    fun `vad closed blocks triggering even with a stale high score`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 2)
        assertFalse(policy.onFrame(0.9f, speaking = false, nowMs = t0))
        assertFalse(policy.onFrame(0.9f, speaking = false, nowMs = t0 + 80))
        // Opening the VAD afterwards still needs two clean frames.
        assertFalse(policy.onFrame(0.9f, speaking = true, nowMs = t0 + 160))
        assertTrue(policy.onFrame(0.9f, speaking = true, nowMs = t0 + 240))
    }

    @Test
    fun `peak-hold smoothing bridges short dips inside a phrase`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 4, smoothDecay = 0.85f)
        policy.onFrame(0.8f, true, t0)                       // count=1
        // Brief dips: smoothed decays 0.68 -> 0.578 but stays above threshold...
        assertFalse(policy.onFrame(0.1f, true, t0 + 80))     // count=2
        assertFalse(policy.onFrame(0.1f, true, t0 + 160))    // count=3
        // ...so the fourth frame of the utterance fires without ever seeing 0.8 twice in a row.
        assertTrue(policy.onFrame(0.8f, true, t0 + 240))     // count=4 -> trigger
    }

    @Test
    fun `trigger resets the smoothed score`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 2)
        policy.onFrame(0.9f, true, t0)
        policy.onFrame(0.9f, true, t0 + 80)
        assertEquals(0f, policy.smoothed, 0.001f)
    }

    @Test
    fun `reset clears all internal state`() {
        val policy = WakeTriggerPolicy(threshold = 0.5f, patienceFrames = 2, cooldownMs = 5000)
        policy.onFrame(0.9f, true, t0)
        policy.reset()

        assertEquals(0f, policy.smoothed, 0.001f)
        // Cooldown forgotten: triggers again immediately with patience frames.
        assertFalse(policy.onFrame(0.9f, true, t0 + 80))
        assertTrue(policy.onFrame(0.9f, true, t0 + 160))
    }
}
