package com.voicedm.app.assistant.commands

/**
 * The fixed voice vocabulary. Everything the assistant understands is in this one file:
 * add a command by adding an enum entry (router wiring arrives with Stage 2).
 *
 * [spoken] lists the canonical phrase first, then the synonyms the router must also
 * accept ("go on", "next", "say again", ...). Matching is deliberately closed-world -
 * a small offline recogniser feeding a fixed list is far more accurate than free dictation.
 */
enum class Command(
    /** Phrases that trigger this command, canonical first. */
    val spoken: List<String>,
    /** Shown on the Assistant screen. */
    val description: String,
) {
    START_CONVERSATION(
        listOf("start conversation", "start", "begin", "let's talk"),
        "Start the conversation loop",
    ),
    STOP_CONVERSATION(
        listOf("stop conversation", "stop", "end conversation"),
        "Stop the conversation loop",
    ),
    READ_LAST(
        listOf("read last message", "repeat", "say again", "again", "what did they say"),
        "Read the last message again",
    ),
    SKIP(
        listOf("skip", "next", "go on", "keep going"),
        "Stop reading the current message",
    ),
    REPLY(
        listOf("reply", "answer", "respond"),
        "Start recording your reply now",
    ),
    CANCEL(
        listOf("cancel", "discard", "never mind"),
        "Discard the recording",
    ),
    SEND(
        listOf("send", "send it", "yes", "confirm"),
        "Confirm and send (when confirm mode is on)",
    ),
    DONT_SEND(
        listOf("don't send", "do not send", "no", "stop"),
        "Back out of the confirmation",
    ),
    PAUSE_ASSISTANT(
        listOf("pause assistant", "pause assistant", "sleep"),
        "Stop listening for the wake phrase",
    ),
    RESUME_ASSISTANT(
        listOf("resume assistant", "wake up", "resume"),
        "Start listening for the wake phrase again",
    ),
    SPEAK_FASTER(
        listOf("speak faster", "faster", "speed up"),
        "Increase TTS speed",
    ),
    SPEAK_SLOWER(
        listOf("speak slower", "slower", "slow down"),
        "Decrease TTS speed",
    ),
    TEXT_MODE(
        listOf("switch to text mode", "text mode", "type mode"),
        "Send replies as inline text (Mode A)",
    ),
    VOICE_NOTE_MODE(
        listOf("switch to voice note mode", "voice note mode"),
        "Send replies as voice notes (Mode B)",
    ),
    STATUS(
        listOf("what's the status", "what is the status", "status", "what are you doing"),
        "Say what the assistant is doing",
    );

    companion object {
        /** All phrases the router must recognise, lower-cased. */
        fun allPhrases(): List<Pair<String, Command>> =
            entries.flatMap { cmd -> cmd.spoken.map { it to cmd } }
    }
}
