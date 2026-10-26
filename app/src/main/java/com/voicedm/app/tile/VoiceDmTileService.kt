package com.voicedm.app.tile

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.voicedm.app.MainActivity
import com.voicedm.app.R
import com.voicedm.app.VoiceDmApp
import com.voicedm.app.domain.model.displayContactName
import com.voicedm.app.service.VoiceDmService

/**
 * Quick-settings tile: pull down, one tap to start or stop the conversation.
 *
 * The tile only reads state synchronously, so the application asks Android to re-query it
 * (TileService.requestListeningState) whenever the loop changes phase.
 */
class VoiceDmTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val container = runCatching { VoiceDmApp.from(this).container }.getOrNull()
        when {
            VoiceDmService.running -> VoiceDmService.stop(this)

            container != null && isReady(container) -> VoiceDmService.start(this)

            else -> openApp()
        }
        refresh()
    }

    /** Nothing is configured yet: hand the user to setup instead of failing silently. */
    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pending = PendingIntent.getActivity(
                    this,
                    0,
                    launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                startActivityAndCollapse(pending)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(launch)
            }
        }
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val container = runCatching { VoiceDmApp.from(this).container }.getOrNull()
        val state = container?.conversation?.state?.value
        val running = VoiceDmService.running || state?.isRunning == true
        tile.label = getString(R.string.tile_label)
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (running) {
                container?.cachedSettings?.displayContactName()?.takeIf { it.isNotBlank() }
            } else {
                null
            }
        }
        runCatching { tile.updateTile() }
    }

    private fun isReady(container: com.voicedm.app.data.AppContainer): Boolean =
        container.permissions.notificationAccessEnabled() &&
            container.permissions.microphoneGranted() &&
            container.cachedSettings.targetChatName.isNotBlank()

    companion object {
        fun requestRefresh(context: Context) {
            runCatching {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, VoiceDmTileService::class.java),
                )
            }
        }
    }
}
