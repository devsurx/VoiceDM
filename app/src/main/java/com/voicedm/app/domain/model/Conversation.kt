package com.voicedm.app.domain.model

/** How VoiceDM delivers your reply back into Instagram. */
enum class ReplyMode {
    /**
     * Mode A (default, robust): transcribe your speech and fire the notification's
     * inline-reply action. Instagram never sees anything but a normal typed reply.
     */
    INLINE_TEXT,

    /**
     * Mode B (experimental): drive Instagram with an AccessibilityService, hold the
     * record button and let Instagram capture your voice as a real voice note.
     */
    VOICE_NOTE,
}

/** High level phase of the conversation loop, surfaced verbatim on the main screen. */
enum class ConversationPhase {
    /** Engine is off. */
    IDLE,

    /** Service is running but waiting for a message. */
    WAITING,

    /** Speaking an incoming message. */
    READING,

    /** Listening for your reply. */
    LISTENING,

    /** Waiting for you to confirm a transcript before sending (confirm mode). */
    CONFIRMING,

    /** Handing the reply to Instagram. */
    SENDING,

    /** Reply went out. */
    SENT,

    /** Nothing usable was heard; the loop moved on. */
    SKIPPED,

    /** Something went wrong (see [ConversationUiState.errorMessage]). */
    ERROR,
}

/** Direction of a line in the on-screen message log. */
enum class LogDirection { INCOMING, OUTGOING, SYSTEM }

/** One line of the rolling message log shown on the main screen. */
data class LogEntry(
    val id: Long,
    val direction: LogDirection,
    val text: String,
    val timeMs: Long,
    val kind: MessageKind = MessageKind.TEXT,
)

/** Everything the UI needs to draw the main screen. */
data class ConversationUiState(
    val isRunning: Boolean = false,
    val phase: ConversationPhase = ConversationPhase.IDLE,
    /** Name of the chat we are allowed to talk to, empty when not configured. */
    val chatName: String = "",
    /** Message currently being spoken / answered. */
    val current: LogEntry? = null,
    /** Transcript of what we heard, while confirming. */
    val pendingTranscript: String? = null,
    val errorMessage: String? = null,
    /** Rolling log, newest last. */
    val log: List<LogEntry> = emptyList(),
)
