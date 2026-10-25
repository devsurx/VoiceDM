package com.voicedm.app.service.reply

/** What happened when we tried to hand a reply back to Instagram. */
sealed interface SendResult {
    data object Sent : SendResult

    /** The notification for that chat is gone (cleared, expired, or never seen). */
    data object NoNotification : SendResult

    /** The notification carries no inline-reply action. */
    data object NoReplyAction : SendResult

    data class Failed(val reason: String) : SendResult
}

/** Mode A: deliver a transcript through the notification's inline reply. */
fun interface TextReplyChannel {
    suspend fun send(chatName: String, text: String): SendResult
}

/** Mode B: record a real voice note inside Instagram via accessibility. */
fun interface VoiceNoteChannel {
    suspend fun recordAndSend(chatName: String, holdMs: Long): SendResult
}
