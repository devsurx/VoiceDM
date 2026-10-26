package com.voicedm.app.assistant.audio

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Lightweight energy gate in front of the wake-word models.
 *
 * The TFLite pipeline only runs while somebody is actually talking, which is what keeps
 * idle CPU near zero in a quiet room (the gate itself is a few arithmetic ops per frame).
 *
 * Design notes:
 *  - The noise floor is an EMA updated **only while the gate is closed**, so speech can
 *    never drag the floor up to its own level.
 *  - Enter/exit use different ratios (hysteresis) so a wavering voice does not chatter.
 *  - A hangover keeps the gate open for a few frames after the voice stops, so the tail
 *    of a phrase is still fed to the model.
 *
 * Pure Kotlin: unit-tested on synthetic frames, no Android types involved.
 */
class EnergyVad(
    /** rms must exceed noiseFloor * this to open the gate. */
    private val enterFactor: Float = 2.5f,
    /** rms must fall below noiseFloor * this (persisted) to close it. */
    private val exitFactor: Float = 1.6f,
    /** Noise-floor EMA weight while the gate is closed. */
    private val floorEma: Float = 0.05f,
    /** Absolute floor: below this, anything is treated as silence regardless of EMA. */
    private val minRms: Float = 150f,
    /** Frames (~80 ms each) the gate stays open after the last speech frame. */
    private val hangoverFrames: Int = 6,
) {

    /** Current gate state. */
    var active: Boolean = false
        private set

    /** Tracked quiet-room RMS; exposed for the debug screen. */
    var noiseFloorRms: Float = 400f
        private set

    private var quietRun = 0

    /** Feed one frame's RMS and get the new gate state. */
    fun observeRms(rms: Float): Boolean {
        val enterThreshold = max(minRms, noiseFloorRms * enterFactor)
        val exitThreshold = max(minRms * 0.7f, noiseFloorRms * exitFactor)

        if (!active) {
            if (rms >= enterThreshold) {
                // Voice onset: flip first, never adapt the floor to this frame.
                active = true
                quietRun = 0
                return true
            }
            // Quiet frame: track the room (capped so a slow rise cannot swallow speech).
            noiseFloorRms = noiseFloorRms * (1f - floorEma) + min(rms, noiseFloorRms * 3f) * floorEma
            return false
        }

        // Gate open: hysteresis + hangover; the floor is left alone while speech can be present.
        if (rms < exitThreshold) {
            quietRun++
            if (quietRun > hangoverFrames) {
                active = false
                quietRun = 0
            }
        } else {
            quietRun = 0
        }
        return active
    }

    fun reset() {
        active = false
        quietRun = 0
        noiseFloorRms = 400f
    }

    companion object {
        /** RMS of a 16-bit PCM frame. */
        fun rms(frame: ShortArray): Float {
            if (frame.isEmpty()) return 0f
            var sum = 0.0
            for (s in frame) {
                val v = s.toDouble()
                sum += v * v
            }
            return sqrt(sum / frame.size).toFloat()
        }
    }
}
