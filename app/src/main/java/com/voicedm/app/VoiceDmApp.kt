package com.voicedm.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.voicedm.app.data.AppContainer
import com.voicedm.app.tile.VoiceDmTileService
import com.voicedm.app.widget.VoiceDmWidget
import kotlinx.coroutines.launch

class VoiceDmApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        container = AppContainer(this)
        observeConversationForWidgets()
    }

    /**
     * The widget and the quick-settings tile cannot observe flows themselves, so this
     * mirrors every conversation change out to them. Distinct-by-shape keeps the updates
     * cheap even though the log can change on every message.
     */
    private fun observeConversationForWidgets() {
        container.appScope.launch {
            var last: Triple<Boolean, com.voicedm.app.domain.model.ConversationPhase, String>? = null
            container.conversation.state.collect { state ->
                val key = Triple(state.isRunning, state.phase, state.chatName)
                if (key == last) return@collect
                last = key
                VoiceDmWidget.push(this@VoiceDmApp)
                VoiceDmTileService.requestRefresh(this@VoiceDmApp)
            }
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.channel_service_description)
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.channel_alerts_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = getString(R.string.channel_alerts_description)
            },
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "voicedm_service"
        const val CHANNEL_ALERTS = "voicedm_alerts"

        fun from(context: Context): VoiceDmApp = context.applicationContext as VoiceDmApp
    }
}
