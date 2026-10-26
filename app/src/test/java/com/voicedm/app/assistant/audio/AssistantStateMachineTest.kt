package com.voicedm.app.assistant.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Exhaustive transition coverage for the assistant audio state machine: every legal edge,
 * the retry limit, the wake gate during TTS, and that invalid events are no-ops.
 */
class AssistantStateMachineTest {

    private lateinit var machine: AssistantStateMachine

    @Before
    fun setUp() {
        machine = AssistantStateMachine()
    }

    private fun startListening(): AssistantTransition {
        val start = machine.handle(AssistantEvent.START)
        return start
    }

    // ---------------------------------------------------------------- happy path

    @Test
    fun `service start moves stopped to wake listening`() {
        val t = startListening()
        assertEquals(AssistantState.STOPPED, t.from)
        assertEquals(AssistantState.IDLE_WAKE_LISTENING, t.to)
        assertTrue(t.changed)
        assertTrue(machine.wakeListening)
        assertTrue(machine.micHeld)
    }

    @Test
    fun `wake moves idle to command listening with chime and timer`() {
        startListening()
        val t = machine.handle(AssistantEvent.WAKE_DETECTED)

        assertEquals(AssistantState.IDLE_WAKE_LISTENING, t.from)
        assertEquals(AssistantState.COMMAND_LISTENING, t.to)
        assertTrue(t.effects.contains(AssistantEffect.CHIME_WAKE))
        assertTrue(t.effects.contains(AssistantEffect.START_COMMAND_TIMER))
        assertFalse(machine.wakeListening)
    }

    @Test
    fun `understood moves to executing with ack tone and stops timer`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        val t = machine.handle(AssistantEvent.COMMAND_UNDERSTOOD)

