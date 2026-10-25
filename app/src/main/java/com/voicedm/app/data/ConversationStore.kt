package com.voicedm.app.data

import com.voicedm.app.domain.model.ConversationPhase
import com.voicedm.app.domain.model.ConversationUiState
import com.voicedm.app.domain.model.LogDirection
import com.voicedm.app.domain.model.LogEntry
import com.voicedm.app.domain.model.MessageKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide conversation state shared by the foreground service (the writer) and the
 * Compose UI, widget and tile (the readers).
 */
class ConversationStore {

    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()

    private var nextLogId = 0L

    fun setRunning(running: Boolean, chatName: String = _state.value.chatName) {
        _state.update {
            it.copy(
                isRunning = running,
                chatName = chatName,
                phase = if (running) ConversationPhase.WAITING else ConversationPhase.IDLE,
                errorMessage = null,
                pendingTranscript = null,
                current = null,
            )
        }
    }

    fun setChatName(chatName: String) = _state.update { it.copy(chatName = chatName) }

    fun setPhase(phase: ConversationPhase, errorMessage: String? = null) {
        _state.update { it.copy(phase = phase, errorMessage = errorMessage) }
    }

    fun setCurrent(entry: LogEntry?) = _state.update { it.copy(current = entry) }

    fun setPendingTranscript(text: String?) = _state.update { it.copy(pendingTranscript = text) }

    fun log(
        direction: LogDirection,
        text: String,
        kind: MessageKind = MessageKind.TEXT,
        timeMs: Long = System.currentTimeMillis(),
    ): LogEntry {
        val entry = LogEntry(
            id = nextLogId++,
            direction = direction,
            text = text,
            timeMs = timeMs,
            kind = kind,
        )
        _state.update { state ->
            val log = (state.log + entry).takeLast(MAX_LOG)
            state.copy(log = log, current = if (direction == LogDirection.OUTGOING) entry else state.current)
        }
        return entry
    }

    fun clearLog() = _state.update { it.copy(log = emptyList(), current = null) }

    fun reset() {
        nextLogId = 0
        _state.value = ConversationUiState()
    }

    companion object {
        const val MAX_LOG = 50
    }
}
