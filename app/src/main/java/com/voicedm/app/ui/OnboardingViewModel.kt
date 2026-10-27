package com.voicedm.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicedm.app.data.AppContainer
import com.voicedm.app.data.DiscoveredChat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the onboarding screen needs to draw its four steps. */
data class OnboardingUi(
    val notificationAccess: Boolean = false,
    val microphone: Boolean = false,
    val notifications: Boolean = false,
    val accessibility: Boolean = false,
    val instagramInstalled: Boolean = true,
    val chatName: String = "",
    val discovered: List<DiscoveredChat> = emptyList(),
) {
    /** The three hard requirements; accessibility stays optional (Mode A only). */
    val finished: Boolean
        get() = notificationAccess && microphone && chatName.isNotBlank()
}

/**
 * Tracks the four onboarding steps. Each step's "done" state is re-derived from
 * [com.voicedm.app.data.PermissionChecker] whenever the activity resumes, so returning
 * from a system settings screen ticks the step automatically.
 */
class OnboardingViewModel(private val container: AppContainer) : ViewModel() {

    private val _ui = MutableStateFlow(OnboardingUi())
    val ui: StateFlow<OnboardingUi> = _ui

    val discovered: StateFlow<List<DiscoveredChat>> = container.discoveredChats.chats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val stored = runCatching { container.settings.current() }.getOrNull()
            _ui.value = _ui.value.copy(chatName = stored?.targetChatName.orEmpty())
            refresh()
            container.discoveredChats.chats.collect { chats ->
                _ui.value = _ui.value.copy(discovered = chats)
            }
        }
    }

    fun refresh() {
        val permissions = container.permissions
        _ui.value = _ui.value.copy(
            notificationAccess = permissions.notificationAccessEnabled(),
            microphone = permissions.microphoneGranted(),
            notifications = permissions.notificationsGranted(),
            accessibility = permissions.accessibilityEnabled(),
            instagramInstalled = permissions.instagramInstalled(),
            discovered = container.discoveredChats.chats.value,
        )
    }

    fun setChatName(name: String) {
        _ui.value = _ui.value.copy(chatName = name)
        viewModelScope.launch {
            container.settings.update {
                it.copy(targetChatName = name.trim(), targetThreadId = "")
            }
        }
    }

    /** Tap-to-pick, anchored to the chat's Instagram thread id. */
    fun pickChat(chat: DiscoveredChat) {
        _ui.value = _ui.value.copy(chatName = chat.name)
        viewModelScope.launch {
            container.settings.update {
                it.copy(
                    targetChatName = chat.name.trim(),
                    targetThreadId = chat.threadId.orEmpty(),
                )
            }
        }
    }

    /** One-tap fill from the newest Instagram notification VoiceDM has seen. */
    fun pickLatestChat() {
        container.discoveredChats.chats.value.firstOrNull()?.let(::pickChat)
    }
}
