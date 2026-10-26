package com.voicedm.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.voicedm.app.R
import com.voicedm.app.ui.theme.VoiceDmTheme

/**
 * Four-step setup. Every step either deep-links into the matching system settings screen
 * or asks for a runtime permission; coming back to the app re-checks the step and marks
 * it complete automatically.
 */
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onRequestMicrophone: () -> Unit,
    onFinished: () -> Unit,
) {
    val ui by viewModel.ui.collectAsState()
    val context = LocalContext.current
    var step by rememberSaveable { mutableIntStateOf(0) }

    // Jump forward if an earlier step was completed outside the app.
    LaunchedEffect(ui.notificationAccess, ui.microphone, ui.chatName) {
        if (step == 0 && ui.notificationAccess) step = 1
        if (step == 1 && ui.microphone) step = 2
    }

    VoiceDmTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = null,
                modifier = Modifier
                    .size(68.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            Text(
                text = stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.onboarding_step, step + 1),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )

            if (!ui.instagramInstalled) {
                StepCard(
                    title = stringResource(R.string.onboarding_instagram_missing),
                    body = "",
                )
            }

            when (step) {
                0 -> StepCard(
                    title = stringResource(R.string.step_notifications_title),
                    body = stringResource(R.string.step_notifications_desc),
                    done = ui.notificationAccess,
                    doneLabel = stringResource(R.string.step_notifications_done),
                    actionLabel = stringResource(R.string.step_notifications_action),
                    onAction = {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                            )
                        }.onFailure {
                            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                        }
                        viewModel.refresh()
                    },
                )

                1 -> StepCard(
                    title = stringResource(R.string.step_mic_title),
                    body = stringResource(R.string.step_mic_desc),
                    done = ui.microphone,
                    doneLabel = stringResource(R.string.step_mic_done),
                    actionLabel = if (ui.notifications) {
                        stringResource(R.string.step_mic_action)
                    } else {
                        stringResource(R.string.step_mic_action_all)
                    },
                    onAction = onRequestMicrophone,
                )

                2 -> StepCard(
                    title = stringResource(R.string.step_accessibility_title),
                    body = stringResource(R.string.step_accessibility_desc),
                    done = ui.accessibility,
                    doneLabel = stringResource(R.string.step_accessibility_done),
                    actionLabel = stringResource(R.string.step_accessibility_action),
                    onAction = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }
                        viewModel.refresh()
                    },
                    secondaryLabel = stringResource(R.string.step_accessibility_skip),
                    onSecondary = { step = 3 },
                )

                3 -> ContactStep(
                    ui = ui,
                    onNameChange = viewModel::setChatName,
                    onUseLatest = viewModel::pickLatestChat,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) {
                    OutlinedButton(
                        onClick = { step-- },
                        modifier = Modifier.height(56.dp),
                    ) {
                        Text("←")
                    }
                }
                val canContinue = when (step) {
                    0 -> ui.notificationAccess
                    1 -> ui.microphone
                    2 -> true
                    else -> ui.chatName.isNotBlank()
                }
                Button(
                    onClick = {
                        if (step < 3) step++ else onFinished()
                    },
                    enabled = canContinue,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                    shape = RoundedCornerShape(18.dp),
                ) {
                    Text(
                        text = if (step == 3) {
                            stringResource(R.string.onboarding_finish)
                        } else {
                            stringResource(R.string.onboarding_continue)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ContactStep(
    ui: OnboardingUi,
    onNameChange: (String) -> Unit,
    onUseLatest: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StepCard(
            title = stringResource(R.string.step_contact_title),
            body = stringResource(R.string.step_contact_desc),
        )
        OutlinedTextField(
            value = ui.chatName,
            onValueChange = onNameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.step_contact_hint)) },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
        )
        // One tap instead of typing the name exactly right: fill from the newest
        // notification VoiceDM has seen (disabled until one has arrived).
        OutlinedButton(
            onClick = onUseLatest,
            enabled = ui.discovered.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.settings_contact_use_last))
        }
        if (ui.discovered.isEmpty()) {
            Text(
                text = stringResource(R.string.step_contact_no_chats),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = stringResource(R.string.settings_seen_chats),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ui.discovered.forEach { chat ->
                OutlinedButton(
                    onClick = { onNameChange(chat.name) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(chat.name, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        if (ui.chatName.isBlank()) {
            Text(
                text = stringResource(R.string.step_contact_empty),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun StepCard(
    title: String,
    body: String,
    done: Boolean = false,
    doneLabel: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (done) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "✓",
                            color = MaterialTheme.colorScheme.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (body.isNotEmpty()) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (done && doneLabel != null) {
                Text(
                    text = doneLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (!done && actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (secondaryLabel != null && onSecondary != null) {
                OutlinedButton(
                    onClick = onSecondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(secondaryLabel)
                }
            }
        }
    }
}
