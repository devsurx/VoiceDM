package com.voicedm.app.domain.parse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMatcherTest {

    @Test
    fun `exact names match`() {
        assertTrue(ChatMatcher.matches("Jane Doe", "Jane Doe"))
    }

    @Test
    fun `case and whitespace differences still match`() {
        assertTrue(ChatMatcher.matches("  jane   DOE ", "Jane Doe"))
    }

    @Test
    fun `trailing colon in the notification title is ignored`() {
        assertTrue(ChatMatcher.matches("Jane Doe", "Jane Doe:"))
        assertTrue(ChatMatcher.matches("Jane Doe:", "Jane Doe"))
    }

    @Test
    fun `zero width characters are stripped`() {
        assertTrue(ChatMatcher.matches("Jane​ Doe", "Jane Doe"))
    }

    @Test
    fun `different people do not match`() {
        assertFalse(ChatMatcher.matches("Jon", "Jonathan"))
        assertFalse(ChatMatcher.matches("Jonathan", "Jon"))
    }

    @Test
    fun `empty target never matches`() {
        assertFalse(ChatMatcher.matches("", "Jane Doe"))
        assertFalse(ChatMatcher.matches("   ", "Jane Doe"))
    }

    @Test
    fun `empty actual never matches`() {
        assertFalse(ChatMatcher.matches("Jane Doe", ""))
    }

    @Test
    fun `normalize is idempotent`() {
        val value = " Jane  Doe : "
        val once = ChatMatcher.normalize(value)
        assertEquals(once, ChatMatcher.normalize(once))
        assertEquals("jane doe", once)
    }
}
