package com.voicedm.app.domain.parse

import com.voicedm.app.domain.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDeduperTest {

    private var now = 0L

    private fun message(
        key: String = "k",
        chat: String = "Jane Doe",
        text: String? = "hello",
        reliableTime: Boolean = true,
        kind: MessageKind = MessageKind.TEXT,
    ) = ParsedMessage(
        dedupeKey = key,
        chatName = chat,
        senderName = "Jane Doe",
        kind = kind,
        text = text,
        timestampMs = now,
        reliableTime = reliableTime,
    )

    @Test
    fun `first sighting is processed and remembered`() {
        val deduper = MessageDeduper(clock = { now })
        assertTrue(deduper.shouldProcess(message(key = "a")))
        assertFalse(deduper.shouldProcess(message(key = "a")))
        assertEquals(1, deduper.size())
    }

    @Test
    fun `different keys are processed independently`() {
        val deduper = MessageDeduper(clock = { now })
        assertTrue(deduper.shouldProcess(message(key = "a")))
        assertTrue(deduper.shouldProcess(message(key = "b")))
        assertEquals(2, deduper.size())
    }

    @Test
    fun `re-posted unreliable message is caught by the recent window`() {
        now = 1_000L
        val deduper = MessageDeduper(clock = { now })
        // Instagram bumped the post time, so the key changed but the body did not.
        assertTrue(deduper.shouldProcess(message(key = "v1", reliableTime = false)))
        now = 4_000L
        assertFalse(deduper.shouldProcess(message(key = "v2", reliableTime = false)))
    }

    @Test
    fun `unreliable message is read again once the window has expired`() {
        now = 1_000L
        val deduper = MessageDeduper(clock = { now })
        assertTrue(deduper.shouldProcess(message(key = "v1", reliableTime = false)))
        now = 1_000L + MessageDeduper.DEFAULT_RECENT_WINDOW_MS + 1
        assertTrue(deduper.shouldProcess(message(key = "v2", reliableTime = false)))
    }

    @Test
    fun `reliable message outside its exact key is not blocked by the window`() {
        now = 1_000L
        val deduper = MessageDeduper(clock = { now })
        assertTrue(deduper.shouldProcess(message(key = "a", reliableTime = true)))
        now = 2_000L
        // Same body, different (reliable) timestamp: a genuinely new message.
        assertTrue(deduper.shouldProcess(message(key = "b", reliableTime = true)))
    }

    @Test
    fun `same text in another chat is not treated as a duplicate`() {
        now = 1_000L
        val deduper = MessageDeduper(clock = { now })
        assertTrue(deduper.shouldProcess(message(key = "a", chat = "Jane", reliableTime = false)))
        now = 2_000L
        assertTrue(
            deduper.shouldProcess(
                message(key = "b", chat = "Bob", text = "hello", reliableTime = false),
            ),
        )
    }

    @Test
    fun `capacity evicts the oldest key`() {
        val deduper = MessageDeduper(capacity = 2, clock = { now })
        assertTrue(deduper.shouldProcess(message(key = "a")))
        assertTrue(deduper.shouldProcess(message(key = "b")))
        assertTrue(deduper.shouldProcess(message(key = "c")))
        assertEquals(2, deduper.size())
        // "a" was evicted, so an identical key is accepted again.
        assertTrue(deduper.shouldProcess(message(key = "a")))
    }

    @Test
    fun `forgetAll resets both tiers`() {
        now = 1_000L
        val deduper = MessageDeduper(clock = { now })
        assertTrue(deduper.shouldProcess(message(key = "a", reliableTime = false)))
        deduper.forgetAll()
        assertEquals(0, deduper.size())
        assertTrue(deduper.shouldProcess(message(key = "a", reliableTime = false)))
    }

    @Test
    fun `media message without body still dedupes by kind`() {
        now = 1_000L
        val deduper = MessageDeduper(clock = { now })
        assertTrue(
            deduper.shouldProcess(
                message(key = "a", text = null, kind = MessageKind.VOICE_NOTE),
            ),
        )
        now = 2_000L
        assertFalse(
            deduper.shouldProcess(
                message(key = "b", text = null, kind = MessageKind.VOICE_NOTE, reliableTime = false),
            ),
        )
    }
}
