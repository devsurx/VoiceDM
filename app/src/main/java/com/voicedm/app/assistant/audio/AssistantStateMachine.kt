package com.voicedm.app.assistant.audio

/**
 * States the audio subsystem can be in. Exactly one component may hold the microphone,
 * and [AssistantStateMachine] is the only thing that decides when.
 *
 * ```
 *            START                    WAKE_DETECTED            UNDERSTOOD
 *  STOPPED --------> IDLE_WAKE_LISTENING ------> COMMAND_LISTENING ------> EXECUTING
 *                        ^   |                        |   ^                   |
 *             RESUME     |   | TIMEOUT / max retries  |   | REPLY_RECORDED     | DONE_SPOKEN
 *   PAUSED <-------------+   |                        +---+                   v
 *      ^                      |  (Stage 2+ command flow)                  TTS_SPEAKING
 *      |  PAUSE / FOCUS_LOST  |                                              |
 *      +----------------------+                     TTS_WILL_REPLY           v
 *                                                     RECORDING_REPLY --> (back to
 *                                                                          IDLE / EXECUTING)
 * ```
 *
 * The machine is pure: [handle] returns the transition (old state, new state, effects the
 * Android layer must perform) and never touches audio hardware itself. That makes every
 * transition unit-testable on the JVM.
 */
enum class AssistantState {
    /** Service not running. */
    STOPPED,

    /** Microphone held, wake-word engine running - the resting state. */
    IDLE_WAKE_LISTENING,

    /** Wake heard: chime played, waiting for the one-shot command (5 s budget). */
    COMMAND_LISTENING,

    /** A command was understood and is being carried out. */
    EXECUTING,

    /** Assistant (or the conversation loop) is speaking; wake detector is gated off. */
    TTS_SPEAKING,

    /** Recording the user's reply. */
    RECORDING_REPLY,

    /** Deliberately paused (notification action, focus loss, or conversation took audio). */
    PAUSED,
}

enum class AssistantEvent {
    START,
    WAKE_DETECTED,
    /** Command recognised - carries the flow into EXECUTING. */
    COMMAND_UNDERSTOOD,
    /** Nothing recognised; up to one retry inside COMMAND_LISTENING, then give up. */
    COMMAND_FAILED,
    COMMAND_TIMEOUT,
    /** Command done; nothing left to say. */
    EXECUTION_FINISHED,
    /** Command done and the result must be spoken. */
    EXECUTION_WILL_SPEAK,
    TTS_FINISHED,
    /** After TTS, the conversation flow wants a recorded reply. */
    TTS_WILL_REPLY,
    REPLY_RECORDED,
    REPLY_DISCARDED,
    PAUSE,
    RESUME,
    /** Audio focus lost (call, another app, headset change): same as pause, resumed later. */
    FOCUS_LOST,
    STOP,
}

/** Side effects the service layer must perform for a transition. */
enum class AssistantEffect {
    /** "Hello" chime: you have my attention. */
    CHIME_WAKE,
    /** Different tone: command understood. */
    TONE_UNDERSTOOD,
    /** "Sorry, I didn't get that." (speech, Stage 2) / failure tone (Stage 1). */
    SAY_SORRY,
    /** Arm the 5 s command timeout. */
    START_COMMAND_TIMER,
    /** Disarm the command timeout. */
    STOP_COMMAND_TIMER,
}

data class AssistantTransition(
    val from: AssistantState,
    val to: AssistantState,
    val effects: Set<AssistantEffect>,
) {
    val changed: Boolean get() = from != to
}

/**
 * Pure transition table + the small amount of memory it needs (pause origin, retry count).
 * See the class diagram above; invalid events are ignored (transition with from == to).
 */
class AssistantStateMachine {

    var state: AssistantState = AssistantState.STOPPED
        private set

    /** Consecutive failed recognitions; spec allows exactly one retry. */
    var retries: Int = 0
        private set

    /** State to return to on RESUME (always IDLE today; kept for future command flows). */
    private var resumeTarget: AssistantState = AssistantState.IDLE_WAKE_LISTENING

    /** True while the wake detector must run. */
    val wakeListening: Boolean get() = state == AssistantState.IDLE_WAKE_LISTENING

    /** True while we own the microphone (everything except STOPPED/PAUSED). */
    val micHeld: Boolean
        get() = state != AssistantState.STOPPED && state != AssistantState.PAUSED

