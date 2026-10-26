package com.voicedm.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voicedm.app.R
import com.voicedm.app.domain.model.ConversationPhase
import com.voicedm.app.domain.model.LogDirection
import com.voicedm.app.domain.model.LogEntry
import com.voicedm.app.domain.model.MessageKind
import com.voicedm.app.service.VoiceDmService
import com.voicedm.app.ui.theme.AccentColor
import com.voicedm.app.ui.theme.DangerColor
import com.voicedm.app.ui.theme.SuccessColor
import com.voicedm.app.ui.theme.VoiceDmTheme
import java.text.DateFormat
import java.util.Date


/**
 * The whole app: one large Start/Stop control, a status line, the contextual buttons the
 * loop currently wants (skip / repeat / send / discard) and a rolling log of what was
 * said. Everything else lives one tap away in Settings.
 */
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onOpenSettings: () -> Unit,
    onOpenAssistant: () -> Unit,
    onOpenSetup: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val conversation by viewModel.conversation.collectAsState()
    val running = VoiceDmService.running || conversation.isRunning
    val context = LocalContext.current
    val logState = rememberLazyListState()

    LaunchedEffect(conversation.log.size) {
        if (conversation.log.isNotEmpty()) {
            logState.animateScrollToItem(conversation.log.size - 1)
        }
    }

    VoiceDmTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (ui.displayName.isNotBlank()) {
                            stringResource(R.string.main_talk_title, ui.displayName)
                        } else {
                            stringResource(R.string.settings_contact_empty)
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    AnimatedContent(
                        targetState = conversation.phase to conversation.errorMessage,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "status",
                    ) { (phase, error) ->
                        Text(
                            text = statusLabel(phase, error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor(phase),
                        )
                    }
                }
                IconButton(onClick = onOpenAssistant) {
                    Icon(
                        imageVector = Mic,
                        contentDescription = stringResource(R.string.assistant_open),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.main_open_settings),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            StatusPill(
                phase = conversation.phase,
                running = running,
                errorMessage = conversation.errorMessage,
            )

            if (!ui.onboardingDone) {
                Button(
                    onClick = onOpenSetup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        stringResource(R.string.main_open_setup),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            if (running && conversation.phase == ConversationPhase.CONFIRMING &&
                conversation.pendingTranscript != null
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.main_confirm_title),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = conversation.pendingTranscript.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }

            // The one control that matters.
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                StartStopButton(
                    running = running,
                    enabled = ui.canStart,
                    onClick = { viewModel.toggle(context) },
                )
            }

            if (running) {
                ContextualActions(
                    phase = conversation.phase,
                    onSkip = { viewModel.skip(context) },
                    onRepeat = { viewModel.repeat(context) },
                    onSend = { viewModel.send(context) },
                    onDiscard = { viewModel.discard(context) },
                    onListen = { viewModel.listenNow(context) },
                )
            }

            if (running && !ui.batteryExempt) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.main_battery_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.main_battery_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { viewModel.requestBatteryExemption(context) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(16.dp),
                        ) {
                            Text(stringResource(R.string.main_battery_action))
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (conversation.log.isEmpty()) {
                        stringResource(R.string.main_log_empty)
                    } else {
                        stringResource(R.string.app_name)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (conversation.log.isNotEmpty()) {
                    TextButton(onClick = viewModel::clearLog) {
                        Text(stringResource(R.string.main_clear_log))
                    }
                }
            }

            LazyColumn(
                state = logState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(conversation.log, key = { it.id }) { entry ->
                    LogRow(entry)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(phase: ConversationPhase, running: Boolean, errorMessage: String?) {
    val label = statusLabel(phase, errorMessage)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusColor(phase), CircleShape),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun StartStopButton(running: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val background = when {
        running -> DangerColor
        enabled -> AccentColor
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(190.dp)
            .background(background, CircleShape)
            .clickable(enabled = enabled || running, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (running) {
                stringResource(R.string.main_stop)
            } else {
                stringResource(R.string.main_start)
            },
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (enabled || running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContextualActions(
    phase: ConversationPhase,
    onSkip: () -> Unit,
    onRepeat: () -> Unit,
    onSend: () -> Unit,
    onDiscard: () -> Unit,
    onListen: () -> Unit,
) {
    val buttons: List<Pair<String, () -> Unit>> = when (phase) {
        ConversationPhase.READING ->
            listOf(stringResource(R.string.main_skip) to onSkip) +
                (stringResource(R.string.main_repeat) to onRepeat)

        ConversationPhase.LISTENING, ConversationPhase.SENDING ->
            listOf(stringResource(R.string.main_skip) to onSkip)

        ConversationPhase.CONFIRMING -> listOf(
            stringResource(R.string.main_send) to onSend,
            stringResource(R.string.main_discard) to onDiscard,
        )

        ConversationPhase.WAITING, ConversationPhase.SENT, ConversationPhase.SKIPPED ->
            listOf(stringResource(R.string.main_listen) to onListen)

        else -> emptyList()
    }
    if (buttons.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        buttons.forEach { (label, action) ->
            OutlinedButton(
                onClick = action,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val outgoing = entry.direction == LogDirection.OUTGOING
    val system = entry.direction == LogDirection.SYSTEM
    val alignment: Alignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart
    val background = when {
        system -> Color.Transparent
        outgoing -> AccentColor.copy(alpha = 0.18f)
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val prefix = when {
        system -> ""
        entry.kind != MessageKind.TEXT -> kindLabel(entry.kind)
        else -> ""
    }
    val text = when {
        system -> entry.text
        prefix.isEmpty() -> entry.text
        else -> "$prefix · ${entry.text}"
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment,
    ) {
        Column(
            horizontalAlignment = if (outgoing) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = background,
            ) {
                Text(
                    text = text,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = when {
                        system -> MaterialTheme.colorScheme.onSurfaceVariant
                        outgoing -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Text(
                text = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(entry.timeMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun kindLabel(kind: MessageKind): String = when (kind) {
    MessageKind.VOICE_NOTE -> stringResource(R.string.kind_voice_note)
    MessageKind.PHOTO -> stringResource(R.string.kind_photo)
    MessageKind.VIDEO -> stringResource(R.string.kind_video)
    MessageKind.REEL -> stringResource(R.string.kind_reel)
    MessageKind.STORY_MENTION -> stringResource(R.string.kind_story)
    MessageKind.TEXT, MessageKind.UNKNOWN -> ""
}

@Composable
private fun statusLabel(phase: ConversationPhase, errorMessage: String?): String = when (phase) {
    ConversationPhase.IDLE -> stringResource(R.string.status_idle)
    ConversationPhase.WAITING -> stringResource(R.string.status_waiting)
    ConversationPhase.READING -> stringResource(R.string.status_reading)
    ConversationPhase.LISTENING -> stringResource(R.string.status_listening)
    ConversationPhase.CONFIRMING -> stringResource(R.string.status_confirming)
    ConversationPhase.SENDING -> stringResource(R.string.status_sending)
    ConversationPhase.SENT -> stringResource(R.string.status_sent)
    ConversationPhase.SKIPPED -> stringResource(R.string.status_skipped)
    ConversationPhase.ERROR -> errorMessage ?: stringResource(R.string.status_error)
}

private fun statusColor(phase: ConversationPhase): Color = when (phase) {
    ConversationPhase.ERROR -> DangerColor
    ConversationPhase.SENT -> SuccessColor
    ConversationPhase.IDLE -> Color(0xFF8A8A99)
    else -> AccentColor
}
