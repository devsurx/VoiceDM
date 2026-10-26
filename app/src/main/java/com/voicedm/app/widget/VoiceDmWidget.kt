package com.voicedm.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.voicedm.app.MainActivity
import com.voicedm.app.R
import com.voicedm.app.VoiceDmApp
import com.voicedm.app.data.AppContainer
import com.voicedm.app.domain.model.ConversationUiState
import com.voicedm.app.domain.model.displayContactName
import com.voicedm.app.service.VoiceDmService

/**
 * Home-screen toggle: shows who we are talking to plus the current phase, and one tap
 * starts or stops the loop.
 *
 * Widgets cannot observe flows, so [push] is called by the application whenever the
 * conversation state changes (see `VoiceDmApp.onCreate`).
 */
class VoiceDmWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val container = runCatching { VoiceDmApp.from(context).container }.getOrNull()
        val state = container?.conversation?.state?.value ?: ConversationUiState()
        val ready = container?.let(::isReady) ?: false
        val views = buildViews(context, state, ready, container)
        appWidgetIds.forEach { id -> appWidgetManager.updateAppWidget(id, views) }
    }

    companion object {

        fun push(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, VoiceDmWidget::class.java))
            if (ids.isEmpty()) return
            val container = runCatching { VoiceDmApp.from(context).container }.getOrNull() ?: return
            val views = buildViews(
                context,
                container.conversation.state.value,
                isReady(container),
                container,
            )
            ids.forEach { id -> manager.updateAppWidget(id, views) }
        }

        private fun isReady(container: AppContainer): Boolean =
            container.permissions.notificationAccessEnabled() &&
                container.permissions.microphoneGranted() &&
                container.cachedSettings.targetChatName.isNotBlank()

        private fun buildViews(
            context: Context,
            state: ConversationUiState,
            ready: Boolean,
            container: AppContainer?,
        ): RemoteViews {
            val running = VoiceDmService.running || state.isRunning
            val displayName = container?.cachedSettings?.displayContactName().orEmpty()
            return RemoteViews(context.packageName, R.layout.widget_voicedm).apply {
                setTextViewText(
                    R.id.widget_title,
                    displayName.ifBlank { context.getString(R.string.widget_title_idle) },
                )
                setTextViewText(
                    R.id.widget_status,
                    when {
                        running -> context.getString(R.string.widget_status_running)
                        !ready -> context.getString(R.string.main_open_setup)
                        else -> context.getString(R.string.widget_status_off)
                    },
                )
                setTextViewText(
                    R.id.widget_toggle,
                    context.getString(if (running) R.string.widget_stop else R.string.widget_start),
                )
                setOnClickPendingIntent(
                    R.id.widget_root,
                    toggleIntent(context, running, ready),
                )
            }
        }

        /**
         * Stopping talks to the already-running foreground service directly (allowed from
         * the background). Starting routes through the activity, because Android does not
         * let a widget start a foreground service from the background.
         */
        private fun toggleIntent(context: Context, running: Boolean, ready: Boolean): PendingIntent {
            val stopIntent = Intent(context, VoiceDmService::class.java)
                .setAction(VoiceDmService.ACTION_STOP)
            val activityIntent = Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_AUTO_START, ready && !running)

            return if (running) {
                PendingIntent.getService(
                    context,
                    REQUEST_STOP,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getActivity(
                    context,
                    REQUEST_START,
                    activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        }

        private const val REQUEST_STOP = 1001
        private const val REQUEST_START = 1002
    }
}
