package com.voicedm.app.data

import android.content.Context
import com.voicedm.app.domain.model.AppSettings
import com.voicedm.app.domain.reply.EchoGuard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency container: one place that owns the process-wide singletons so
 * services, receivers and ViewModels all talk to the same instances without pulling in a
 * DI framework.
 */
class AppContainer(private val context: Context) {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings = SettingsRepository(context)
    val permissions = PermissionChecker(context)
    val discoveredChats = DiscoveredChatsRepository(context, appScope)
    val conversation = ConversationStore()
    val activeNotifications = ActiveNotificationStore()
    val pipeline = MessagePipeline()
    val echoGuard = EchoGuard()

    /**
     * Last settings snapshot, readable without suspending - the widget and quick-settings
     * tile need a synchronous answer and DataStore is coroutine-only.
     */
    @Volatile
    var cachedSettings = AppSettings()
        private set

    init {
        appScope.launch {
            settings.settings.collect { cachedSettings = it }
        }
    }
}
