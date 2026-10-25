package com.voicedm.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.voicedm.app.MainActivity
import com.voicedm.app.R
import com.voicedm.app.VoiceDmApp
import com.voicedm.app.VoiceDmApp.Companion.CHANNEL_ALERTS
import com.voicedm.app.VoiceDmApp.Companion.CHANNEL_SERVICE
import com.voicedm.app.data.AppContainer
import com.voicedm.app.domain.model.ConversationPhase
import com.voicedm.app.domain.model.ConversationUiState
import com.voicedm.app.service.audio.AudioFocusController
import com.voicedm.app.service.audio.BeepPlayer
import com.voicedm.app.service.audio.FocusEvent
import com.voicedm.app.service.reply.InlineReplySender
import com.voicedm.app.service.reply.VoiceNoteSender
import com.voicedm.app.service.speech.AndroidSpeechToText
import com.voicedm.app.service.tts.AndroidTtsSpeaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the conversation loop alive with the screen off.
 *
 * It owns the engine, the TTS/STT instances, audio focus, the media session (headset
 * buttons) and the persistent notification showing what the loop is doing.
 */
class VoiceDmService : LifecycleService() {

    private lateinit var container: AppContainer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var engine: ConversationEngine? = null
    private var tts: AndroidTtsSpeaker? = null
    private var stt: AndroidSpeechToText? = null
    private var focus: AudioFocusController? = null
    private var mediaSession: MediaSession? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val notificationManager: NotificationManagerCompat by lazy {
        NotificationManagerCompat.from(this)
    }

    @Volatile
    private var foregroundTypes: Int = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

