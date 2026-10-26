package com.voicedm.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.voicedm.app.R
import com.voicedm.app.assistant.audio.AssistantState
import com.voicedm.app.assistant.commands.Command
import com.voicedm.app.assistant.wakeword.WakePhrase
import com.voicedm.app.assistant.wakeword.WakeTriggerPolicy
import com.voicedm.app.ui.theme.VoiceDmTheme

/**
 * Everything about the on-device assistant: master switch, wake phrase, sensitivity,
 * a live detector readout (the "debug screen"), the command vocabulary, setup shortcuts
 * and the privacy statement. Minimal, dark and consistent with Settings - the shared
 * [SectionCard]/[PermissionRow] helpers keep the look identical.
 */
@Composable
fun AssistantScreen(
    viewModel: AssistantViewModel,
    onBack: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val runtime by viewModel.runtime.collectAsState()
    val context = LocalContext.current
    val permissions = rememberPermissionChecker()
    val phrase = WakePhrase.byId(settings.assistantPhraseId)

    // Enabling may need the microphone first; remember the intent across the grant dialog.
    var pendingEnable by remember { mutableStateOf(false) }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && pendingEnable) {
            viewModel.start(context)
        }
        pendingEnable = false
    }

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
                    text = stringResource(R.string.assistant_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            }

            Text(
                text = stringResource(R.string.assistant_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ------------------------------------------------------------- master switch
            SectionCard(title = stringResource(R.string.assistant_enable)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.assistant_enable_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = settings.assistantEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !permissions.microphoneGranted()) {
                                pendingEnable = true
                                viewModel.update { it.copy(assistantEnabled = true) }
                                micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            } else {
                                viewModel.setEnabled(context, enabled)
                            }
                        },
                    )
                }

                // Live state indicator.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val dotColor = when (runtime.state) {
                        AssistantState.IDLE_WAKE_LISTENING ->
                            MaterialTheme.colorScheme.primary

                        AssistantState.PAUSED, AssistantState.STOPPED ->
                            MaterialTheme.colorScheme.onSurfaceVariant

                        else -> MaterialTheme.colorScheme.tertiary
                    }
                    val listening = runtime.state == AssistantState.IDLE_WAKE_LISTENING
                    // Gentle breathing pulse while the mic is live; static otherwise.
                    val pulse by rememberInfiniteTransition(label = "dot").animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1100),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dotPulse",
                    )
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .graphicsLayer {
                                if (listening) {
                                    val s = 1f + pulse * 0.4f
                                    scaleX = s
                                    scaleY = s
                                    alpha = 1f - pulse * 0.5f
                                }
                            }
                            .background(dotColor, CircleShape),
                    )
                    Text(
                        text = stateLabel(runtime.state, phrase.label),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // ------------------------------------------------------------- live detector
            SectionCard(title = stringResource(R.string.assistant_debug)) {
                LinearProgressIndicator(
                    progress = { runtime.score.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(
                        R.string.assistant_live_score,
                        runtime.score,
                        runtime.avgInferenceMs,
                        runtime.avgInferenceMs / 80f * 100f,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DebugLine("state", runtime.state.name)
                DebugLine("voice activity", if (runtime.speaking) "speech" else "quiet")
                DebugLine(
                    "microphone",
                    when {
                        runtime.micReady -> "open · 16 kHz"
                        runtime.micError != null -> "error: ${runtime.micError}"
                        else -> "closed"
                    },
                )
                DebugLine("last event", runtime.lastEvent)

                Button(
                    onClick = {
                        if (permissions.microphoneGranted()) {
                            viewModel.start(context)
                        } else {
                            pendingEnable = true
                            micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(stringResource(R.string.assistant_test))
                }
                Text(
                    text = stringResource(R.string.assistant_test_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ------------------------------------------------------------- wake phrase
            SectionCard(title = stringResource(R.string.assistant_phrase_section)) {
                WakePhrase.BUNDLED.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.setPhrase(option.id) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = option.id == settings.assistantPhraseId,
                            onClick = { viewModel.setPhrase(option.id) },
                        )
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ------------------------------------------------------------- sensitivity
            SectionCard(title = stringResource(R.string.assistant_sensitivity)) {
                Slider(
                    value = settings.assistantSensitivity,
                    onValueChange = { viewModel.setSensitivity(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_sensitivity_high),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "threshold %.2f".format(WakeTriggerPolicy.thresholdFor(settings.assistantSensitivity)),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.assistant_sensitivity_low),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // ------------------------------------------------------------- wake-ups
            SectionCard(title = stringResource(R.string.assistant_wakeups)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "${settings.assistantFalseTriggers}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = { viewModel.resetWakeUps() }) {
                        Text(stringResource(R.string.assistant_reset))
                    }
                }
                Text(
                    text = stringResource(R.string.assistant_wakeups_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ------------------------------------------------------------- commands
            SectionCard(title = stringResource(R.string.assistant_commands)) {
                Text(
                    text = stringResource(R.string.assistant_commands_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Command.entries.forEach { command ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "“${command.spoken.first()}”",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = command.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // ------------------------------------------------------------- setup
            SectionCard(title = stringResource(R.string.assistant_setup)) {
                PermissionRow(
                    label = stringResource(R.string.settings_perm_mic),
                    granted = permissions.microphoneGranted(),
                    onOpen = {
                        pendingEnable = true
                        micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                    },
                )
                PermissionRow(
                    label = stringResource(R.string.settings_perm_battery),
                    granted = permissions.batteryOptimizationExempt(),
                    onOpen = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
                                ),
                            )
                        }
                    },
                )
            }

            // ------------------------------------------------------------- privacy
            SectionCard(title = stringResource(R.string.assistant_privacy_title)) {
                Text(
                    text = stringResource(R.string.assistant_privacy),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Localised state text for the live indicator. */
@Composable
private fun stateLabel(state: AssistantState, phraseLabel: String): String = when (state) {
    AssistantState.IDLE_WAKE_LISTENING -> stringResource(R.string.assistant_state_idle, phraseLabel)
    AssistantState.COMMAND_LISTENING -> stringResource(R.string.assistant_state_command)
    AssistantState.EXECUTING -> stringResource(R.string.assistant_state_executing)
    AssistantState.TTS_SPEAKING -> stringResource(R.string.assistant_state_speaking)
    AssistantState.RECORDING_REPLY -> stringResource(R.string.assistant_state_recording)
    AssistantState.PAUSED -> stringResource(R.string.assistant_state_paused)
    AssistantState.STOPPED -> stringResource(R.string.assistant_state_stopped)
}

@Composable
private fun DebugLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}
