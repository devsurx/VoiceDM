package com.voicedm.app.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The energy gate decides when the expensive models run, so these tests pin down the
 * behaviour that keeps idle CPU low: silence keeps it closed, speech opens it within one
 * frame, hysteresis + hangover prevent chatter, and the noise floor adapts only while the
 * gate is closed.
 */
class EnergyVadTest {

    private fun rmsFrame(rms: Float): ShortArray {
        // Constant-amplitude square wave: its RMS equals the amplitude exactly.
        val amplitude = rms.toInt().coerceIn(1, 32767)
        return ShortArray(1280) { if (it % 2 == 0) amplitude.toShort() else (-amplitude).toShort() }
    }

    private fun sineFrame(rms: Float): ShortArray {
        val amplitude = (rms * kotlin.math.sqrt(2f))
        return ShortArray(1280) { i ->
            (amplitude * sin(2.0 * PI * i / 32.0)).toInt().toShort()
        }
    }

    @Test
    fun `rms helper matches expected amplitude`() {
        val frame = rmsFrame(1000f)
        assertEquals(1000f, EnergyVad.rms(frame), 2f)
        assertEquals(0f, EnergyVad.rms(ShortArray(0)), 0f)
    }

    @Test
    fun `silence keeps the gate closed`() {
        val vad = EnergyVad()
        repeat(20) { assertFalse(vad.observeRms(EnergyVad.rms(rmsFrame(200f)))) }
        assertFalse(vad.active)
    }

    @Test
    fun `loud burst opens the gate within one frame`() {
        val vad = EnergyVad()
        repeat(5) { vad.observeRms(EnergyVad.rms(rmsFrame(200f))) }

        assertTrue(vad.observeRms(EnergyVad.rms(rmsFrame(8000f))))
        assertTrue(vad.active)
    }

    @Test
    fun `gate closes only after the hangover expires`() {
        val vad = EnergyVad(hangoverFrames = 4)
        vad.observeRms(EnergyVad.rms(rmsFrame(8000f)))
        assertTrue(vad.active)

        // 4 quiet frames: still inside the hangover.
        repeat(4) { assertTrue(vad.observeRms(EnergyVad.rms(rmsFrame(200f)))) }
        // 5th quiet frame exceeds the run -> closed.
        assertFalse(vad.observeRms(EnergyVad.rms(rmsFrame(200f))))
        assertFalse(vad.active)
    }

    @Test
    fun `speech right before hangover expires keeps the gate open`() {
        val vad = EnergyVad(hangoverFrames = 4)
        vad.observeRms(EnergyVad.rms(rmsFrame(8000f)))
        repeat(3) { vad.observeRms(EnergyVad.rms(rmsFrame(200f))) }
        assertTrue(vad.observeRms(EnergyVad.rms(rmsFrame(6000f)))) // voice returns in time
        assertTrue(vad.active)
    }

    @Test
    fun `moderate voice opens but moderate noise does not`() {
        val vad = EnergyVad()
        // Quiet room first so the floor settles low.
        repeat(20) { vad.observeRms(EnergyVad.rms(rmsFrame(250f))) }

        // ~3000 RMS is clearly speech in this room; 400 is not.
        assertFalse(vad.observeRms(EnergyVad.rms(rmsFrame(400f))))
        assertTrue(vad.observeRms(EnergyVad.rms(rmsFrame(3000f))))
    }

    @Test
    fun `noise floor does not adapt while the gate is open`() {
        val vad = EnergyVad()
        repeat(10) { vad.observeRms(EnergyVad.rms(rmsFrame(250f))) }
        val floorBefore = vad.noiseFloorRms

        vad.observeRms(EnergyVad.rms(rmsFrame(9000f)))
        assertTrue(vad.active)
        val floorWhileSpeaking = vad.noiseFloorRms

        assertEquals(floorBefore, floorWhileSpeaking, 0.01f)
    }

    @Test
    fun `reset returns the gate to a clean state`() {
        val vad = EnergyVad()
        vad.observeRms(EnergyVad.rms(rmsFrame(9000f)))
        assertTrue(vad.active)

        vad.reset()
        assertFalse(vad.active)
        assertEquals(400f, vad.noiseFloorRms, 0.01f)
    }

    @Test
    fun `sine speech-like frame also triggers`() {
        val vad = EnergyVad()
        repeat(10) { vad.observeRms(EnergyVad.rms(sineFrame(250f))) }
        assertTrue(vad.observeRms(EnergyVad.rms(sineFrame(5000f))))
    }
}
