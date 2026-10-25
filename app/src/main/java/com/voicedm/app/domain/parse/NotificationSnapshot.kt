package com.voicedm.app.domain.parse

/**
 * Pure, Android-free mirror of a posted [android.app.Notification].
 *
 * The notification listener is the only place that touches real Android notification
 * objects; everything downstream (filtering, chat matching, dedupe) works on this
 * snapshot so it can be unit tested on the JVM.
 */
data class NotificationSnapshot(
    /** [android.service.notification.StatusBarNotification.getKey], stable per notification. */
    val key: String,
    /** When the notification was last posted. */
    val postTimeMs: Long,
    /** `Notification.when`, i.e. the message timestamp Instagram set. */
    val whenMs: Long,
    val packageName: String,
    /** EXTRA_TITLE, e.g. the sender or "Instagram". */
    val title: String? = null,
    /** EXTRA_TEXT. */
    val text: String? = null,
    /** EXTRA_BIG_TEXT. */
    val bigText: String? = null,
    /** EXTRA_SUB_TEXT. */
    val subText: String? = null,
    /** EXTRA_CONVERSATION_TITLE from MessagingStyle. */
    val conversationTitle: String? = null,
    /** EXTRA_MESSAGING_STYLE_USER, the person the style belongs to. */
    val messagingUserName: String? = null,
    /** EXTRA_MESSAGES from MessagingStyle, already flattened. Empty when not a style. */
    val messages: List<StyleMessage> = emptyList(),
    /** EXTRA_PICTURE present -> a photo/image notification. */
    val hasPicture: Boolean = false,
    /**
     * EXTRA_SELF_DISPLAY_NAME: how the app labels *us*. Present, our own messages can be
     * filtered out of the MessagingStyle list.
     */
    val selfDisplayName: String? = null,
    /** FLAG_GROUP_SUMMARY, never a real message. */
    val isGroupSummary: Boolean = false,
    /** Actions that carry a RemoteInput, i.e. possible inline-reply targets. */
    val replyActions: List<ReplyActionInfo> = emptyList(),
    val category: String? = null,
)

/** One entry of Notification.EXTRA_MESSAGES. */
data class StyleMessage(
    val text: String?,
    val sender: String?,
    val timestampMs: Long,
)

/** A notification action that can take free-form text (inline reply). */
data class ReplyActionInfo(
    /** Index inside [android.app.Notification.getActions]; action ids are usually all -1. */
    val index: Int,
    val title: String,
    /** RemoteInput result keys, e.g. ["extra_text"]. */
    val resultKeys: List<String>,
)
