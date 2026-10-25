package com.voicedm.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C8CFF)
private val AccentDim = Color(0xFF5B6CFF)
private val Background = Color(0xFF101014)
private val Surface = Color(0xFF17171E)
private val SurfaceHigh = Color(0xFF21212B)
private val OnSurface = Color(0xFFECECF2)
private val OnSurfaceDim = Color(0xFFB8B8C6)
private val Danger = Color(0xFFFF6B6B)
private val Success = Color(0xFF5BD6A0)

val DangerColor = Danger
val SuccessColor = Success
val AccentColor = Accent

private val VoiceDmColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = AccentDim,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF9AA6FF),
    onSecondary = Color.Black,
    background = Background,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceHigh,
    onSurfaceVariant = OnSurfaceDim,
    outline = Color(0xFF3A3A48),
    error = Danger,
    onError = Color.Black,
)

/**
 * VoiceDM is dark-only: the app is meant to be glanced at with the screen at minimum
 * brightness during hands-free conversations.
 */
@Composable
fun VoiceDmTheme(
    @Suppress("UNUSED_PARAMETER") darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = VoiceDmColors, content = content)
}
