package com.voicedm.app.service.reply

import com.voicedm.app.service.VoiceDmAccessibilityService

/**
 * Mode B sender: delegates to the accessibility service, which drives Instagram itself.
 *
 * Nothing here runs unless the user turned both "Mode B" and the accessibility service on,
 * so Mode A keeps working with the accessibility permission revoked.
 */
class VoiceNoteSender : VoiceNoteChannel {

    override suspend fun recordAndSend(chatName: String, holdMs: Long): SendResult {
        val service = VoiceDmAccessibilityService.instance
            ?: return SendResult.Failed("Voice-note mode needs the accessibility service turned on")
        return service.recordVoiceNote(chatName, holdMs)
    }
}
