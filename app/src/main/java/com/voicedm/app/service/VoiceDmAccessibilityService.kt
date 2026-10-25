package com.voicedm.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.app.KeyguardManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.voicedm.app.service.reply.SendResult
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Mode B: drives Instagram itself so your reply goes out as a real voice note.
 *
 * The whole flow is defensive on purpose - every lookup has several strategies and every
 * timeout turns into a [SendResult.Failed] with a human message instead of hanging the
 * conversation loop. Mode A never touches this class.
 */
class VoiceDmAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "VoiceDM accessibility service connected")
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /**
     * Opens [chatName] in Instagram, holds the record button for [holdMs] while you speak,
     * then releases it so Instagram sends the voice note.
     */
    suspend fun recordVoiceNote(chatName: String, holdMs: Long): SendResult =
        withContext(Dispatchers.Main.immediate) {
            try {
                ensureAwake()?.let { return@withContext it }

                if (!ensureChatOpen(chatName)) {
                    return@withContext SendResult.Failed("Could not open that chat in Instagram")
                }

                val button = awaitRecordButton(RECORD_TIMEOUT_MS)
                    ?: return@withContext SendResult.Failed("Instagram's record button was not found")

                val bounds = Rect()
                button.getBoundsInScreen(bounds)
                if (bounds.isEmpty) {
                    return@withContext SendResult.Failed("The record button had no size")
                }

                val duration = holdMs
                    .coerceIn(MIN_HOLD_MS, GestureDescription.getMaxGestureDuration() - 50L)
                val held = performHold(bounds, duration)
                if (!held) {
                    return@withContext SendResult.Failed("Instagram refused the record gesture")
                }
                SendResult.Sent
            } catch (t: Throwable) {
                Log.w(TAG, "voice note automation failed", t)
                SendResult.Failed(t.message ?: "Automation failed")
            }
        }

    /** Returns null when the device is ready, otherwise the reason we cannot proceed. */
    private suspend fun ensureAwake(): SendResult? {
        val power = getSystemService(PowerManager::class.java)
        if (!power.isInteractive) {
            runCatching {
                startActivity(
                    Intent(this, WakeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            var waited = 0L
            while (waited < WAKE_TIMEOUT_MS && !power.isInteractive) {
                delay(POLL_INTERVAL_MS)
                waited += POLL_INTERVAL_MS
            }
            if (!power.isInteractive) {
                return SendResult.Failed("Turn your screen on to send a voice note")
            }
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isDeviceLocked == true) {
            return SendResult.Failed("Unlock your phone to send a voice note")
        }
        return null
    }

    private suspend fun ensureChatOpen(chatName: String): Boolean {
        if (recordButton(chatName) != null && containsName(chatName)) return true

        openInbox()

        // 1. Find the row for that person in the inbox and tap it.
        val row = awaitNode(OPEN_CHAT_TIMEOUT_MS) { root -> findChatRow(root, chatName) }
        if (row == null) {
            // Maybe we were already inside that conversation.
            return recordButton(chatName) != null
        }
        row.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        delay(CLICK_SETTLE_MS)

        // 2. Wait until the conversation (and its record button) is actually on screen.
        return awaitBoolean(CONVERSATION_TIMEOUT_MS) { root ->
            findRecordButton(root) != null && (containsName(chatName) || clickedRecently())
        }
    }

    private fun openInbox() {
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("instagram://direct/inbox"))
            .setPackage(INSTAGRAM_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(deepLink) }.recoverCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/direct/inbox/"))
                    .setPackage(INSTAGRAM_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "could not open Instagram inbox", it) }
    }

    private fun recordButton(chatName: String): AccessibilityNodeInfo? {
        val root = root() ?: return null
        if (!containsName(chatName)) return null
        return findRecordButton(root)
    }

    private suspend fun awaitRecordButton(timeoutMs: Long): AccessibilityNodeInfo? =
        awaitNode(timeoutMs) { root -> findRecordButton(root) }

    private suspend fun awaitNode(
        timeoutMs: Long,
        finder: (AccessibilityNodeInfo) -> AccessibilityNodeInfo?,
    ): AccessibilityNodeInfo? {
        var waited = 0L
        while (waited < timeoutMs) {
            val root = root() ?: run { delay(POLL_INTERVAL_MS); waited += POLL_INTERVAL_MS; continue }
            finder(root)?.let { return it }
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return null
    }

    private suspend fun awaitBoolean(
        timeoutMs: Long,
        predicate: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            val root = root()
            if (root != null && predicate(root)) return true
            delay(POLL_INTERVAL_MS)
            waited += POLL_INTERVAL_MS
        }
        return false
    }

    private fun root(): AccessibilityNodeInfo? {
        rootInActiveWindow?.let { return it }
        return runCatching { getWindows()?.firstNotNullOfOrNull { it.root } }.getOrNull()
    }

    private fun containsName(chatName: String): Boolean {
        val root = root() ?: return false
        val wanted = normalize(chatName)
        return walk(root) { node ->
            val text = normalize(node.text?.toString().orEmpty())
            val desc = normalize(node.contentDescription?.toString().orEmpty())
            text.contains(wanted) || desc.contains(wanted)
        }
    }

    private var lastClickAtMs = 0L

    private fun clickedRecently(): Boolean =
        System.currentTimeMillis() - lastClickAtMs < CLICK_REMEMBER_MS

    // ---------------------------------------------------------------- node lookup

    private fun findChatRow(
        root: AccessibilityNodeInfo,
        chatName: String,
    ): AccessibilityNodeInfo? {
        val wanted = normalize(chatName)
        var exact: AccessibilityNodeInfo? = null
        var prefix: AccessibilityNodeInfo? = null
        walk(root) { node ->
            val text = normalize(node.text?.toString().orEmpty())
            val desc = normalize(node.contentDescription?.toString().orEmpty())
            val candidates = listOf(text, desc).filter { it.isNotEmpty() }
            if (candidates.any { it == wanted }) {
                if (exact == null && node.isClickable) exact = node
            } else if (candidates.any { it.startsWith(wanted) }) {
                if (prefix == null && node.isClickable) prefix = node
            }
            false
        }
        return exact ?: prefix
    }

    private fun findRecordButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // 1. View ids - fastest and least ambiguous when they still match.
        for (id in RECORD_VIEW_IDS) {
            runCatching { root.findAccessibilityNodeInfosByViewId(id) }
                .getOrNull()
                ?.firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }

        // 2. Content description keywords, then accessible text.
        var byDescription: AccessibilityNodeInfo? = null
        var byId: AccessibilityNodeInfo? = null
        walk(root) { node ->
            if (byDescription == null) {
                val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
                if (desc.isNotEmpty() && RECORD_DESCRIPTIONS.any { desc.contains(it) }) {
                    byDescription = node
                }
            }
            if (byId == null) {
                val viewId = node.viewIdResourceName.orEmpty()
                if (viewId.isNotEmpty() && RECORD_VIEW_ID_SUFFIXES.any { viewId.endsWith(it) }) {
                    byId = node
                }
            }
            byDescription != null && byId != null
        }
        return byDescription ?: byId
    }

    /** Depth-first walk with a node budget so a huge list view cannot stall the service. */
    private fun walk(
        node: AccessibilityNodeInfo,
        budget: Int = MAX_NODES,
        visitor: (AccessibilityNodeInfo) -> Boolean,
    ): Boolean {
        var visited = 0

        fun visit(current: AccessibilityNodeInfo): Boolean {
            if (++visited > budget) return true
            if (visitor(current)) return true
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                if (visit(child)) return true
            }
            return false
        }
        return visit(node)
    }

    // ---------------------------------------------------------------- gesture

    private suspend fun performHold(bounds: Rect, durationMs: Long): Boolean {
        val path = Path().apply { moveTo(bounds.exactCenterX(), bounds.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        return suspendCancellableCoroutine { continuation ->
            val dispatched = dispatchGesture(
                gesture,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null,
            )
            if (!dispatched && continuation.isActive) continuation.resume(false)
        }
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(WHITESPACE, " ")

    companion object {
        @Volatile
        var instance: VoiceDmAccessibilityService? = null
            private set

        const val TAG = "VoiceDM"
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
        const val POLL_INTERVAL_MS = 200L
        const val OPEN_CHAT_TIMEOUT_MS = 8_000L
        const val CONVERSATION_TIMEOUT_MS = 8_000L
        const val RECORD_TIMEOUT_MS = 6_000L
        const val WAKE_TIMEOUT_MS = 5_000L
        const val CLICK_SETTLE_MS = 900L
        const val CLICK_REMEMBER_MS = 6_000L
        const val MIN_HOLD_MS = 1_500L
        const val MAX_NODES = 600

        val WHITESPACE = Regex("\\s+")

        /** View ids used by Instagram builds so far (oldest to newest). */
        val RECORD_VIEW_IDS = listOf(
            "com.instagram.android:id/voice_message_record_button",
            "com.instagram.android:id/record_button",
            "com.instagram.android:id/microphone_button",
            "com.instagram.android:id/mic_button",
            "com.instagram.android:id/voice_message_mic",
            "com.instagram.android:id/composer_mic",
            "com.instagram.android:id/action_mic",
            "com.instagram.android:id/record_voice_button",
        )

        val RECORD_VIEW_ID_SUFFIXES = listOf(
            ":id/voice_message_record_button",
            ":id/record_button",
            ":id/microphone_button",
            ":id/mic_button",
            ":id/voice_message_mic",
            ":id/composer_mic",
            ":id/action_mic",
        )

        /** Content descriptions Instagram uses for the hold-to-record control. */
        val RECORD_DESCRIPTIONS = listOf(
            "record voice message",
            "voice message",
            "hold to record",
            "hold to talk",
            "record audio",
            "start recording",
            "microphone",
            "record voice",
        )
    }
}
