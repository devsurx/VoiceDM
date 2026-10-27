package com.voicedm.app.domain.parse

import com.voicedm.app.domain.model.MessageKind

/** A single message we decided to act on. */
data class ParsedMessage(
    /** Stable identity used to avoid reading the same notification update twice. */
    val dedupeKey: String,
    val chatName: String,
    val senderName: String?,
    val kind: MessageKind,
    /** Raw body; media kinds keep the placeholder text for the on-screen log. */
    val text: String?,
    val timestampMs: Long,
    /**
     * True when [timestampMs] came from a message-level timestamp (MessagingStyle entry or
     * `Notification.when`). False means we fell back to the system post time, which changes
     * every time Instagram re-posts the same notification, so a fuzzy second check applies.
     */
    val reliableTime: Boolean,
)

/** Everything the engine needs about one Instagram notification. */
data class ParsedChat(
    val chatName: String,
    val messages: List<ParsedMessage>,
    /**
     * Index of the inline-reply action inside `Notification.actions`, or [NO_REPLY_ACTION].
     *
     * Action ids are almost always -1 so the index is the only stable handle we have;
     * the sender still re-validates the live action before firing it.
     */
    val replyActionIndex: Int = NO_REPLY_ACTION,
    /** Instagram's stable thread id for this chat, when the notification carried one. */
    val threadId: String? = null,
) {
    companion object {
        const val NO_REPLY_ACTION = -1
    }
}
