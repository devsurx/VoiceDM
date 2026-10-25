package com.voicedm.app.data

import com.voicedm.app.domain.model.IncomingMessage
import com.voicedm.app.domain.parse.MessageDeduper
import com.voicedm.app.domain.queue.PendingMessageQueue
import kotlinx.coroutines.channels.Channel

/**
 * The single funnel every candidate message goes through.
 *
 * Instagram may re-post a notification several times for one message, so this is where
 * dedupe happens; everything downstream (queue, engine, UI) only ever sees messages that
 * passed [MessageDeduper]. Work is handed to the engine through the bounded [pending]
 * queue plus a cheap "something arrived" signal, so nothing is lost while the engine is
 * busy speaking and nothing is processed twice.
 */
class MessagePipeline(
    private val deduper: MessageDeduper = MessageDeduper(),
    val pending: PendingMessageQueue = PendingMessageQueue(),
) {

    private val _signal = Channel<Unit>(Channel.UNLIMITED)

    /** Fires whenever [pending] has grown; the engine drains it after each signal. */
    val signal: Channel<Unit> = _signal

    /** Returns true when the message was new and has been queued for the engine. */
    fun publish(message: IncomingMessage): Boolean {
        if (!deduper.shouldProcess(message.message)) return false
        pending.add(message)
        _signal.trySend(Unit)
        return true
    }

    /** Called when the target chat changes so old dedupe state cannot hide new messages. */
    fun onTargetChanged() {
        deduper.forgetAll()
        pending.clear()
    }
}
