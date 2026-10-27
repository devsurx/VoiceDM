package com.voicedm.app.assistant

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.voicedm.app.R
import com.voicedm.app.VoiceDmApp
import com.voicedm.app.assistant.audio.AssistantEffect
import com.voicedm.app.assistant.audio.AssistantEvent
import com.voicedm.app.assistant.audio.AssistantState
import com.voicedm.app.assistant.audio.AudioSessionManager
import com.voicedm.app.assistant.wakeword.OpenWakeWordEngine
import com.voicedm.app.assistant.wakeword.WakePhrase
import com.voicedm.app.assistant.wakeword.WakeTriggerPolicy
import com.voicedm.app.assistant.wakeword.WakeWordEngine
import com.voicedm.app.domain.model.ConversationPhase
import com.voicedm.app.service.audio.Beep
import com.voicedm.app.service.audio.BeepPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Always-on wake-word listener.
 *
 * Runs as a foreground service with `foregroundServiceType="microphone"` so it can hold the
 * mic with the screen off, and publishes every state change to [AssistantRuntimeStore] over
 * the shared in-process Flow bus - the same mechanism the conversation service uses.
 *
 * Android 12+/14 background-start rules: this service is only started from a visible
 * Activity (Assistant screen, notification action, boot receiver). On Android 14+ starting
 * a *microphone* type service from the background is refused by the system; we catch that
 * and fall back to a one-tap "Resume listening" notification, as required by the spec.
 *
 * Stage 1 scope: wake detection + chime + a timed command window (the recogniser that
 * fills that window arrives in Stage 2). The state machine already models the full
 * IDLE -> COMMAND -> EXECUTING -> TTS -> RECORDING cycle.
 */
class AssistantService : Service() {

    private val container by lazy { VoiceDmApp.from(this).container }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val session = AudioSessionManager()
    private val beeps = BeepPlayer()
    private val trigger = WakeTriggerPolicy()

    private var engine: WakeWordEngine? = null
    private var captureJob: Job? = null
    private var commandJob: Job? = null
    private var conversationWatch: Job? = null

    /** Set when the user tapped Pause (vs. the conversation loop taking the audio). */
    @Volatile
    private var userPaused = false

    /** Set while VoiceDM itself is speaking or recording a reply. */
    @Volatile
    private var conversationYields = false

    @Volatile
    private var awake = false

    private var avgInferenceMs = 0f

    override fun onBind(intent: Intent?): android.os.IBinder? = null

    override fun onCreate() {
        super.onCreate()
        awake = true
        watchConversation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                userPaused = true
                reconcilePause("user pause")
            }

            ACTION_RESUME -> {
                userPaused = false
                reconcilePause("user resume")
            }

