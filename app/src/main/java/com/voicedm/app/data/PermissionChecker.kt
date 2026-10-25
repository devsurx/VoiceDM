package com.voicedm.app.data

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.voicedm.app.service.VoiceDmAccessibilityService

/** Answers "is X granted / available yet?" so the UI can react the moment a step completes. */
class PermissionChecker(private val context: Context) {

    fun notificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun microphoneGranted(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /** POST_NOTIFICATIONS only exists from API 33; always granted before that. */
    fun notificationsGranted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun accessibilityEnabled(): Boolean {
        val serviceClass = VoiceDmAccessibilityService::class.java.name
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val enabled = manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .orEmpty()
        if (enabled.any { it.id.substringAfter('/') == serviceClass }) return true

        val secure = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()
        return secure.split(':').any { it.substringAfter('/') == serviceClass }
    }

    fun batteryOptimizationExempt(): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun instagramInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(INSTAGRAM_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        const val INSTAGRAM_PACKAGE = "com.instagram.android"
    }
}
