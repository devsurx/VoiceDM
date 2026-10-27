package com.voicedm.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.voicedm.app.VoiceDmApp
import com.voicedm.app.data.AppContainer
import com.voicedm.app.data.PermissionChecker
import com.voicedm.app.domain.model.IncomingMessage
import com.voicedm.app.domain.parse.ChatMatcher
import com.voicedm.app.domain.parse.NotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches Instagram's notifications and turns the ones from the chosen chat into messages
 * for the conversation engine.
 *
 * This is the only component that needs notification access; it never talks to Instagram
 * directly, it only reads what Android already surfaced.
 */
class VoiceDmNotificationListener : NotificationListenerService() {

    private lateinit var container: AppContainer
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var targetChat: String = ""

    @Volatile
    private var targetThread: String = ""

    override fun onCreate() {
        super.onCreate()
        container = VoiceDmApp.from(this).container
        scope.launch {
            container.settings.settings.collect { settings ->
                val target = settings.targetChatName
                val thread = settings.targetThreadId
                if (target != targetChat) {
                    targetChat = target
                    targetThread = thread
                    // New person selected: forget everything we knew about the old one.
                    container.pipeline.onTargetChanged()
                    container.activeNotifications.clear()
                    if (target.isNotBlank()) refreshExistingNotifications()
                } else if (thread != targetThread) {
                    // The pick got anchored (or un-anchored) to a thread: re-filter the
                    // live notifications, but keep the queued messages - it is the same
                    // person, just a stricter identity.
                    targetThread = thread
                    container.activeNotifications.clear()
                    if (target.isNotBlank()) refreshExistingNotifications()
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch { refreshExistingNotifications() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn ?: return
        try {
            handle(notification)
        } catch (t: Throwable) {
            // Never let a parse error kill notification access.
            android.util.Log.w(TAG, "Failed to process notification", t)
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { container.activeNotifications.onRemoved(it.key) }
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun refreshExistingNotifications() {
        val active = activeNotifications ?: return
        active.forEach(::handle)
    }

    private fun handle(sbn: StatusBarNotification) {
        if (sbn.packageName != PermissionChecker.INSTAGRAM_PACKAGE) return

        val chat = NotificationParser.parse(sbn.toSnapshot()) ?: return
        container.discoveredChats.record(chat.chatName, chat.threadId)

        val matched = ChatMatcher.matches(targetChat, targetThread, chat.chatName, chat.threadId)
        if (!matched) return

        // First name match proves which thread the typed contact belongs to: pin the
        // pick to it so later notifications keep matching even when Instagram shows a
        // different display name for the same conversation.
        val thread = chat.threadId
        if (targetThread.isEmpty() && thread != null &&
            ChatMatcher.matches(targetChat, chat.chatName)
        ) {
            scope.launch {
                container.settings.update { it.copy(targetThreadId = thread) }
            }
        }

        container.activeNotifications.onPosted(chat.chatName, sbn)
        // Matched by thread while Instagram named the chat differently: index the
        // notification under the chosen name too, so the manual Reply path finds it.
        if (thread != null && !ChatMatcher.matches(targetChat, chat.chatName)) {
            container.activeNotifications.onPosted(targetChat, sbn)
        }

        val now = System.currentTimeMillis()
        chat.messages.forEach { message ->
            container.pipeline.publish(
                IncomingMessage(
                    chatName = chat.chatName,
                    message = message,
                    notificationKey = sbn.key,
                    replyActionIndex = chat.replyActionIndex,
                    receivedAtMs = now,
                ),
            )
        }
    }

    private companion object {
        const val TAG = "VoiceDM"
    }
}
