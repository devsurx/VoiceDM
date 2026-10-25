package com.voicedm.app.service.reply

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.voicedm.app.data.ActiveNotificationStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mode A sender: fills the notification's RemoteInput and fires the reply PendingIntent.
 *
 * This is the same code path Android's own "Reply" button uses, so Instagram just sees a
 * normal inline reply - no automation, no unofficial API, nothing that depends on
 * Instagram's UI layout.
 */
class InlineReplySender(
    private val context: Context,
    private val store: ActiveNotificationStore,
) : TextReplyChannel {

    override suspend fun send(chatName: String, text: String): SendResult =
        withContext(Dispatchers.Main.immediate) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext SendResult.Failed("Nothing to send")

            val notification = store.latestFor(chatName)?.notification
                ?: return@withContext SendResult.NoNotification

            val action = resolveReplyAction(notification)
                ?: return@withContext SendResult.NoReplyAction

            val inputs = action.remoteInputs.orEmpty().filter(::canCarryText).toTypedArray()
            if (inputs.isEmpty()) return@withContext SendResult.NoReplyAction

            val results = Bundle()
            inputs.forEach { input -> results.putCharSequence(input.resultKey, trimmed) }

            val fillIn = Intent()
            RemoteInput.addResultsToIntent(inputs, fillIn, results)

            try {
                action.actionIntent.send(context, 0, fillIn)
                SendResult.Sent
            } catch (e: PendingIntent.CanceledException) {
                SendResult.Failed("The reply action expired, open the chat once")
            } catch (e: SecurityException) {
                SendResult.Failed(e.message ?: "Not allowed to reply")
            } catch (e: RuntimeException) {
                SendResult.Failed(e.message ?: "Reply failed")
            }
        }

    private fun resolveReplyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions ?: return null
        val candidates = actions.filter {
            !it.remoteInputs.isNullOrEmpty() && it.actionIntent != null
        }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { it.title.toString().equals("Reply", ignoreCase = true) }
            ?: candidates.firstOrNull {
                it.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY
            }
            ?: candidates.first()
    }

    private fun canCarryText(input: RemoteInput): Boolean =
        !input.isDataOnly && input.allowFreeFormInput && !input.resultKey.isNullOrEmpty()
}
