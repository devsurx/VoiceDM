package com.voicedm.app.domain.queue

import com.voicedm.app.domain.model.IncomingMessage

/**
 * Messages that arrived while the engine was off, waiting to be spoken on the next start.
 *
 * Bounded in both age and size so a phone that has been sitting with unread messages for
 * three days does not read them all back when the user taps Start.
 */
class PendingMessageQueue(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val capacity: Int = DEFAULT_CAPACITY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val items = ArrayDeque<IncomingMessage>()

    @Synchronized
    fun add(message: IncomingMessage) {
        items.addLast(message)
        while (items.size > capacity) items.removeFirst()
    }

    /**
     * Removes and returns everything newer than [oldestAllowedMs]. Anything older is stale
     * for good (the cut-off only ever moves forward) and is dropped.
     * Pass `now - ttl` to honour the catch-up window, or `now` to start fresh.
     */
    @Synchronized
    fun drain(oldestAllowedMs: Long): List<IncomingMessage> {
        val drained = mutableListOf<IncomingMessage>()
        while (items.isNotEmpty()) {
            val next = items.removeFirst()
            if (next.receivedAtMs >= oldestAllowedMs) drained += next
        }
        drained.sortBy { it.receivedAtMs }
        return drained
    }

    @Synchronized
    fun clear() = items.clear()

    @Synchronized
    fun size(): Int = items.size

    /** Cut-off timestamp for [drain] honouring the catch-up TTL. */
    fun oldestAllowed(catchUp: Boolean, nowMs: Long = clock()): Long =
        if (catchUp) nowMs - ttlMs else nowMs

    companion object {
        const val DEFAULT_TTL_MS = 10 * 60_000L
        const val DEFAULT_CAPACITY = 32
    }
}
