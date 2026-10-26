package com.voicedm.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedm.app.R
import com.voicedm.app.domain.model.AppSettings
import com.voicedm.app.domain.model.ReplyMode
import com.voicedm.app.ui.theme.VoiceDmTheme
import java.util.Locale

/**
 * Every knob VoiceDM exposes, grouped into contact / replies / voice / Mode B / access.
 * Values are written straight through to DataStore, so changes apply to the very next
 * message even while the loop is running.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val discovered by viewModel.discovered.collectAsState()
    val context = LocalContext.current
    val permissions = rememberPermissionChecker()

    VoiceDmTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.settings_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back))
                }
            }

            // ---------------------------------------------------------------- contact
            SectionCard(title = stringResource(R.string.settings_section_contact)) {
                OutlinedTextField(
                    value = settings.targetChatName,
                    onValueChange = { name -> viewModel.setContact(name) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.settings_contact_hint)) },
                    placeholder = { Text(stringResource(R.string.settings_contact_empty)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                if (discovered.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_seen_chats),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    discovered.forEach { chat ->
                        OutlinedButton(
                            onClick = { viewModel.setContact(chat.name) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Text(
                                text = chat.name,
                                modifier = Modifier.fillMaxWidth(),
                                color = if (chat.name.trim() == settings.targetChatName.trim()) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------------------------- replies
            SectionCard(title = stringResource(R.string.settings_section_reply)) {
                ModeOption(
                    title = stringResource(R.string.settings_mode_a),
                    description = stringResource(R.string.settings_mode_a_desc),
                    selected = settings.replyMode == ReplyMode.INLINE_TEXT,
                    onSelect = { viewModel.update { it.copy(replyMode = ReplyMode.INLINE_TEXT) } },
                )
                ModeOption(
                    title = stringResource(R.string.settings_mode_b),
                    description = stringResource(R.string.settings_mode_b_desc),
                    selected = settings.replyMode == ReplyMode.VOICE_NOTE,
                    onSelect = { viewModel.update { it.copy(replyMode = ReplyMode.VOICE_NOTE) } },
                )
                if (settings.replyMode == ReplyMode.VOICE_NOTE && !permissions.accessibilityEnabled()) {
                    Text(
                        text = stringResource(R.string.settings_mode_b_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                ToggleRow(
                    title = stringResource(R.string.settings_confirm),
                    subtitle = stringResource(R.string.settings_confirm_desc),
                    checked = settings.confirmBeforeSend,
                    onChange = { value -> viewModel.update { it.copy(confirmBeforeSend = value) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_autolisten),
                    checked = settings.autoListenAfterRead,
                    onChange = { value -> viewModel.update { it.copy(autoListenAfterRead = value) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_headset),
                    checked = settings.headsetButtonTrigger,
                    onChange = { value -> viewModel.update { it.copy(headsetButtonTrigger = value) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_announce),
                    checked = settings.announceSender,
                    onChange = { value -> viewModel.update { it.copy(announceSender = value) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_retry),
                    checked = settings.retryOnNoSpeech,
                    onChange = { value -> viewModel.update { it.copy(retryOnNoSpeech = value) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_catchup),
                    checked = settings.catchUpOnStart,
                    onChange = { value -> viewModel.update { it.copy(catchUpOnStart = value) } },
                )
            }

            // ---------------------------------------------------------------- voice
            SectionCard(title = stringResource(R.string.settings_section_speech)) {
                val rate = settings.ttsRate
                Text(
                    text = stringResource(R.string.settings_tts_rate) + " · " +
                        stringResource(R.string.settings_tts_rate_value, rate),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = rate,
                    onValueChange = { value -> viewModel.update { it.copy(ttsRate = value) } },
                    valueRange = 0.5f..2.0f,
                )
                LanguageField(
                    label = stringResource(R.string.settings_tts_language),
                    value = settings.ttsLanguageTag,
                    onChange = { tag -> viewModel.update { it.copy(ttsLanguageTag = tag) } },
                )
                LanguageField(
                    label = stringResource(R.string.settings_asr_language),
                    value = settings.recognitionLanguageTag,
                    onChange = { tag -> viewModel.update { it.copy(recognitionLanguageTag = tag) } },
                )
                ToggleRow(
                    title = stringResource(R.string.settings_offline_asr),
                    checked = settings.offlineRecognition,
                    onChange = { value -> viewModel.update { it.copy(offlineRecognition = value) } },
                )
            }

            // ---------------------------------------------------------------- mode B
            SectionCard(title = stringResource(R.string.settings_section_mode_b)) {
                Text(
                    text = stringResource(R.string.settings_voice_hold, settings.voiceNoteHoldSeconds),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = settings.voiceNoteHoldSeconds.toFloat(),
                    onValueChange = { value ->
                        viewModel.update { it.copy(voiceNoteHoldSeconds = value.toInt()) }
                    },
                    valueRange = 3f..30f,
                    steps = 26,
                )
            }

            // ---------------------------------------------------------------- access
            SectionCard(title = stringResource(R.string.settings_section_permissions)) {
                PermissionRow(
                    label = stringResource(R.string.settings_perm_notifications),
                    granted = permissions.notificationAccessEnabled(),
                    onOpen = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.settings_perm_mic),
                    granted = permissions.microphoneGranted(),
                    onOpen = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        }
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.settings_perm_accessibility),
                    granted = permissions.accessibilityEnabled(),
                    onOpen = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.settings_perm_battery),
                    granted = permissions.batteryOptimizationExempt(),
                    onOpen = {
                        // No permission needed for the settings page (see MainViewModel).
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                            )
                        }.onFailure {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        android.net.Uri.parse("package:${context.packageName}"),
                                    ),
                                )
                            }
                        }
                    },
                )
            }

            Text(
                text = stringResource(R.string.settings_about),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun ModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        onClick = onSelect,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    subtitle: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LanguageField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = {
            Text(stringResource(R.string.settings_lang_hint).replace("e.g. ", "").lowercase(Locale.ROOT))
        },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
internal fun PermissionRow(label: String, granted: Boolean, onOpen: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (granted) {
                stringResource(R.string.settings_granted)
            } else {
                stringResource(R.string.settings_not_granted)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (granted) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        OutlinedButton(onClick = onOpen, shape = RoundedCornerShape(14.dp)) {
            Text(stringResource(R.string.settings_open))
        }
    }
}
