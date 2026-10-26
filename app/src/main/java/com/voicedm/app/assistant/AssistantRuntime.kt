package com.voicedm.app.assistant

import com.voicedm.app.assistant.audio.AssistantState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide view of the running assistant, shared between [AssistantService] and the
 * Assistant screen through the app's Flow event bus (same pattern as ConversationStore -
 * one process, one source of truth, no bound-service plumbing).
 *
 * Everything user-configurable (enabled, phrase, sensitivity, false-trigger count) lives
 * in DataStore/AppSettings instead; this holds only what the service publishes at runtime.
 */
data class AssistantUiState(
    /** Foreground service is alive. */
    val serviceRunning: Boolean = false,
    /** Where the state machine currently is. */
    val state: AssistantState = AssistantState.STOPPED,
    /** True while the wake detector is being fed audio. */
    val listeningForWake: Boolean = false,
    /** Latest keyword score, 0..1. */
    val score: Float = 0f,
    /** Energy gate says someone is talking right now. */
    val speaking: Boolean = false,
    /** Rolling average model cost per 80 ms frame. */
    val avgInferenceMs: Float = 0f,
    /** Mic opened successfully. */
    val micReady: Boolean = false,
    /** Mic/open failure reason, if any. */
    val micError: String? = null,
    /** Last state-machine event, for the debug panel. */
    val lastEvent: String = "idle",
)

class AssistantRuntimeStore {

    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    fun update(transform: (AssistantUiState) -> AssistantUiState) {
        _state.update(transform)
    }
}
