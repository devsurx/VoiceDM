package com.voicedm.app.service

import com.voicedm.app.data.ConversationStore
import com.voicedm.app.domain.model.Announcements
import com.voicedm.app.domain.model.AppSettings
import com.voicedm.app.domain.model.ConversationPhase
import com.voicedm.app.domain.model.IncomingMessage
import com.voicedm.app.domain.model.LogDirection
import com.voicedm.app.domain.model.MessageKind
import com.voicedm.app.domain.model.ReplyMode
import com.voicedm.app.domain.model.displayContactName
import com.voicedm.app.domain.queue.PendingMessageQueue
import com.voicedm.app.domain.reply.EchoGuard
import com.voicedm.app.service.audio.AudioFocusController
import com.voicedm.app.service.audio.Beep
import com.voicedm.app.service.audio.BeepPlayer
import com.voicedm.app.service.reply.SendResult
import com.voicedm.app.service.reply.TextReplyChannel
import com.voicedm.app.service.reply.VoiceNoteChannel
import com.voicedm.app.service.speech.ListenFailure
import com.voicedm.app.service.speech.ListenResult
import com.voicedm.app.service.speech.SpeechToText
import com.voicedm.app.service.tts.SpeakResult
import com.voicedm.app.service.tts.TtsSpeaker
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The conversation loop: read -> listen -> send -> repeat.
 *
 * All Android specifics live behind the ports passed in, so the control flow (retries,
 * skips, confirmations, audio interruptions) is deterministic and testable on the JVM.
 */
