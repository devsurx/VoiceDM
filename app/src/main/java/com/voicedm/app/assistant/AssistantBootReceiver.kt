package com.voicedm.app.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.voicedm.app.VoiceDmApp
import kotlinx.coroutines.runBlocking

/**
 * Restarts the wake-word service after a reboot - but only when the user actually turned
 * the assistant on.
 *
 * Android allows a foreground service start from BOOT_COMPLETED; what the system may
 * still refuse is the *microphone* type while the app is in the background (Android 14+).
 * That refusal surfaces inside [AssistantService], which then posts a one-tap
 * "Resume listening" notification instead - exactly the "restart only if allowed,
 * otherwise notify" behaviour the spec asks for. We only catch failures here as a belt
 * and braces (some OEMs refuse the start outright).
 */
class AssistantBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        val container = VoiceDmApp.from(context).container
        val settings = runBlocking { container.settings.current() }
        if (!settings.assistantEnabled) return

        runCatching {
            AssistantService.start(context)
        }.onFailure {
            Log.w(TAG, "boot restart refused by the system", it)
        }
    }

    private companion object {
        const val TAG = "AssistantBoot"
    }
}
