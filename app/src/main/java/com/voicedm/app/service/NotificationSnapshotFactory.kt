package com.voicedm.app.service

import android.app.Notification
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import com.voicedm.app.domain.parse.NotificationSnapshot
import com.voicedm.app.domain.parse.ReplyActionInfo
import com.voicedm.app.domain.parse.StyleMessage

/**
 * The only place that reads real Android notification objects.
 *
 * It flattens whatever Instagram posted into a [NotificationSnapshot]; all decisions
 * (is this our chat, is it a photo, which message is new) happen in pure Kotlin afterwards.
 */
internal fun StatusBarNotification.toSnapshot(): NotificationSnapshot {
    val notification = this.notification
    val extras: Bundle = notification.extras ?: Bundle()

    return NotificationSnapshot(
        key = key,
        postTimeMs = postTime,
        whenMs = notification.`when`,
        packageName = packageName,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        conversationTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
        messagingUserName = extras.readMessagingUserName(),
        messages = extras.readMessages(),
        hasPicture = extras.getParcelable<Uri>(Notification.EXTRA_PICTURE) != null,
        selfDisplayName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString(),
        isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        replyActions = notification.readReplyActions(),
        category = notification.category,
    )
}

@Suppress("DEPRECATION")
private fun Bundle.readMessages(): List<StyleMessage> {
    val raw = getParcelableArray(Notification.EXTRA_MESSAGES) ?: return emptyList()
    return raw.mapNotNull { entry ->
        val message = entry as? Notification.MessagingStyle.Message ?: return@mapNotNull null
        StyleMessage(
            text = message.text?.toString(),
            sender = message.senderName(),
            timestampMs = message.timestamp,
        )
    }
}

private fun Notification.MessagingStyle.Message.senderName(): String? {
    val personName = senderPerson?.name?.toString()
    if (!personName.isNullOrEmpty()) return personName
    return sender?.toString()?.takeIf { it.isNotEmpty() }
}

private fun Bundle.readMessagingUserName(): String? {
    val parcelable: Parcelable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(Notification.EXTRA_MESSAGING_PERSON, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(Notification.EXTRA_MESSAGING_PERSON)
        }
    (parcelable as? android.app.Person)?.name?.toString()?.takeIf { it.isNotEmpty() }?.let { return it }
    return getCharSequence(Notification.EXTRA_MESSAGING_PERSON)?.toString()?.takeIf { it.isNotEmpty() }
}

private fun Notification.readReplyActions(): List<ReplyActionInfo> {
    val actions = actions ?: return emptyList()
    return actions.mapIndexedNotNull { index, action ->
        val inputs = action.remoteInputs ?: return@mapIndexedNotNull null
        if (inputs.isEmpty()) return@mapIndexedNotNull null
        val keys = inputs.mapNotNull { input ->
            input.resultKey?.takeIf { key -> !TextUtils.isEmpty(key) }
        }
        if (keys.isEmpty()) return@mapIndexedNotNull null
        ReplyActionInfo(
            index = index,
            title = action.title?.toString().orEmpty(),
            resultKeys = keys,
        )
    }
}
