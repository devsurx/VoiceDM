package com.voicedm.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voicedm.app.data.AppContainer
import com.voicedm.app.domain.model.AppSettings
import com.voicedm.app.service.VoiceDmService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Snapshot of everything the main screen needs to decide what it can offer the user. */
data class MainUiState(
    val chatName: String = "",
    val canStart: Boolean = false,
    val instagramInstalled: Boolean = true,
    val batteryExempt: Boolean = true,
    val onboardingDone: Boolean = false,
)

/**
 * Backs the main screen: exposes the live conversation state owned by the service plus the
 * small amount of permission/settings state needed to enable the big Start button.
 *
 * Permissions are re-read whenever [refresh] runs (the activity calls it on every resume),
 * because notification access and the accessibility toggle are granted in other apps.
 */
class MainViewModel(private val container: AppContainer) : ViewModel() {

    val conversation = container.conversation.state

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val permissionEpoch = kotlinx.coroutines.flow.MutableStateFlow(0)

    val ui: StateFlow<MainUiState> =
        combine(container.settings.settings, permissionEpoch) { settings, _ ->
            buildUi(settings)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    init {
        refresh()
    }

    /** Re-reads permissions; call whenever the app returns to the foreground. */
    fun refresh() {
        permissionEpoch.value++
    }

    private fun buildUi(settings: AppSettings): MainUiState {
        val permissions = container.permissions
        val notificationAccess = permissions.notificationAccessEnabled()
        val microphone = permissions.microphoneGranted()
        val configured = settings.targetChatName.isNotBlank()
        return MainUiState(
            chatName = settings.targetChatName,
            instagramInstalled = permissions.instagramInstalled(),
            batteryExempt = permissions.batteryOptimizationExempt(),
            canStart = notificationAccess && microphone && configured &&
                permissions.instagramInstalled(),
            onboardingDone = notificationAccess && microphone && configured,
        )
    }

    fun toggle(context: Context) {
        if (VoiceDmService.running) {
            VoiceDmService.stop(context)
        } else if (ui.value.canStart) {
            VoiceDmService.start(context)
        } else {
            refresh()
        }
    }

    fun listenNow(context: Context) = VoiceDmService.listen(context)
    fun skip(context: Context) = VoiceDmService.skip(context)
    fun repeat(context: Context) = VoiceDmService.repeat(context)
    fun send(context: Context) = VoiceDmService.send(context)
    fun discard(context: Context) = VoiceDmService.discard(context)

    /**
     * Opens the battery-optimisation list so the user can exempt VoiceDM.
     *
     * Uses the plain settings page rather than ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS:
     * that intent requires a restricted permission whose only job is to show one dialog, and
     * declaring it is a common trigger for install-time permission warnings. No permission
     * is needed to open the settings page or to *read* whether we're already exempt.
     */
    fun requestBatteryExemption(context: Context) {
        val opened = runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            true
        }.getOrDefault(false)
        if (!opened) {
            // OEM fallback: the app's own details page, where the toggle lives on some skins.
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            }
        }
        refresh()
    }

    fun clearLog() = container.conversation.clearLog()
}