        assertEquals(AssistantState.EXECUTING, t.to)
        assertTrue(t.effects.contains(AssistantEffect.TONE_UNDERSTOOD))
        assertTrue(t.effects.contains(AssistantEffect.STOP_COMMAND_TIMER))
    }

    @Test
    fun `full cycle returns to wake listening`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        machine.handle(AssistantEvent.COMMAND_UNDERSTOOD)
        machine.handle(AssistantEvent.EXECUTION_FINISHED)

        assertEquals(AssistantState.IDLE_WAKE_LISTENING, machine.state)
        assertTrue(machine.wakeListening)
    }

    @Test
    fun `spoken result flows through tts and recording back to executing`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        machine.handle(AssistantEvent.COMMAND_UNDERSTOOD)

        assertEquals(
            AssistantState.TTS_SPEAKING,
            machine.handle(AssistantEvent.EXECUTION_WILL_SPEAK).to,
        )
        assertEquals(
            AssistantState.RECORDING_REPLY,
            machine.handle(AssistantEvent.TTS_WILL_REPLY).to,
        )
        assertEquals(
            AssistantState.EXECUTING,
            machine.handle(AssistantEvent.REPLY_RECORDED).to,
        )
        assertEquals(
            AssistantState.IDLE_WAKE_LISTENING,
            machine.handle(AssistantEvent.EXECUTION_FINISHED).to,
        )
    }

    @Test
    fun `discarded reply goes straight back to wake listening`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        machine.handle(AssistantEvent.COMMAND_UNDERSTOOD)
        machine.handle(AssistantEvent.EXECUTION_WILL_SPEAK)
        machine.handle(AssistantEvent.TTS_WILL_REPLY)

        val t = machine.handle(AssistantEvent.REPLY_DISCARDED)
        assertEquals(AssistantState.IDLE_WAKE_LISTENING, t.to)
        assertTrue(machine.wakeListening)
    }

    // ---------------------------------------------------------------- timeouts & retries

    @Test
    fun `command timeout returns to wake listening and disarms timer`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        val t = machine.handle(AssistantEvent.COMMAND_TIMEOUT)

        assertEquals(AssistantState.IDLE_WAKE_LISTENING, t.to)
        assertTrue(t.effects.contains(AssistantEffect.STOP_COMMAND_TIMER))
        assertTrue(machine.wakeListening)
    }

    @Test
    fun `first failure retries inside command listening`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        val t = machine.handle(AssistantEvent.COMMAND_FAILED)

        assertEquals(AssistantState.COMMAND_LISTENING, t.to)
        assertTrue(t.effects.contains(AssistantEffect.SAY_SORRY))
        assertTrue(t.effects.contains(AssistantEffect.START_COMMAND_TIMER))
        assertEquals(1, machine.retries)
    }

    @Test
    fun `second failure exhausts the single retry and gives up`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        machine.handle(AssistantEvent.COMMAND_FAILED)
        val t = machine.handle(AssistantEvent.COMMAND_FAILED)

        assertEquals(AssistantState.IDLE_WAKE_LISTENING, t.to)
        assertTrue(t.effects.contains(AssistantEffect.SAY_SORRY))
        assertTrue(t.effects.contains(AssistantEffect.STOP_COMMAND_TIMER))
        assertEquals(0, machine.retries)
    }

    // ---------------------------------------------------------------- wake gate

    @Test
    fun `wake is ignored while tts is speaking`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        machine.handle(AssistantEvent.COMMAND_UNDERSTOOD)
        machine.handle(AssistantEvent.EXECUTION_WILL_SPEAK)

        val t = machine.handle(AssistantEvent.WAKE_DETECTED)
        assertEquals(AssistantState.TTS_SPEAKING, t.from)
        assertEquals(AssistantState.TTS_SPEAKING, t.to) // ignored
        assertFalse(t.changed)
        assertTrue(t.effects.isEmpty())
    }

    @Test
    fun `wake is ignored during command listening`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        val t = machine.handle(AssistantEvent.WAKE_DETECTED)

        assertEquals(AssistantState.COMMAND_LISTENING, t.to)
        assertFalse(t.changed)
    }

    // ---------------------------------------------------------------- pause / resume / focus

    @Test
    fun `pause from listening suspends mic and resume restores it`() {
        startListening()
        val pause = machine.handle(AssistantEvent.PAUSE)
        assertEquals(AssistantState.PAUSED, pause.to)
        assertFalse(machine.micHeld)
        assertFalse(machine.wakeListening)

        val resume = machine.handle(AssistantEvent.RESUME)
        assertEquals(AssistantState.IDLE_WAKE_LISTENING, resume.to)
        assertTrue(machine.micHeld)
    }

    @Test
    fun `focus loss behaves like pause`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        val t = machine.handle(AssistantEvent.FOCUS_LOST)

        assertEquals(AssistantState.PAUSED, t.to)
        assertTrue(t.effects.contains(AssistantEffect.STOP_COMMAND_TIMER))
        assertFalse(machine.micHeld)
    }

    @Test
    fun `stop from anywhere lands in stopped and releases everything`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        val t = machine.handle(AssistantEvent.STOP)

        assertEquals(AssistantState.STOPPED, t.to)
        assertFalse(machine.micHeld)
        assertFalse(machine.wakeListening)
        assertEquals(0, machine.retries)
    }

    // ---------------------------------------------------------------- invalid events are no-ops

    @Test
    fun `events before start are ignored`() {
        val wake = machine.handle(AssistantEvent.WAKE_DETECTED)
        assertEquals(AssistantState.STOPPED, wake.to)
        assertFalse(wake.changed)
        assertTrue(wake.effects.isEmpty())

        val resume = machine.handle(AssistantEvent.RESUME)
        assertEquals(AssistantState.STOPPED, resume.to)
    }

    @Test
    fun `double start does not restart an active service`() {
        startListening()
        machine.handle(AssistantEvent.WAKE_DETECTED)
        val t = machine.handle(AssistantEvent.START)

        assertEquals(AssistantState.COMMAND_LISTENING, t.to)
        assertFalse(t.changed)
    }

    @Test
    fun `command outcomes outside command listening are ignored`() {
        startListening()
        val understood = machine.handle(AssistantEvent.COMMAND_UNDERSTOOD)
        assertFalse(understood.changed)

        val failed = machine.handle(AssistantEvent.COMMAND_FAILED)
        assertFalse(failed.changed)
        assertTrue(failed.effects.isEmpty())
    }

    @Test
    fun `tts events outside tts speaking are ignored`() {
        startListening()
        assertFalse(machine.handle(AssistantEvent.TTS_FINISHED).changed)
        assertFalse(machine.handle(AssistantEvent.TTS_WILL_REPLY).changed)
    }

    @Test
    fun `pause is idempotent and resume without pause is ignored`() {
        startListening()
        machine.handle(AssistantEvent.PAUSE)
        val again = machine.handle(AssistantEvent.PAUSE)
        assertEquals(AssistantState.PAUSED, again.to)
        assertFalse(again.changed)

        // RESUME from PAUSED works once; a second RESUME must be a no-op.
        machine.handle(AssistantEvent.RESUME)
        val second = machine.handle(AssistantEvent.RESUME)
        assertFalse(second.changed)
        assertEquals(AssistantState.IDLE_WAKE_LISTENING, second.to)
    }
}
