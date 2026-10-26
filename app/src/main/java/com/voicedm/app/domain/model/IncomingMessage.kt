package com.voicedm.app.domain.model

import com.voicedm.app.domain.parse.ParsedMessage

/** A parsed message plus everything needed to answer it. */
data class IncomingMessage(
    val chatName: String,
    val message: ParsedMessage,
    /** StatusBarNotification.getKey of the notification it came from. */
    val notificationKey: String,
    /** Index of the inline-reply action, -1 when the notification has none. */
    val replyActionIndex: Int,
    val receivedAtMs: Long,
) {
    /** Spoken form, as decided by the announcement rules. */
    fun announcement(
        announceSender: Boolean,
        /** Display name for the chat - used when the notification carries no sender name. */
        displayName: String? = null,
    ): String = com.voicedm.app.domain.model.Announcements.incoming(
        kind = message.kind,
        text = message.text,
        senderName = message.senderName?.takeIf { it.isNotBlank() } ?: displayName,
        announceSender = announceSender,
    )
}