class ConversationEngine(
    private val scope: CoroutineScope,
    private val store: ConversationStore,
    private val pending: PendingMessageQueue,
    private val messageSignals: ReceiveChannel<Unit>,
    private val echoGuard: EchoGuard,
    private val focus: AudioFocusController,
    private val tts: TtsSpeaker,
    private val stt: SpeechToText,
    private val textReply: TextReplyChannel,
    private val voiceNote: VoiceNoteChannel,
    private val beeps: BeepPlayer,
    private val currentSettings: suspend () -> AppSettings,
    private val onRecordingChanged: (Boolean) -> Unit = {},
    private val onAutomationFailed: (String) -> Unit = {},
) {

    private val commands = Channel<EngineCommand>(Channel.UNLIMITED)
    private val gains = Channel<Unit>(Channel.CONFLATED)
    private val audioAvailable = AtomicBoolean(true)

    private var startedAtMs = 0L

    fun post(command: EngineCommand) {
        commands.trySend(command)
    }

    /** Called by the service from the audio focus callback. */
    fun onFocusEvent(lost: Boolean) {
        if (lost) {
            audioAvailable.set(false)
            if (store.state.value.phase == ConversationPhase.READING) {
                commands.trySend(EngineCommand.AUDIO_LOST)
            }
        } else {
            audioAvailable.set(true)
            gains.trySend(Unit)
        }
    }

    suspend fun run() {
        startedAtMs = System.currentTimeMillis()
        var first = true
        while (scope.isActive) {
            val oldestAllowed = if (first) {
                first = false
                pending.oldestAllowed(currentSettings().catchUpOnStart, startedAtMs)
            } else {
                Long.MIN_VALUE
            }
            for (message in pending.drain(oldestAllowed)) {
                process(message)
            }

            select<Unit> {
                messageSignals.onReceive { }
                commands.onReceive { command ->
                    if (command == EngineCommand.LISTEN) manualReply()
                }
            }
        }
    }

    // ------------------------------------------------------------------ one message

    private suspend fun process(message: IncomingMessage) {
        if (echoGuard.isEcho(message)) return

        val cfg = currentSettings()
        store.setPhase(ConversationPhase.READING)
        val displayed = message.message.text
            ?: Announcements.incoming(
                kind = message.message.kind,
                text = null,
                senderName = message.message.senderName,
                announceSender = false,
            )
        val entry = store.log(
            direction = LogDirection.INCOMING,
            text = displayed,
            kind = message.message.kind,
            timeMs = message.receivedAtMs,
        )
        store.setCurrent(entry)

        focus.request()
        announce(message.announcement(cfg.announceSender, cfg.displayContactName()))
        focus.abandon()

        if (!cfg.autoListenAfterRead) {
            store.setPhase(ConversationPhase.WAITING)
            return
        }
        performReply(message.chatName, cfg)
    }

    /** Speaks [text] while honouring skip/repeat and audio-focus interruptions. */
    private suspend fun announce(text: String) {
        var attempts = 0
        while (attempts < MAX_REREADS) {
            attempts++
            when (speakWithControls(text)) {
                SpeakOutcome.Interrupted -> {
                    if (!awaitAudioGain()) return
                    focus.request()
                    // Audio came back: say it again from the start so nothing is missed.
                }
                SpeakOutcome.Finished -> return
            }
        }
    }

    private suspend fun manualReply() {
        val cfg = currentSettings()
        val chat = cfg.targetChatName
        if (chat.isBlank()) {
            store.log(direction = LogDirection.SYSTEM, text = "Pick a contact first")
            store.setPhase(ConversationPhase.ERROR, "No contact selected")
            return
        }
        performReply(chat, cfg)
    }

    private suspend fun performReply(chatName: String, cfg: AppSettings): Boolean {
        focus.request()
        try {
            if (cfg.replyMode == ReplyMode.VOICE_NOTE) {
                return deliverVoiceNote(chatName, cfg)
            }
            val transcript = listenForReply(cfg) ?: run {
                store.setPhase(ConversationPhase.SKIPPED)
                delay(SKIP_PAUSE_MS)
                return false
            }
            if (cfg.confirmBeforeSend && !confirmTranscript(transcript)) {
                store.setPhase(ConversationPhase.SKIPPED)
                return false
            }
            return deliverText(chatName, transcript)
        } finally {
            focus.abandon()
            onRecordingChanged(false)
        }
    }

    // ------------------------------------------------------------------ listening

    private suspend fun listenForReply(cfg: AppSettings): String? {
        store.setPhase(ConversationPhase.LISTENING)
        beeps.play(Beep.LISTENING)
        onRecordingChanged(true)
        try {
            val attempts = if (cfg.retryOnNoSpeech) 2 else 1
            repeat(attempts) { attempt ->
                val result = stt.listen(
                    languageTag = cfg.recognitionLanguageTag,
                    preferOffline = cfg.offlineRecognition,
                    timeoutMs = LISTEN_TIMEOUT_MS,
                )
                when (result) {
                    is ListenResult.Heard -> return result.text
                    is ListenResult.Failed -> when (result.reason) {
                        ListenFailure.NO_SPEECH ->
                            if (attempt == attempts - 1) return null

                        ListenFailure.UNAVAILABLE -> {
                            store.log(
                                direction = LogDirection.SYSTEM,
                                text = "Speech recognition unavailable for ${cfg.recognitionLanguageTag}",
                            )
                            return null
                        }

                        ListenFailure.TIMEOUT,
                        ListenFailure.ERROR,
                        -> return null
                    }
                }
            }
            return null
        } finally {
            onRecordingChanged(false)
        }
    }

    private suspend fun confirmTranscript(transcript: String): Boolean = coroutineScope {
        store.setPhase(ConversationPhase.CONFIRMING)
        store.setPendingTranscript(transcript)
        beeps.play(Beep.CONFIRM)
        val prompt = async { tts.speak("You said. $transcript. Send it?") }

        var decision: Boolean? = null
        val deadline = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS
        while (decision == null) {
            val remaining = max(1L, deadline - System.currentTimeMillis())
            val command = withTimeoutOrNull(remaining) { commands.receive() }
            when (command) {
                EngineCommand.SEND -> decision = true
                EngineCommand.DISCARD,
                EngineCommand.SKIP,
                null,
                -> decision = false

                EngineCommand.AUDIO_LOST -> tts.stop()
                else -> Unit
            }
        }
        tts.stop()
        prompt.join()
        store.setPendingTranscript(null)
        decision == true
    }

    // ------------------------------------------------------------------ sending

    private suspend fun deliverText(chatName: String, transcript: String): Boolean {
        store.setPhase(ConversationPhase.SENDING)
        val result = textReply.send(chatName, transcript)
        return report(result, transcript, MessageKind.TEXT)
    }

    private suspend fun deliverVoiceNote(chatName: String, cfg: AppSettings): Boolean {
        store.setPhase(ConversationPhase.LISTENING)
        speakWithControls("Recording voice message. Speak after the beep.")
        beeps.play(Beep.LISTENING, force = true)
        delay(BEEP_LEAD_IN_MS)

        store.setPhase(ConversationPhase.SENDING)
        val result = voiceNote.recordAndSend(chatName, cfg.voiceNoteHoldSeconds * 1000L)
        return report(result, "Voice message", MessageKind.VOICE_NOTE)
    }

    private suspend fun report(
        result: SendResult,
        text: String,
        kind: MessageKind,
    ): Boolean {
        when (result) {
            SendResult.Sent -> {
                beeps.play(Beep.SENT)
                store.setPhase(ConversationPhase.SENT)
                store.log(direction = LogDirection.OUTGOING, text = text, kind = kind)
                if (kind == MessageKind.TEXT) echoGuard.rememberSent(text)
                delay(SENT_PAUSE_MS)
                store.setPhase(ConversationPhase.WAITING)
                return true
            }
            else -> {
                val reason = when (result) {
                    SendResult.NoNotification -> "No notification for that chat any more"
                    SendResult.NoReplyAction -> "That notification has no reply action"
                    is SendResult.Failed -> result.reason
                    SendResult.Sent -> ""
                }
                beeps.play(Beep.FAILED)
                store.setPhase(ConversationPhase.ERROR, reason)
                store.log(direction = LogDirection.SYSTEM, text = "Not sent: $reason")
                onAutomationFailed(reason)
                delay(ERROR_PAUSE_MS)
                store.setPhase(ConversationPhase.WAITING)
                return false
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private sealed interface SpeakOutcome {
        data object Finished : SpeakOutcome
        data object Interrupted : SpeakOutcome
    }

    private sealed interface SpeakEvent {
        data class Finished(val result: SpeakResult) : SpeakEvent
        data class Command(val command: EngineCommand) : SpeakEvent
    }

    private suspend fun speakWithControls(text: String): SpeakOutcome = coroutineScope {
        val job = async { tts.speak(text) }
        while (true) {
            val event = select<SpeakEvent> {
                job.onAwait { SpeakEvent.Finished(it) }
                commands.onReceive { SpeakEvent.Command(it) }
            }
            if (event is SpeakEvent.Finished) {
                return@coroutineScope if (
                    event.result == SpeakResult.Stopped && !audioAvailable.get()
                ) {
                    SpeakOutcome.Interrupted
                } else {
                    SpeakOutcome.Finished
                }
            }
            val command = (event as SpeakEvent.Command).command
            when (command) {
                EngineCommand.SKIP,
                EngineCommand.AUDIO_LOST,
                -> tts.stop()

                EngineCommand.REPEAT -> {
                    tts.stop()
                    job.join()
                    return@coroutineScope SpeakOutcome.Finished
                }

                EngineCommand.AUDIO_GAINED -> audioAvailable.set(true)
                else -> Unit
            }
        }
        @Suppress("UNREACHABLE_CODE")
        SpeakOutcome.Finished
    }

    /**
     * Waits for audio focus to come back. Returns false when it never does within the
     * timeout, so the loop can move on instead of hanging on a call.
     */
    private suspend fun awaitAudioGain(): Boolean {
        if (audioAvailable.get()) return true
        val deadline = System.currentTimeMillis() + AUDIO_GAIN_TIMEOUT_MS
        while (!audioAvailable.get()) {
            val remaining = max(1L, deadline - System.currentTimeMillis())
            if (withTimeoutOrNull(remaining) { gains.receive() } == null) return false
        }
        return true
    }

    private companion object {
        const val LISTEN_TIMEOUT_MS = 9_000L
        const val CONFIRM_TIMEOUT_MS = 30_000L
        const val AUDIO_GAIN_TIMEOUT_MS = 30_000L
        const val SENT_PAUSE_MS = 700L
        const val ERROR_PAUSE_MS = 1_500L
        const val SKIP_PAUSE_MS = 400L
        const val BEEP_LEAD_IN_MS = 250L
        const val MAX_REREADS = 2
    }
}
