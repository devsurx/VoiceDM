package com.voicedm.app.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.voicedm.app.VoiceDmApp
import com.voicedm.app.data.AppContainer
import com.voicedm.app.data.PermissionChecker

/** The process-wide container, reachable from any composable without a DI framework. */
@Composable
fun rememberContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) { VoiceDmApp.from(context).container }
}

/** Convenience for screens that only need "is X granted?". */
@Composable
fun rememberPermissionChecker(): PermissionChecker = rememberContainer().permissions

/** Unwraps a context into the application instance, used by ViewModel factories. */
fun containerFrom(context: Context): AppContainer = VoiceDmApp.from(context).container
