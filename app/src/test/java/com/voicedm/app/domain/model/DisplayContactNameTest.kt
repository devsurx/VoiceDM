package com.voicedm.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The nickname only ever affects *display*: one pure resolver guarantees the title,
 * notification, widget, tile and TTS fallback all show the exact same name, and a blank
 * nickname can never hide the real chat name.
 */
class DisplayContactNameTest {

    @Test
    fun `nickname wins whenever one is set`() {
        val s = AppSettings(targetChatName = "Sarah 🌸", contactNickname = "Mum")
        assertEquals("Mum", s.displayContactName())
    }

    @Test
    fun `blank or whitespace nickname falls back to the chat name`() {
        assertEquals(
            "Sarah 🌸",
            AppSettings(targetChatName = "Sarah 🌸", contactNickname = "").displayContactName(),
        )
        assertEquals(
            "Sarah 🌸",
            AppSettings(targetChatName = "  Sarah 🌸 ", contactNickname = "   ").displayContactName(),
        )
    }

    @Test
    fun `nickname is trimmed but keeps internal spaces`() {
        val s = AppSettings(targetChatName = "chat", contactNickname = "  Project X  ")
        assertEquals("Project X", s.displayContactName())
    }

    @Test
    fun `nothing configured resolves to empty, not a placeholder`() {
        assertEquals("", AppSettings().displayContactName())
    }
}
