package com.voicedm.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicedm.app.assistant.AssistantService
import com.voicedm.app.assistant.AssistantUiState
import com.voicedm.app.data.AppContainer
import com.voicedm.app.domain.model.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Assistant screen state: DataStore settings plus the live runtime view published by
 * [AssistantService] over the shared Flow bus.
 */
class AssistantViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val runtime: StateFlow<AssistantUiState> = container.assistant.state

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    /** Master switch: persists the choice and starts/stops the foreground service. */
    fun setEnabled(context: Context, enabled: Boolean) {
        update { it.copy(assistantEnabled = enabled) }
        if (enabled) {
            if (container.permissions.microphoneGranted()) {
                AssistantService.start(context)
            }
        } else {
            AssistantService.stop(context)
        }
    }

    fun setPhrase(id: String) = update { it.copy(assistantPhraseId = id) }

    fun setSensitivity(value: Float) = update { it.copy(assistantSensitivity = value.coerceIn(0f, 1f)) }

    fun resetWakeUps() = update { it.copy(assistantFalseTriggers = 0) }

    /** Test button / notification actions. */
    fun start(context: Context) = AssistantService.start(context)
    fun pause(context: Context) = AssistantService.pause(context)
    fun resume(context: Context) = AssistantService.resume(context)

    val microphoneGranted: Boolean get() = container.permissions.microphoneGranted()
}