            ACTION_STOP -> stopAssistant()
            else -> startAssistant()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        awake = false
        runCatching { session.dispatch(AssistantEvent.STOP) }
        session.releaseMic()
        engine?.close()
        engine = null
        container.assistant.update {
            it.copy(serviceRunning = false, state = AssistantState.STOPPED, listeningForWake = false, score = 0f)
        }
        super.onDestroy()
    }

    // ---------------------------------------------------------------------------------
    // lifecycle

    private fun startAssistant() {
        if (!container.permissions.microphoneGranted()) {
            Log.w(TAG, "start refused: microphone permission missing")
            stopSelf()
            return
        }

        // State first so the foreground notification opens on the right text.
        if (session.machine.state == AssistantState.STOPPED) {
            val transition = session.dispatch(AssistantEvent.START)
            record(transition.to, "service start")
            applyEffects(transition.effects)
        }

        if (!startForegroundMic()) {
            // System refused a background microphone FGS (Android 14+) or similar:
            // hand the user a one-tap notification instead.
            postResumeNotification()
            stopSelf()
            return
        }
        if (captureJob?.isActive == true) {
            updateNotification()
            return
        }

        userPaused = false
        updateNotification()

        captureJob = scope.launch(Dispatchers.IO) { captureLoop() }
    }

    /** Returns false when the system blocks microphone-type foreground start. */
    private fun startForegroundMic(): Boolean = try {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "mic-type startForeground refused", e)
        false
    }

    private fun stopAssistant() {
        awake = false
        commandJob?.cancel()
        captureJob?.cancel()
        session.dispatch(AssistantEvent.STOP)
        session.releaseMic()
        engine?.close()
        engine = null
        container.assistant.update {
            it.copy(serviceRunning = false, state = AssistantState.STOPPED, listeningForWake = false, score = 0f, lastEvent = "stopped")
        }
        NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---------------------------------------------------------------------------------
    // the capture loop: mic -> VAD -> wake model -> trigger

    private suspend fun captureLoop() {
        if (!session.openMic()) {
            container.assistant.update { it.copy(micError = session.micError, micReady = false, lastEvent = "mic failed") }
            postResumeNotification()
            stopSelf()
            return
        }

        val settings = container.settings.current()
        val phrase = WakePhrase.byId(settings.assistantPhraseId)
        trigger.threshold = WakeTriggerPolicy.thresholdFor(settings.assistantSensitivity)

        val newEngine = OpenWakeWordEngine(this, phrase)
        engine = newEngine
        container.assistant.update {
            it.copy(
                micReady = true,
                micError = null,
                serviceRunning = true,
                listeningForWake = session.machine.wakeListening,
                lastEvent = "listening for ${phrase.label}",
            )
        }

        val frame = ShortArray(WakeWordEngine.STEP_SAMPLES)
        var gapFrames = 0
        var framesSincePublish = 0

        while (awake) {
            val read = session.readStep(frame)
            if (read < frame.size) {
                delay(25)
                continue
            }

            val listening = session.machine.wakeListening
            if (listening) {
                if (gapFrames > 0) {
                    newEngine.markGap(gapFrames)
                    gapFrames = 0
                }
                val result = newEngine.processFrame(frame)
                avgInferenceMs = avgInferenceMs * 0.95f + result.inferenceMs * 0.05f

                framesSincePublish++
                if (framesSincePublish >= 4 || kotlin.math.abs(result.score - container.assistant.state.value.score) > 0.02f) {
                    framesSincePublish = 0
                    container.assistant.update {
                        it.copy(
                            score = result.score,
                            speaking = result.speaking,
                            avgInferenceMs = avgInferenceMs,
                            listeningForWake = true,
                            state = session.machine.state,
                        )
                    }
                }

                if (trigger.onFrame(result.score, result.speaking, android.os.SystemClock.elapsedRealtime())) {
                    onWakeDetected()
                }
            } else {
                // Detector gated (command window / TTS / paused): account the silence so the
                // feature tail is rebuilt when the user speaks again.
                gapFrames++
                if (gapFrames % 25 == 0) {
                    container.assistant.update {
                        it.copy(listeningForWake = false, state = session.machine.state, avgInferenceMs = avgInferenceMs)
                    }
                }
            }
        }
    }

    private fun onWakeDetected() {
        val transition = session.dispatch(AssistantEvent.WAKE_DETECTED)
        if (!transition.changed) return
        record(transition.to, "wake: ${container.assistant.state.value.score}")
        applyEffects(transition.effects)
        updateNotification()
        publishState()
        countWakeUp()

        // Stage 1: the command window is timed but not yet transcribed (Stage 2 plugs the
        // recogniser in here). The same 5 s budget the spec asks for.
        commandJob?.cancel()
        commandJob = scope.launch {
            delay(COMMAND_WINDOW_MS)
            val timeout = session.dispatch(AssistantEvent.COMMAND_TIMEOUT)
            if (timeout.changed) {
                record(timeout.to, "command window expired")
                applyEffects(timeout.effects)
                updateNotification()
                publishState()
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // coordination with the conversation loop (shared Flow bus)

    private fun watchConversation() {
        conversationWatch = scope.launch {
            container.conversation.state.collectLatest { conv ->
                val busy = conv.isRunning && (conv.phase == ConversationPhase.READING ||
                    conv.phase == ConversationPhase.LISTENING ||
                    conv.phase == ConversationPhase.YAPPING)
                if (busy != conversationYields) {
                    conversationYields = busy
                    reconcilePause(if (busy) "conversation took audio" else "conversation released audio")
                }
            }
        }
    }

    /**
     * Pause when *either* the user or the conversation loop needs the audio path, resume
     * only when both are done. Idempotent, so it can be called from anywhere.
     */
    private fun reconcilePause(reason: String) {
        if (!awake) return
        val shouldPause = userPaused || conversationYields
        val state = session.machine.state
        when {
            shouldPause && state != AssistantState.STOPPED && state != AssistantState.PAUSED -> {
                val t = session.dispatch(AssistantEvent.PAUSE)
                record(t.to, reason)
                applyEffects(t.effects)
            }

            !shouldPause && state == AssistantState.PAUSED -> {
                val t = session.dispatch(AssistantEvent.RESUME)
                record(t.to, reason)
                applyEffects(t.effects)
            }
        }
        if (!shouldPause) trigger.reset()
        updateNotification()
        publishState()
    }

    // ---------------------------------------------------------------------------------
    // effects / publishing

    private fun applyEffects(effects: Set<AssistantEffect>) {
        for (effect in effects) {
            when (effect) {
                AssistantEffect.CHIME_WAKE -> beeps.play(Beep.WAKE, force = true)
                AssistantEffect.TONE_UNDERSTOOD -> beeps.play(Beep.UNDERSTOOD, force = true)
                // Stage 1: failure tone; Stage 2 replaces this with "Sorry, I didn't get that".
                AssistantEffect.SAY_SORRY -> beeps.play(Beep.FAILED, force = true)
                AssistantEffect.START_COMMAND_TIMER -> Unit // armed in onWakeDetected
                AssistantEffect.STOP_COMMAND_TIMER -> commandJob?.cancel()
            }
        }
    }

    private fun publishState() {
        container.assistant.update {
            it.copy(
                state = session.machine.state,
                listeningForWake = session.machine.wakeListening,
                avgInferenceMs = avgInferenceMs,
            )
        }
    }

    private fun record(state: AssistantState, event: String) {
        container.assistant.update { it.copy(state = state, lastEvent = event) }
    }

    private fun countWakeUp() {
        scope.launch {
            container.settings.update { s -> s.copy(assistantFalseTriggers = s.assistantFalseTriggers + 1) }
        }
    }

    // ---------------------------------------------------------------------------------
    // notifications

    private fun stateText(): String {
        val phrase = WakePhrase.byId(container.cachedSettings.assistantPhraseId).label
        return when (session.machine.state) {
            AssistantState.IDLE_WAKE_LISTENING -> getString(R.string.assistant_notif_listening, phrase)
            AssistantState.COMMAND_LISTENING -> getString(R.string.assistant_notif_command)
            AssistantState.EXECUTING -> getString(R.string.assistant_notif_working)
            AssistantState.TTS_SPEAKING -> getString(R.string.assistant_notif_speaking)
            AssistantState.RECORDING_REPLY -> getString(R.string.assistant_notif_recording)
            AssistantState.PAUSED -> getString(R.string.assistant_notif_paused)
            AssistantState.STOPPED -> getString(R.string.assistant_notif_paused)
        }
    }

    private fun updateNotification() {
        runCatching {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.voicedm.app.MainActivity::class.java)
                .putExtra(com.voicedm.app.MainActivity.EXTRA_OPEN_ASSISTANT, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleAction = if (session.machine.state == AssistantState.PAUSED) {
            NotificationCompat.Action(
                0,
                getString(R.string.assistant_notif_action_resume),
                servicePending(ACTION_RESUME, 11),
            )
        } else {
            NotificationCompat.Action(
                0,
                getString(R.string.assistant_notif_action_pause),
                servicePending(ACTION_PAUSE, 12),
            )
        }

        return NotificationCompat.Builder(this, VoiceDmApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_voicedm)
            .setContentTitle(getString(R.string.assistant_notif_title))
            .setContentText(stateText())
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .addAction(toggleAction)
            .addAction(
                NotificationCompat.Action(
                    0,
                    getString(R.string.notif_action_stop),
                    servicePending(ACTION_STOP, 13),
                ),
            )
            .build()
    }

    private fun servicePending(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AssistantService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Fallback path when the system refuses a background microphone service start. */
    private fun postResumeNotification() {
        runCatching {
            val open = PendingIntent.getActivity(
                this,
                1,
                Intent(this, com.voicedm.app.MainActivity::class.java)
                    .putExtra(com.voicedm.app.MainActivity.EXTRA_RESUME_ASSISTANT, true)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(this, VoiceDmApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_voicedm)
                .setContentTitle(getString(R.string.assistant_notif_title))
                .setContentText(getString(R.string.assistant_notif_resume))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(this).notify(RESUME_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val NOTIFICATION_ID = 4213
        private const val RESUME_NOTIFICATION_ID = 4214
        const val COMMAND_WINDOW_MS = 5_000L

        const val ACTION_PAUSE = "com.voicedm.app.assistant.PAUSE"
        const val ACTION_RESUME = "com.voicedm.app.assistant.RESUME"
        const val ACTION_STOP = "com.voicedm.app.assistant.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AssistantService::class.java),
            )
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            runCatching { context.startService(Intent(context, AssistantService::class.java).setAction(action)) }
        }
    }
}
