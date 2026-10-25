package com.voicedm.app.domain.parse

import com.voicedm.app.domain.model.MessageKind

/**
 * Turns a [NotificationSnapshot] into the list of messages VoiceDM should act on.
 *
 * Everything in here is pure Kotlin: no Android types, no I/O, no clock. The notification
 * listener is responsible for building the snapshot and for feeding the result through
 * [MessageDeduper].
 *
 * Supported shapes (as Instagram posts them today):
 *  - MessagingStyle notifications with EXTRA_MESSAGES (one or many entries),
 *  - flat notifications where EXTRA_TEXT is either the body or "Sender: body".
 */
object NotificationParser {

    /** Titles that identify the app rather than a chat. */
    private val APP_TITLES = setOf("instagram", "instagram dm", "direct", "com.instagram.android")

    /** Whole-string placeholder phrases Instagram uses for media. */
    private val VOICE_PHRASES = setOf(
        "voice message",
        "voice note",
        "sent a voice message",
        "sent you a voice message",
        "sent a voice note",
        "sent you a voice note",
        "sent an audio message",
        "sent you an audio message",
    )

    private val PHOTO_PHRASES = setOf(
        "photo",
        "image",
        "picture",
        "sent a photo",
        "sent you a photo",
        "sent a photo message",
        "sent an image",
        "sent you an image",
        "sent a picture",
        "sent you a picture",
        "shared a photo",
        "shared a picture",
        "sent a gif",
        "sent a gif message",
    )

    private val VIDEO_PHRASES = setOf(
        "video",
        "sent a video",
        "sent you a video",
        "sent a video message",
    )

    private val REEL_PHRASES = setOf(
        "reel",
        "sent a reel",
        "sent you a reel",
        "shared a reel",
    )

    private val STORY_PATTERN = Regex("mentioned you in their story")

    fun parse(snapshot: NotificationSnapshot): ParsedChat? {
        if (snapshot.isGroupSummary) return null

        val chatName = resolveChatName(snapshot) ?: return null
        val messages = if (snapshot.messages.isNotEmpty()) {
            parseStyle(snapshot, chatName)
        } else {
            listOfNotNull(parseFlat(snapshot, chatName))
        }
        if (messages.isEmpty()) return null

        return ParsedChat(
            chatName = chatName,
            messages = messages,
            replyActionIndex = snapshot.replyActions.firstOrNull()?.index ?: ParsedChat.NO_REPLY_ACTION,
        )
    }

    /** The chat a notification belongs to, or null when it does not name one. */
    fun resolveChatName(snapshot: NotificationSnapshot): String? {
        snapshot.conversationTitle.asChatName()?.let { return it }
        snapshot.title.asChatName()?.let { return it }
        snapshot.subText.asChatName()?.let { return it }
        val flat = snapshot.bigText ?: snapshot.text
        flat?.let { senderPrefixOf(it) }?.takeIf { it.isNotBlank() }?.let { return it }
        return null
    }

    private fun parseStyle(snapshot: NotificationSnapshot, chatName: String): List<ParsedMessage> {
        val lastIndex = snapshot.messages.lastIndex
        return snapshot.messages.mapIndexedNotNull { index, message ->
            if (isOwnMessage(message.sender, snapshot.selfDisplayName)) return@mapIndexedNotNull null
            val body = message.text?.trim().orEmpty()
            val sender = message.sender.asSender() ?: snapshot.messagingUserName.asSender()
            val timestamp = when {
                message.timestampMs > 0 -> message.timestampMs
                else -> maxOf(snapshot.whenMs, snapshot.postTimeMs)
            }
            val kind = detectKind(body, hasPicture = snapshot.hasPicture && index == lastIndex)
            ParsedMessage(
                dedupeKey = styleKey(chatName, sender, timestamp, body),
                chatName = chatName,
                senderName = sender,
                kind = kind,
                text = body.ifEmpty { null },
                timestampMs = timestamp,
                reliableTime = message.timestampMs > 0 || snapshot.whenMs > 0,
            )
        }
    }

    private fun parseFlat(snapshot: NotificationSnapshot, chatName: String): ParsedMessage? {
        val raw = (snapshot.bigText ?: snapshot.text)?.trim().orEmpty()
        val title = snapshot.title ?: chatName
        val (sender, body) = stripSenderPrefix(title, raw)
        val kind = detectKind(body, hasPicture = snapshot.hasPicture)
        val text = body.ifEmpty { null }
        if (text == null && kind == MessageKind.UNKNOWN) return null

        val timestamp = if (snapshot.whenMs > 0) snapshot.whenMs else snapshot.postTimeMs
        return ParsedMessage(
            dedupeKey = flatKey(snapshot, timestamp, body),
            chatName = chatName,
            senderName = sender,
            kind = kind,
            text = text,
            timestampMs = timestamp,
            reliableTime = snapshot.whenMs > 0,
        )
    }

    /** Splits "Jane: hello" into sender + body when the prefix names the chat. */
    private fun stripSenderPrefix(title: String, body: String): Pair<String?, String> {
        if (body.isEmpty()) return null to body
        val idx = body.indexOf(':')
        if (idx <= 0 || idx > 60) return null to body
        val prefix = body.substring(0, idx).trim()
        if (prefix.isEmpty() || prefix.any { it.isDigit() }) return null to body
        val matchesTitle = prefix.equals(title, ignoreCase = true) ||
            title.startsWith(prefix, ignoreCase = true) ||
            prefix.startsWith(title, ignoreCase = true)
        if (!matchesTitle) return null to body
        return prefix to body.substring(idx + 1).trim()
    }

    private fun senderPrefixOf(body: String): String? {
        val idx = body.indexOf(':')
        if (idx <= 0 || idx > 60) return null
        val prefix = body.substring(0, idx).trim()
        return prefix.takeIf { it.isNotEmpty() && !it.any { c -> c.isDigit() } }
    }

    private fun detectKind(text: String, hasPicture: Boolean): MessageKind {
        if (hasPicture) return MessageKind.PHOTO
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return MessageKind.UNKNOWN
        if (STORY_PATTERN.containsMatchIn(normalized)) return MessageKind.STORY_MENTION
        return when (normalized) {
            in VOICE_PHRASES -> MessageKind.VOICE_NOTE
            in PHOTO_PHRASES -> MessageKind.PHOTO
            in VIDEO_PHRASES -> MessageKind.VIDEO
            in REEL_PHRASES -> MessageKind.REEL
            else -> MessageKind.TEXT
        }
    }

    private fun styleKey(chat: String, sender: String?, timestampMs: Long, body: String): String =
        "style|$chat|${sender.orEmpty()}|$timestampMs|${normalize(body)}"

    private fun flatKey(snapshot: NotificationSnapshot, timestampMs: Long, body: String): String =
        "flat|${snapshot.key}|$timestampMs|${normalize(body)}"

    /** Trim + collapse whitespace so cosmetic re-posts do not read twice. */
    fun normalize(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    /** True when a MessagingStyle entry was written by us rather than the other person. */
    private fun isOwnMessage(sender: String?, selfDisplayName: String?): Boolean {
        val self = selfDisplayName?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        val from = sender?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return from.equals(self, ignoreCase = true)
    }

    private fun String?.asChatName(): String? {
        val value = this?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.lowercase() in APP_TITLES) return null
        return value.trimEnd(':').trim().takeIf { it.isNotEmpty() }
    }

    private fun String?.asSender(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
