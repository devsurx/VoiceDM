package com.voicedm.app.data

import android.service.notification.StatusBarNotification

/**
 * Keeps the freshest [StatusBarNotification] for each chat so the reply can be fired
 * against a live notification (actions/PendingIntents are only valid there).
 *
 * Instagram re-posts the same notification key on every message, so the map always holds
 * the latest object for that chat.
 */
class ActiveNotificationStore {

    private val lock = Any()
    private val byChat = LinkedHashMap<String, StatusBarNotification>()
    private val chatByKey = HashMap<String, String>()

    fun onPosted(chatName: String, sbn: StatusBarNotification) {
        synchronized(lock) {
            val normalized = normalize(chatName)
            val previousKey = byChat[normalized]?.key
            if (previousKey != null && previousKey != sbn.key) chatByKey.remove(previousKey)
            byChat[normalized] = sbn
            chatByKey[sbn.key] = normalized
            while (byChat.size > MAX_CHATS) {
                val eldest = byChat.keys.first()
                byChat.remove(eldest)
            }
        }
    }

    fun onRemoved(key: String) {
        synchronized(lock) {
            val chat = chatByKey.remove(key) ?: return
            if (byChat[chat]?.key == key) byChat.remove(chat)
        }
    }

    fun latestFor(chatName: String): StatusBarNotification? = synchronized(lock) {
        byChat[normalize(chatName)]
    }

    fun clear() = synchronized(lock) {
        byChat.clear()
        chatByKey.clear()
    }

    private fun normalize(name: String) = name.trim().lowercase()

    companion object {
        private const val MAX_CHATS = 16
    }
}
