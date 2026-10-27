package com.voicedm.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicedm.app.data.AppContainer
import com.voicedm.app.data.DiscoveredChat
import com.voicedm.app.domain.model.AppSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings screen state: the live [AppSettings] flow plus the chats VoiceDM has seen a
 * notification from, so the contact can be tapped instead of typed.
 */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val discovered: StateFlow<List<DiscoveredChat>> = container.discoveredChats.chats
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settings.update(transform) }
    }

    /** Typing is name-matching: a fresh name drops any thread anchor from a tap. */
    fun setContact(name: String) = update {
        it.copy(targetChatName = name.trim(), targetThreadId = "")
    }

    /**
     * One tap: pick a chat VoiceDM has seen, anchored to its Instagram thread id so
     * later notifications match that exact conversation regardless of display name.
     */
    fun pickChat(chat: DiscoveredChat) = update {
        it.copy(
            targetChatName = chat.name.trim(),
            targetThreadId = chat.threadId.orEmpty(),
        )
    }

    /**
     * One tap: overwrite the contact with the chat of the newest notification VoiceDM has
     * seen ([discovered] is kept sorted newest-first by DiscoveredChatsRepository).
     */
    fun useLastNotification() {
        discovered.value.firstOrNull()?.let(::pickChat)
    }

    /** Nickname keeps its spaces while typing; [com.voicedm.app.domain.model.displayContactName] trims on read. */
    fun setNickname(name: String) = update { it.copy(contactNickname = name) }

    fun clearLog() = container.conversation.clearLog()
}