    fun handle(event: AssistantEvent): AssistantTransition {
        val from = state
        val effects = mutableSetOf<AssistantEffect>()
        var to = from

        when (event) {
            AssistantEvent.START ->
                if (from == AssistantState.STOPPED) {
                    to = AssistantState.IDLE_WAKE_LISTENING
                    retries = 0
                }

            AssistantEvent.WAKE_DETECTED ->
                // Only a resting detector can wake us - this is what gates out self-wake
                // while TTS speaks or a command runs.
                if (from == AssistantState.IDLE_WAKE_LISTENING) {
                    to = AssistantState.COMMAND_LISTENING
                    effects += AssistantEffect.CHIME_WAKE
                    effects += AssistantEffect.START_COMMAND_TIMER
                    retries = 0
                }

            AssistantEvent.COMMAND_UNDERSTOOD ->
                if (from == AssistantState.COMMAND_LISTENING) {
                    to = AssistantState.EXECUTING
                    effects += AssistantEffect.TONE_UNDERSTOOD
                    effects += AssistantEffect.STOP_COMMAND_TIMER
                }

            AssistantEvent.COMMAND_FAILED ->
                if (from == AssistantState.COMMAND_LISTENING) {
                    effects += AssistantEffect.SAY_SORRY
                    if (retries < MAX_RETRIES) {
                        retries++
                        to = AssistantState.COMMAND_LISTENING
                        effects += AssistantEffect.START_COMMAND_TIMER
                    } else {
                        to = AssistantState.IDLE_WAKE_LISTENING
                        effects += AssistantEffect.STOP_COMMAND_TIMER
                        retries = 0
                    }
                }

            AssistantEvent.COMMAND_TIMEOUT ->
                if (from == AssistantState.COMMAND_LISTENING) {
                    to = AssistantState.IDLE_WAKE_LISTENING
                    effects += AssistantEffect.STOP_COMMAND_TIMER
                    retries = 0
                }

            AssistantEvent.EXECUTION_FINISHED ->
                if (from == AssistantState.EXECUTING || from == AssistantState.RECORDING_REPLY) {
                    to = AssistantState.IDLE_WAKE_LISTENING
                }

            AssistantEvent.EXECUTION_WILL_SPEAK ->
                if (from == AssistantState.EXECUTING) to = AssistantState.TTS_SPEAKING

            AssistantEvent.TTS_FINISHED ->
                if (from == AssistantState.TTS_SPEAKING) to = AssistantState.IDLE_WAKE_LISTENING

            AssistantEvent.TTS_WILL_REPLY ->
                if (from == AssistantState.TTS_SPEAKING) to = AssistantState.RECORDING_REPLY

            AssistantEvent.REPLY_RECORDED ->
                if (from == AssistantState.RECORDING_REPLY) to = AssistantState.EXECUTING

            AssistantEvent.REPLY_DISCARDED ->
                if (from == AssistantState.RECORDING_REPLY) to = AssistantState.IDLE_WAKE_LISTENING

            AssistantEvent.PAUSE ->
                if (from != AssistantState.STOPPED && from != AssistantState.PAUSED) {
                    resumeTarget = if (from == AssistantState.IDLE_WAKE_LISTENING) {
                        AssistantState.IDLE_WAKE_LISTENING
                    } else {
                        AssistantState.IDLE_WAKE_LISTENING // command flows restart from rest
                    }
                    to = AssistantState.PAUSED
                    effects += AssistantEffect.STOP_COMMAND_TIMER
                }

            AssistantEvent.RESUME ->
                if (from == AssistantState.PAUSED) {
                    to = resumeTarget
                    retries = 0
                }

            AssistantEvent.FOCUS_LOST ->
                if (from != AssistantState.STOPPED && from != AssistantState.PAUSED) {
                    resumeTarget = AssistantState.IDLE_WAKE_LISTENING
                    to = AssistantState.PAUSED
                    effects += AssistantEffect.STOP_COMMAND_TIMER
                }

            AssistantEvent.STOP ->
                if (from != AssistantState.STOPPED) {
                    to = AssistantState.STOPPED
                    effects += AssistantEffect.STOP_COMMAND_TIMER
                    retries = 0
                }
        }

        state = to
        return AssistantTransition(from, to, effects)
    }

    companion object {
        /** Spec: "Sorry, I didn't get that" with a maximum of 1 retry. */
        const val MAX_RETRIES = 1
    }
}
