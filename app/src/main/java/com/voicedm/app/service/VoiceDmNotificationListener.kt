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

    override fun onCreate() {
        super.onCreate()
        container = VoiceDmApp.from(this).container
        scope.launch {
            container.settings.settings.collect { settings ->
                val target = settings.targetChatName
                if (target != targetChat) {
                    targetChat = target
                    // New person selected: forget everything we knew about the old one.
                    container.pipeline.onTargetChanged()
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
        container.discoveredChats.record(chat.chatName)

        if (!ChatMatcher.matches(targetChat, chat.chatName)) return
        container.activeNotifications.onPosted(chat.chatName, sbn)

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