    override fun onCreate() {
        super.onCreate()
        running = true
        container = VoiceDmApp.from(this).container
        startInForeground(foregroundTypes)

        serviceScope.launch {
            container.conversation.state.collect { state -> updateNotification(state) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startLoop()
            ACTION_STOP -> {
                stopLoop()
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SKIP -> engine?.post(EngineCommand.SKIP)
            ACTION_REPEAT -> engine?.post(EngineCommand.REPEAT)
            ACTION_SEND -> engine?.post(EngineCommand.SEND)
            ACTION_DISCARD -> engine?.post(EngineCommand.DISCARD)
            ACTION_LISTEN -> engine?.post(EngineCommand.LISTEN)
        }
        updateNotification(container.conversation.state.value)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        stopLoop()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ loop

    private fun startLoop() {
        if (engine != null) return

        acquireWakeLock()
        startMediaSession()

        val speechSynthesizer = AndroidTtsSpeaker(this)
        val speechRecognizer = AndroidSpeechToText(this)
        val audioFocus = AudioFocusController(this) { event ->
            engine?.onFocusEvent(lost = event != FocusEvent.Gained)
        }
        tts = speechSynthesizer
        stt = speechRecognizer
        focus = audioFocus

        val created = ConversationEngine(
            scope = serviceScope,
            store = container.conversation,
            pending = container.pipeline.pending,
            messageSignals = container.pipeline.signal,
            echoGuard = container.echoGuard,
            focus = audioFocus,
            tts = speechSynthesizer,
            stt = speechRecognizer,
            textReply = InlineReplySender(this, container.activeNotifications),
            voiceNote = VoiceNoteSender(),
            beeps = BeepPlayer(),
            currentSettings = { container.settings.current() },
            onRecordingChanged = ::applyRecordingType,
            onAutomationFailed = ::showAlert,
        )
        engine = created

        lifecycleScope.launch {
            val settings = container.settings.current()
            container.conversation.setRunning(true, settings.targetChatName)
            created.run()
        }
    }

    private fun stopLoop() {
        engine = null
        tts?.shutdown()
        tts = null
        stt?.shutdown()
        stt = null
        focus?.abandon()
        focus = null
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null
        container.conversation.setRunning(false)
        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceDM:conversation").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        wakeLock = null
    }

    // ------------------------------------------------------------------ notification

    private fun startInForeground(types: Int) {
        val mediaOnly = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        val wanted = if (types == 0) mediaOnly else types
        runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(container.conversation.state.value),
                wanted,
            )
        }.onFailure {
            Log.w(TAG, "startForeground($wanted) failed, retrying with mediaPlayback", it)
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(container.conversation.state.value),
                    mediaOnly,
                )
            }
        }
        foregroundTypes = wanted
    }

    /**
     * Mode A actually uses the microphone, so the service type has to include it while we
     * listen; otherwise Android 14+ kills the recording.
     */
    private fun applyRecordingType(recording: Boolean) {
        val wanted = if (recording && container.permissions.microphoneGranted()) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        if (wanted == foregroundTypes) return
        startInForeground(wanted)
    }

    private fun updateNotification(state: ConversationUiState) {
        if (!container.permissions.notificationsGranted()) return
        runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification(state)) }
    }

    private fun buildNotification(state: ConversationUiState): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_voicedm)
            .setContentTitle(
                getString(
                    R.string.notif_title,
                    state.chatName.ifBlank { getString(R.string.notif_no_chat) },
                ),
            )
            .setContentText(statusText(state))
            .setSubText(getString(R.string.notif_subtext))
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(0xFF5B6CFF.toInt())

        when (state.phase) {
            ConversationPhase.READING,
            ConversationPhase.LISTENING,
            -> builder.addAction(
                0,
                getString(R.string.notif_action_skip),
                serviceIntent(ACTION_SKIP, 1),
            )

            ConversationPhase.CONFIRMING -> builder
                .addAction(0, getString(R.string.notif_action_send), serviceIntent(ACTION_SEND, 2))
                .addAction(
                    0,
                    getString(R.string.notif_action_discard),
                    serviceIntent(ACTION_DISCARD, 3),
                )

            ConversationPhase.WAITING -> builder.addAction(
                0,
                getString(R.string.notif_action_reply),
                serviceIntent(ACTION_LISTEN, 4),
            )

            else -> Unit
        }
        builder.addAction(0, getString(R.string.notif_action_stop), serviceIntent(ACTION_STOP, 5))
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, VoiceDmService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun statusText(state: ConversationUiState): String = when (state.phase) {
        ConversationPhase.IDLE -> getString(R.string.status_idle)
        ConversationPhase.WAITING -> getString(R.string.status_waiting)
        ConversationPhase.READING -> getString(R.string.status_reading)
        ConversationPhase.LISTENING -> getString(R.string.status_listening)
        ConversationPhase.CONFIRMING -> getString(R.string.status_confirming)
        ConversationPhase.SENDING -> getString(R.string.status_sending)
        ConversationPhase.SENT -> getString(R.string.status_sent)
        ConversationPhase.SKIPPED -> getString(R.string.status_skipped)
        ConversationPhase.ERROR -> state.errorMessage ?: getString(R.string.status_error)
    }

    private fun showAlert(reason: String) {
        if (!container.permissions.notificationsGranted()) return
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_voicedm)
            .setContentTitle(getString(R.string.alert_title))
            .setContentText(reason)
            .setAutoCancel(true)
            .build()
        runCatching { notificationManager.notify(ALERT_NOTIFICATION_ID, notification) }
    }

    // ------------------------------------------------------------------ headset buttons

    private fun startMediaSession() {
        if (mediaSession != null) return
        mediaSession = MediaSession(this, "VoiceDM").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(intent: Intent): Boolean =
                        handleMediaButton(intent)

                    override fun onPlay() {
                        engine?.post(EngineCommand.LISTEN)
                    }

                    override fun onPause() {
                        engine?.post(EngineCommand.SKIP)
                    }

                    override fun onSkipToNext() {
                        engine?.post(EngineCommand.SKIP)
                    }
                },
            )
            isActive = true
        }
    }

    private fun handleMediaButton(intent: Intent): Boolean {
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT) ?: return false
        if (event.action != KeyEvent.ACTION_DOWN) return true

        serviceScope.launch {
            if (!container.settings.current().headsetButtonTrigger) return@launch
            val phase = container.conversation.state.value.phase
            val command = when (phase) {
                ConversationPhase.READING -> EngineCommand.SKIP
                ConversationPhase.CONFIRMING -> EngineCommand.SEND
                ConversationPhase.LISTENING -> return@launch // cancelled by its own timeout
                else -> EngineCommand.LISTEN
            }
            engine?.post(command)
        }
        return true
    }

    companion object {
        private const val TAG = "VoiceDM"
        private const val NOTIFICATION_ID = 4211
        private const val ALERT_NOTIFICATION_ID = 4212

        const val ACTION_START = "com.voicedm.app.action.START"
        const val ACTION_STOP = "com.voicedm.app.action.STOP"
        const val ACTION_SKIP = "com.voicedm.app.action.SKIP"
        const val ACTION_REPEAT = "com.voicedm.app.action.REPEAT"
        const val ACTION_SEND = "com.voicedm.app.action.SEND"
        const val ACTION_DISCARD = "com.voicedm.app.action.DISCARD"
        const val ACTION_LISTEN = "com.voicedm.app.action.LISTEN"

        /** True while the service is alive, so the UI/widget/tile can show the right state. */
        @Volatile
        var running: Boolean = false
            private set

        private fun intentFor(context: Context, action: String): Intent =
            Intent(context, VoiceDmService::class.java).setAction(action)

        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, intentFor(context, ACTION_START))
            }.onFailure { Log.w(TAG, "Could not start VoiceDM", it) }
        }

        fun command(context: Context, action: String) {
            runCatching { context.startService(intentFor(context, action)) }
                .onFailure { Log.w(TAG, "Could not deliver $action", it) }
        }

        fun stop(context: Context) = command(context, ACTION_STOP)
        fun skip(context: Context) = command(context, ACTION_SKIP)
        fun repeat(context: Context) = command(context, ACTION_REPEAT)
        fun send(context: Context) = command(context, ACTION_SEND)
        fun discard(context: Context) = command(context, ACTION_DISCARD)
        fun listen(context: Context) = command(context, ACTION_LISTEN)
    }
}
