package com.voicedm.app

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicedm.app.data.AppContainer
import com.voicedm.app.service.VoiceDmService
import com.voicedm.app.ui.AssistantScreen
import com.voicedm.app.ui.AssistantViewModel
import com.voicedm.app.ui.MainScreen
import com.voicedm.app.ui.MainViewModel
import com.voicedm.app.ui.OnboardingScreen
import com.voicedm.app.ui.OnboardingViewModel
import com.voicedm.app.ui.SettingsScreen
import com.voicedm.app.ui.SettingsViewModel
import com.voicedm.app.ui.theme.VoiceDmTheme

private enum class Screen { ONBOARDING, MAIN, SETTINGS, ASSISTANT }

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AUTO_START = "com.voicedm.app.AUTO_START"
        const val EXTRA_OPEN_ASSISTANT = "com.voicedm.app.OPEN_ASSISTANT"
        const val EXTRA_RESUME_ASSISTANT = "com.voicedm.app.RESUME_ASSISTANT"
    }

    /** Nonce > 0 means "jump to the Assistant screen"; bumps again for each new intent. */
    private var assistantNav by mutableStateOf(0)

    /** Set when the "Resume listening" notification should also start the service. */
    private var resumeAssistantPending = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (wantsAssistant(intent)) {
            assistantNav++
            resumeAssistantPending = intent.getBooleanExtra(EXTRA_RESUME_ASSISTANT, false)
        }
    }

    private fun wantsAssistant(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_ASSISTANT, false) == true ||
            intent?.getBooleanExtra(EXTRA_RESUME_ASSISTANT, false) == true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (wantsAssistant(intent)) {
            assistantNav = 1
            resumeAssistantPending = intent.getBooleanExtra(EXTRA_RESUME_ASSISTANT, false)
        }

        val container = VoiceDmApp.from(this).container

        setContent {
            VoiceDmTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.MAIN) }
                val factory = remember { ContainerFactory(container) }

                val onboardingViewModel: OnboardingViewModel = viewModel(factory = factory)
                val mainViewModel: MainViewModel = viewModel(factory = factory)
                val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                val assistantViewModel: AssistantViewModel = viewModel(factory = factory)

                val onboarding by onboardingViewModel.ui.collectAsState()
                val mainState by mainViewModel.ui.collectAsState()

                // Notification / boot "resume" taps land here: open the Assistant screen,
                // and start the service when that's what the tap asked for.
                val navNonce = assistantNav
                androidx.compose.runtime.LaunchedEffect(navNonce) {
                    if (navNonce > 0) {
                        screen = Screen.ASSISTANT
                        if (resumeAssistantPending) {
                            resumeAssistantPending = false
                            com.voicedm.app.assistant.AssistantService.start(this@MainActivity)
                        }
                    }
                }

                // The widget hands us an "auto start" flag when the user tapped Start on
                // the home screen; honour it once permissions and contact are in place.
                val autoStart = remember {
                    intent?.getBooleanExtra(EXTRA_AUTO_START, false) == true
                }
                androidx.compose.runtime.LaunchedEffect(autoStart, mainState.canStart) {
                    if (autoStart && mainState.canStart && !VoiceDmService.running) {
                        VoiceDmService.start(this@MainActivity)
                    }
                }

                // Decide where to land on first composition; after that the user moves freely.
                var decided by rememberSaveable { mutableStateOf(false) }
                if (!decided) {
                    decided = true
                    screen = if (onboarding.finished) Screen.MAIN else Screen.ONBOARDING
                }

                val micLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { onboardingViewModel.refresh() }

                // Grants happen in other apps, so every return to the foreground re-checks
                // the steps instead of trusting a stale snapshot.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            onboardingViewModel.refresh()
                            mainViewModel.refresh()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                BackHandler(enabled = screen == Screen.SETTINGS || screen == Screen.ASSISTANT) {
                    screen = Screen.MAIN
                }

                when (screen) {
                    Screen.ONBOARDING -> OnboardingScreen(
                        viewModel = onboardingViewModel,
                        onRequestMicrophone = {
                            val wanted = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (!onboarding.notifications) {
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            }.toTypedArray()
                            micLauncher.launch(wanted)
                        },
                        onFinished = {
                            onboardingViewModel.refresh()
                            screen = if (onboardingViewModel.ui.value.finished) {
                                Screen.MAIN
                            } else {
                                Screen.ONBOARDING
                            }
                        },
                    )

                    Screen.MAIN -> MainScreen(
                        viewModel = mainViewModel,
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenAssistant = { screen = Screen.ASSISTANT },
                        onOpenSetup = {
                            onboardingViewModel.refresh()
                            screen = Screen.ONBOARDING
                        },
                    )

                    Screen.SETTINGS -> SettingsScreen(
                        viewModel = settingsViewModel,
                        onBack = { screen = Screen.MAIN },
                    )

                    Screen.ASSISTANT -> AssistantScreen(
                        viewModel = assistantViewModel,
                        onBack = { screen = Screen.MAIN },
                    )
                }
            }
        }
    }
}

private class ContainerFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(MainViewModel::class.java) ->
            MainViewModel(container) as T

        modelClass.isAssignableFrom(OnboardingViewModel::class.java) ->
            OnboardingViewModel(container) as T

        modelClass.isAssignableFrom(SettingsViewModel::class.java) ->
            SettingsViewModel(container) as T

        modelClass.isAssignableFrom(AssistantViewModel::class.java) ->
            AssistantViewModel(container) as T

        else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
    }
}
