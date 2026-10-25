package com.voicedm.app.domain.reply

import com.voicedm.app.domain.model.IncomingMessage
import com.voicedm.app.domain.model.MessageKind
import com.voicedm.app.domain.parse.NotificationParser

/**
 * Drops messages that are just our own reply coming back to us.
 *
 * When Instagram updates the notification after we answer, the MessagingStyle list can
 * include the text we sent. Reading it aloud would start a feedback loop, so anything
 * matching a very recently sent transcript is ignored.
 */
class EchoGuard(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Sent(val text: String, val timeMs: Long)

    private val sent = ArrayDeque<Sent>()

    fun rememberSent(text: String) {
        val normalized = NotificationParser.normalize(text)
        if (normalized.isEmpty()) return
        prune(clock())
        sent.addLast(Sent(normalized, clock()))
        while (sent.size > capacity) sent.removeFirst()
    }

    fun isEcho(message: IncomingMessage): Boolean {
        val kind = message.message.kind
        if (kind != MessageKind.TEXT && kind != MessageKind.UNKNOWN) return false
        val body = message.message.text?.let(NotificationParser::normalize).orEmpty()
        if (body.isEmpty()) return false
        val now = clock()
        prune(now)
        return sent.any { it.text.equals(body, ignoreCase = true) && now - it.timeMs <= windowMs }
    }

    private fun prune(nowMs: Long) {
        while (sent.isNotEmpty() && nowMs - sent.first().timeMs > windowMs) sent.removeFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 60_000L
        const val DEFAULT_CAPACITY = 8
    }
}
