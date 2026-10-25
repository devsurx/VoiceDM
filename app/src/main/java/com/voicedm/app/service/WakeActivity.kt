package com.voicedm.app.service

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager

/**
 * Turns the screen on for Mode B and hands control straight to Instagram.
 *
 * Android does not let a background service wake the display, but an activity launched by
 * an accessibility service may. This one is transparent, keeps the display awake while the
 * voice note is being recorded, then gets out of the way.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
        )
        setContentView(android.widget.FrameLayout(this))

        openInstagramInbox()
        finishLatch = this
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed({ if (finishLatch === this) finish() }, MAX_VISIBLE_MS)
    }

    override fun onDestroy() {
        if (finishLatch === this) finishLatch = null
        super.onDestroy()
    }

    private fun openInstagramInbox() {
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct/inbox"))
            .setPackage(INSTAGRAM_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(deepLink) }.recoverCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/direct/inbox/"))
                    .setPackage(INSTAGRAM_PACKAGE),
            )
        }
    }

    private companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val MAX_VISIBLE_MS = 60_000L

        @Volatile
        var finishLatch: WakeActivity? = null
    }
}
