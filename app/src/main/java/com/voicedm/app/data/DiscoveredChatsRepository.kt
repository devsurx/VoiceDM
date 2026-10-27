package com.voicedm.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A chat VoiceDM has seen a notification from, offered as a tap-to-pick choice. */
data class DiscoveredChat(
    val name: String,
    val lastSeenMs: Long,
    val messageCount: Int,
    /** Instagram's stable thread id when the notification carried one; anchors a pick. */
    val threadId: String? = null,
)

private val Context.discoveredChatsStore: DataStore<Preferences> by preferencesDataStore(name = "voicedm_discovered")

/**
 * Remembers the chat names Instagram notifies us about so the user can pick their contact
 * by tapping instead of typing the name exactly right.
 */
class DiscoveredChatsRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _chats = MutableStateFlow<List<DiscoveredChat>>(emptyList())
    val chats: StateFlow<List<DiscoveredChat>> = _chats.asStateFlow()

    init {
        scope.launch { load() }
    }

    fun record(chatName: String, threadId: String? = null) {
        val name = chatName.trim()
        if (name.isEmpty()) return
        val key = normalize(name)
        val now = clock()
        val current = _chats.value.toMutableList()
        val existing = current.indexOfFirst { normalize(it.name) == key }
        if (existing >= 0) {
            val previous = current[existing]
            current[existing] = previous.copy(
                name = name,
                lastSeenMs = now,
                messageCount = previous.messageCount + 1,
                threadId = threadId ?: previous.threadId,
            )
        } else {
            current += DiscoveredChat(name, now, 1, threadId)
        }
        val next = current.sortedByDescending { it.lastSeenMs }.take(MAX_CHATS)
        _chats.value = next
        persist(next)
    }

    private suspend fun load() {
        val stored = context.discoveredChatsStore.data.first()
        val loaded = stored.asMap()
            .mapNotNull { (rawKey, value) ->
                val key = rawKey.name
                if (!key.startsWith(PREFIX)) return@mapNotNull null
                val parts = (value as? String)?.split(';', limit = 4) ?: return@mapNotNull null
                if (parts.size < 3) return@mapNotNull null
                val lastSeen = parts[0].toLongOrNull() ?: return@mapNotNull null
                val count = parts[1].toIntOrNull() ?: return@mapNotNull null
                DiscoveredChat(
                    name = parts[2],
                    lastSeenMs = lastSeen,
                    messageCount = count,
                    threadId = parts.getOrNull(3)?.takeIf { it.isNotEmpty() },
                )
            }
            .sortedByDescending { it.lastSeenMs }
            .take(MAX_CHATS)
        if (loaded.isNotEmpty()) _chats.value = loaded
    }

    private fun persist(chats: List<DiscoveredChat>) {
        scope.launch {
            context.discoveredChatsStore.edit { prefs ->
                prefs.clear()
                chats.forEach { chat ->
                    prefs[stringPreferencesKey(PREFIX + normalize(chat.name))] = buildString {
                        append(chat.lastSeenMs)
                        append(';')
                        append(chat.messageCount)
                        append(';')
                        append(chat.name)
                        if (!chat.threadId.isNullOrEmpty()) {
                            append(';')
                            append(chat.threadId)
                        }
                    }
                }
            }
        }
    }

    private fun normalize(name: String) = name.trim().lowercase().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    companion object {
        const val MAX_CHATS = 20
        private const val PREFIX = "chat:"
    }
}
