package com.voicedm.app.domain.parse

import com.voicedm.app.domain.model.MessageKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    private fun snapshot(
        title: String? = null,
        text: String? = null,
        bigText: String? = null,
        conversationTitle: String? = null,
        messagingUserName: String? = null,
        messages: List<StyleMessage> = emptyList(),
        selfDisplayName: String? = null,
        isGroupConversation: Boolean = false,
        threadId: String? = null,
        hasPicture: Boolean = false,
        isGroupSummary: Boolean = false,
        replyActions: List<ReplyActionInfo> = emptyList(),
        whenMs: Long = 0L,
        postTimeMs: Long = 1_000L,
        key: String = "key-1",
    ) = NotificationSnapshot(
        key = key,
        postTimeMs = postTimeMs,
        whenMs = whenMs,
        packageName = "com.instagram.android",
        title = title,
        text = text,
        bigText = bigText,
        conversationTitle = conversationTitle,
        messagingUserName = messagingUserName,
        messages = messages,
        hasPicture = hasPicture,
        selfDisplayName = selfDisplayName,
        isGroupConversation = isGroupConversation,
        threadId = threadId,
        isGroupSummary = isGroupSummary,
        replyActions = replyActions,
    )

    // ---------------------------------------------------------------- MessagingStyle

    @Test
    fun `messaging style yields one message per entry`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                messages = listOf(
                    StyleMessage("hey", "Jane Doe", 10_000L),
                    StyleMessage("are you free?", "Jane Doe", 11_000L),
                ),
            ),
        )

        assertNotNull(parsed)
        assertEquals("Jane Doe", parsed!!.chatName)
        assertEquals(2, parsed.messages.size)
        assertEquals("hey", parsed.messages[0].text)
        assertEquals("are you free?", parsed.messages[1].text)
        assertTrue(parsed.messages.all { it.reliableTime })
    }

    @Test
    fun `own messages are filtered out by self display name`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                selfDisplayName = "Me",
                messages = listOf(
                    StyleMessage("from me", "Me", 10_000L),
                    StyleMessage("from jane", "Jane Doe", 11_000L),
                ),
            ),
        )

        assertNotNull(parsed)
        assertEquals(1, parsed!!.messages.size)
        assertEquals("from jane", parsed.messages[0].text)
    }

    @Test
    fun `media placeholder is classified instead of read verbatim`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                messages = listOf(StyleMessage("sent a voice message", "Jane Doe", 5_000L)),
            ),
        )

        assertNotNull(parsed)
        assertEquals(MessageKind.VOICE_NOTE, parsed!!.messages[0].kind)
    }

    @Test
    fun `picture extra forces photo kind on the last message`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                hasPicture = true,
                messages = listOf(StyleMessage("", "Jane Doe", 5_000L)),
            ),
        )

        assertNotNull(parsed)
        assertEquals(MessageKind.PHOTO, parsed!!.messages[0].kind)
    }

    @Test
    fun `story mention is recognised`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                messages = listOf(
                    StyleMessage("mentioned you in their story", "Jane Doe", 5_000L),
                ),
            ),
        )

        assertNotNull(parsed)
        assertEquals(MessageKind.STORY_MENTION, parsed!!.messages[0].kind)
    }

    // ---------------------------------------------------------------- flat shape

    @Test
    fun `flat notification with sender prefix splits sender and body`() {
        val parsed = NotificationParser.parse(
            snapshot(title = "Jane Doe", text = "Jane Doe: see you at 8"),
        )

        assertNotNull(parsed)
        assertEquals("Jane Doe", parsed!!.chatName)
        assertEquals("Jane Doe", parsed.messages[0].senderName)
        assertEquals("see you at 8", parsed.messages[0].text)
    }

    @Test
    fun `flat notification without prefix uses title as chat`() {
        val parsed = NotificationParser.parse(
            snapshot(title = "Jane Doe", text = "hello there"),
        )

        assertNotNull(parsed)
        assertEquals("Jane Doe", parsed!!.chatName)
        assertEquals("hello there", parsed.messages[0].text)
        assertFalse(parsed.messages[0].reliableTime)
    }

    @Test
    fun `colon inside a normal message is not treated as a sender`() {
        val parsed = NotificationParser.parse(
            snapshot(title = "Jane Doe", text = "Jane Doe: the code is 10:30"),
        )

        assertNotNull(parsed)
        // The *first* colon still splits, and the remainder keeps the second one intact.
        assertEquals("the code is 10:30", parsed!!.messages[0].text)
    }

    @Test
    fun `instagram app title alone does not name a chat`() {
        val parsed = NotificationParser.parse(
            snapshot(title = "Instagram", text = "New message"),
        )

        assertNull(parsed)
    }

    @Test
    fun `group summary is ignored`() {
        val parsed = NotificationParser.parse(
            snapshot(
                title = "Jane Doe",
                text = "hi",
                isGroupSummary = true,
            ),
        )

        assertNull(parsed)
    }

    @Test
    fun `notification without any usable content is ignored`() {
        assertNull(NotificationParser.parse(snapshot(title = "Instagram", bigText = null, text = null)))
    }

    // ---------------------------------------------------------------- reply action

    @Test
    fun `first reply action index is carried into the parsed chat`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                messages = listOf(StyleMessage("yo", "Jane Doe", 1L)),
                replyActions = listOf(ReplyActionInfo(index = 2, title = "Reply", resultKeys = listOf("extra_text"))),
            ),
        )

        assertNotNull(parsed)
        assertEquals(2, parsed!!.replyActionIndex)
    }

    @Test
    fun `missing reply action is signalled as NO_REPLY_ACTION`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                messages = listOf(StyleMessage("yo", "Jane Doe", 1L)),
            ),
        )

        assertNotNull(parsed)
        assertEquals(ParsedChat.NO_REPLY_ACTION, parsed!!.replyActionIndex)
    }

    // ---------------------------------------------------------------- chat name

    @Test
    fun `conversation title wins over title`() {
        val name = NotificationParser.resolveChatName(
            snapshot(title = "Instagram", conversationTitle = "Jane Doe"),
        )
        assertEquals("Jane Doe", name)
    }

    @Test
    fun `trailing colon in the title is trimmed`() {
        val name = NotificationParser.resolveChatName(snapshot(title = "Jane Doe:"))
        assertEquals("Jane Doe", name)
    }

    @Test
    fun `own username in the conversation title loses to the other person`() {
        // Live shape of an Instagram 1:1: EXTRA_CONVERSATION_TITLE holds our own
        // handle while every message comes from the other side.
        val name = NotificationParser.resolveChatName(
            snapshot(
                title = "sr.x_l: shaurya",
                conversationTitle = "sr.x_l",
                selfDisplayName = "surx.",
                messagingUserName = "surx.",
                messages = listOf(
                    StyleMessage("Lwk", "shaurya", 10_000L),
                    StyleMessage("Tf is elvish yaadav doing", "shaurya", 11_000L),
                ),
            ),
        )
        assertEquals("shaurya", name)
    }

    @Test
    fun `own name in the title is skipped for a one to one chat`() {
        val name = NotificationParser.resolveChatName(
            snapshot(
                title = "surx.",
                text = "shaurya: hello there",
                selfDisplayName = "surx.",
            ),
        )
        assertEquals("shaurya", name)
    }

    @Test
    fun `group conversation is named by its title, not its members`() {
        val name = NotificationParser.resolveChatName(
            snapshot(
                conversationTitle = "Squad",
                selfDisplayName = "Me",
                isGroupConversation = true,
                messages = listOf(
                    StyleMessage("a", "Jane", 1L),
                    StyleMessage("b", "John", 2L),
                ),
            ),
        )
        assertEquals("Squad", name)
    }

    @Test
    fun `two distinct senders without the group flag still prefer the title`() {
        val name = NotificationParser.resolveChatName(
            snapshot(
                conversationTitle = "Squad",
                selfDisplayName = "Me",
                messages = listOf(
                    StyleMessage("a", "Jane", 1L),
                    StyleMessage("b", "John", 2L),
                ),
            ),
        )
        assertEquals("Squad", name)
    }

    @Test
    fun `one to one without any usable title falls back to the sender`() {
        val name = NotificationParser.resolveChatName(
            snapshot(
                title = "Instagram",
                selfDisplayName = "Me",
                messages = listOf(StyleMessage("hi", "shaurya", 1L)),
            ),
        )
        assertEquals("shaurya", name)
    }

    @Test
    fun `self only conversation falls back to its own title`() {
        val name = NotificationParser.resolveChatName(
            snapshot(
                conversationTitle = "surx.",
                title = "surx.",
                selfDisplayName = "surx.",
                messages = listOf(StyleMessage("note to self", "surx.", 1L)),
            ),
        )
        assertEquals("surx.", name)
    }

    @Test
    fun `thread id from the notification tag is carried into the parsed chat`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                threadId = "340282366841710301244259765539919755296",
                messages = listOf(StyleMessage("yo", "Jane Doe", 1L)),
            ),
        )

        assertNotNull(parsed)
        assertEquals("340282366841710301244259765539919755296", parsed!!.threadId)
    }

    @Test
    fun `dedupe keys differ between messages`() {
        val parsed = NotificationParser.parse(
            snapshot(
                conversationTitle = "Jane Doe",
                messages = listOf(
                    StyleMessage("one", "Jane Doe", 1L),
                    StyleMessage("two", "Jane Doe", 2L),
                ),
            ),
        )

        assertNotNull(parsed)
        assertTrue(parsed!!.messages[0].dedupeKey != parsed.messages[1].dedupeKey)
    }

    @Test
    fun `normalize collapses whitespace`() {
        assertEquals("a b c", NotificationParser.normalize("  a   b \n c "))
    }
}
