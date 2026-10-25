package com.voicedm.app.domain.model

/**
 * Builds the sentence spoken by TTS for an incoming message.
 *
 * Kept pure and Android-free so it can be reasoned about (and tested) without a device.
 */
object Announcements {

    fun incoming(
        kind: MessageKind,
        text: String?,
        senderName: String?,
        announceSender: Boolean,
    ): String {
        val who = if (announceSender && !senderName.isNullOrBlank()) {
            "$senderName says. "
        } else {
            ""
        }
        val body = when (kind) {
            MessageKind.VOICE_NOTE -> "Voice message received"
            MessageKind.PHOTO -> "Photo received"
            MessageKind.VIDEO -> "Video received"
            MessageKind.REEL -> "Reel received"
            MessageKind.STORY_MENTION -> "You were mentioned in a story"
            MessageKind.TEXT, MessageKind.UNKNOWN ->
                text?.trim().takeUnless { it.isNullOrEmpty() } ?: "New message received"
        }
        return who + body
    }
}
