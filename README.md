# VoiceDM

Hands-free conversations for **one** Instagram DM, built only from Android system
features: notification access, text-to-speech, speech-to-text, notification inline-reply,
and (optionally) an AccessibilityService.

VoiceDM reads new Instagram messages from the notification shade out loud, listens for your
spoken answer, and sends it back — no typing, no unlocking, no unofficial Instagram API,
no login credentials, no scraping.

```
Instagram DM → notification → VoiceDM reads it aloud → listens → sends your reply → repeat
```

Since **v1.0.3** VoiceDM also ships a fully on-device **voice assistant**: say a wake phrase
("Hey Jarvis" by default) and control the whole loop hands-free — see
[Voice assistant (wake phrase)](#voice-assistant-wake-phrase).

---

## Download

Grab the prebuilt APK from the releases page:

**→ [github.com/devsurx/VoiceDM/releases](https://github.com/devsurx/VoiceDM/releases)**

Current release: **[v1.0.2](https://github.com/devsurx/VoiceDM/releases/tag/v1.0.2)** —
R8-minified, **signed**, 1.5 MB, `versionName 1.0.2`, minSdk 26 / targetSdk 37.
(v1.0.3 adds the [voice assistant](#voice-assistant-wake-phrase).)

```bash
adb install VoiceDM-1.0.2.apk      # or copy it to the phone and open it
```

Signer certificate: `CN=VoiceDM, OU=VoiceDM, O=devsurx, C=US`
(SHA-256 `6d6e4e4c0b67b8db766821262c12dbb60723c12be754cf76464b9b62fe2baee9`).

Releases are signed with the project keystore — see [Releasing](#releasing) for how that
works and what must never be committed.

---

## Contents

- [Requirements](#requirements)
- [Building](#building)
- [Setup (first run)](#setup-first-run)
- [How Mode A works](#how-mode-a-works-robust-default)
- [How Mode B works](#how-mode-b-works-optional-experimental)
- [Settings reference](#settings-reference)
- [Widget & quick-settings tile](#widget--quick-settings-tile)
- [Voice assistant (wake phrase)](#voice-assistant-wake-phrase)
- [Privacy](#privacy)
- [Architecture](#architecture)
- [Releasing](#releasing)
- [Tests](#tests)
- [Known limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)

---

## Requirements

| | |
|---|---|
| Android | 8.0 (API 26) or newer, tested up to SDK 37 |
| Instagram | Any current version (`com.instagram.android`) |
| Languages | Any language your device's TTS/STT engines support |

Nothing else: no account is shared with VoiceDM, no network access at all.

## Building

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:testDebugUnitTest  # JVM unit tests
./gradlew :app:assembleRelease    # signed release APK (see Releasing)
```

Requirements: JDK 17+ and an Android SDK with `platforms;android-37` (the path goes into
`local.properties` as `sdk.dir`). If the build machine has several JDKs, point
`JAVA_HOME` at a JDK 17/21 before invoking Gradle.

## Setup (first run)

VoiceDM shows a four-step setup; each step deep-links to the exact system screen and
ticks itself off when you come back:

1. **Notification access** — lets VoiceDM read the Instagram DM notification and use its
   reply action. *Required.*
2. **Microphone** (plus *post notifications* on Android 13+) — the microphone transcribes
   your spoken reply; the notification permission lets the status notification appear.
   *Required.*
3. **Accessibility (Mode B, optional)** — only needed for voice-note replies. Skip it and
   VoiceDM runs entirely in Mode A. *Optional.*
4. **Pick your contact** — the one chat VoiceDM is allowed to answer. Type the name
   exactly as Instagram shows it in notifications, or tap one it has already seen.
   *Required.*

Then tap the big **Start** button. While running, VoiceDM also offers a battery-optimisation
exemption so the loop survives with the screen off.

### Instagram side (do this once)

* The chat must be **unmuted** and have **notifications enabled** — VoiceDM can only see
  notifications Instagram actually posts.
* Turn on *Settings → Privacy → Notifications → Messages* (wording varies by version).
* Group chats work too: pick the group's display name as the contact.

## How Mode A works (robust, default)

1. `VoiceDmNotificationListener` (a `NotificationListenerService`) filters posts from
   `com.instagram.android` and converts each one into a plain `NotificationSnapshot`.
2. `NotificationParser` reads the MessagingStyle payload (`EXTRA_MESSAGES`,
   `EXTRA_CONVERSATION_TITLE`, …) or falls back to the flat `EXTRA_TEXT` shape, classifies
   media (voice note / photo / video / reel / story mention) and drops your own messages.
3. `ChatMatcher` keeps only the configured contact; `MessageDeduper` throws away the
   re-posts Instagram sends every time it bumps a notification.
4. The engine speaks the message (`TTS`), beeps, then starts `SpeechRecognizer`.
5. Your transcript is handed to `InlineReplySender`, which finds the notification's
   `Notification.Action` carrying a `RemoteInput`, re-validates it against the live
   notification and fires it with your text — exactly the same code path as typing into
   the notification shade's Reply box.
6. Beep, log, wait for the next message. Repeat.

Because Mode A only touches documented Android APIs, it keeps working with the screen off
and the phone locked.

**Flow:** notification → read → beep → listen → inline-reply → beep → wait.

## How Mode B works (optional, experimental)

Mode B exists for one reason: Android gives an app no supported way to inject audio into
Instagram's own microphone, so the only way to send a *real voice note* is to drive
Instagram itself.

When you select Mode B in Settings (and enable the accessibility service):

1. `VoiceDmAccessibilityService` opens the chat — first through the
   `instagram://direct/inbox` deep link, then by finding the conversation row in the
   inbox list by its text/content-description.
2. It looks for the record button with three layered strategies: known view IDs, content
   description keywords, and view-ID suffixes.
3. It performs a **hold gesture** for `voiceNoteHoldSeconds` (default 8 s) while you speak
   after the beep, then releases so Instagram sends the voice note.
4. Every lookup is bounded by a timeout and ends in a human-readable failure
   notification instead of hanging the conversation loop.

**Mode B requirements and limits**

* The screen must be **on and unlocked** — Android refuses to inject gestures into a
  locked/off display. VoiceDM opens a transparent `WakeActivity` to turn the screen on,
  but it cannot unlock your phone for you.
* It **records live speech** through Instagram; there is no supported way to inject a
  pre-recorded clip into Instagram's mic on a non-rooted device.
* It is fragile by nature: Instagram's view IDs and labels change between releases, and
  every lookup may need a new fallback.
* Mode A is completely unaffected by any of this — Mode B code is never touched unless
  you switch modes and grant the accessibility permission.

## Settings reference

| Setting | What it does |
|---|---|
| Contact | The only chat VoiceDM answers — type it, tap a recently-seen chat, **Use last notification** (fills from the newest notification) or **Clear** |
| Nickname | Display name shown instead of the chat name (title, notification, widget, tile); replies still use the real name |
| Mode A / Mode B | Reply delivery: notification inline-reply vs. Instagram voice note |
| Solo yap mode | One-way monologue: beep → record → send → repeat until you pause. Incoming replies are logged but never read aloud (needs the Mode B accessibility service) |
| Ask before sending | Speak the transcript back; send only on confirmation (Send/Discard in app or notification) |
| Listen automatically after reading | Turn off to trigger listening with the headset button or the notification's *Reply* action |
| Headset button controls the loop | Play/pause/media keys skip, repeat or start listening |
| Announce who is talking | Prefix messages with "`<name>` says" |
| Retry once when nothing was heard | One re-listen on silence, then the loop moves on |
| Read messages that arrived while stopped | Catch-up window on Start |
| Reading speed | TTS rate 0.5×–2.0× |
| Speech / Recognition language | BCP-47 tags for TTS and STT (e.g. `en-US`, `de-DE`, `hi-IN`) |
| Prefer on-device recognition | Ask the speech service for an offline recogniser first |
| Hold record for *n* s | Mode B gesture length (3–30 s) |
| Permissions | Live status + jump to each system screen |

Confirm mode's actions are also available as notification actions (**Send** / **Discard**)
and **Skip**/**Repeat** while reading, so the phone can stay in your pocket.

## Widget & quick-settings tile

* **Home-screen widget** shows the contact and current phase; one tap starts or stops the
  loop (starting routes through the app, because Android does not allow a widget to start
  a foreground service from the background).
* **Quick-settings tile** toggles the loop from the shade and shows the contact while
  active.

Both update whenever the conversation state changes.

## Voice assistant (wake phrase)

VoiceDM ships a lightweight, fully on-device assistant so the conversation loop can be
driven entirely by voice — no cloud assistant, no extra app:

```
"Hey Jarvis" → chime → "reply … sure, on my way" → ack tone → sent
```

### Stage 1 (v1.0.3): the wake detector

* A **`microphone`-type foreground service** (`AssistantService`) keeps a 16 kHz capture
  loop alive with a persistent notification carrying **Pause / Resume / Stop** buttons.
* Audio is streamed through a 1280-sample hop (80 ms) into the models from
  **[openWakeWord](https://github.com/dscripka/openWakeWord)** (Apache-2.0), ported
  bit-exact to LiteRT/TFLite in Kotlin.
* An **energy VAD gate** keeps idle CPU low: the melspectrogram runs every frame to keep
  the window alignment intact, while the more expensive embedding + keyword head only run
  while sound is above an adaptive noise floor. On voice onset up to 16 missed windows are
  back-filled so the first word isn't lost.
* Trigger rules: peak-hold smoothing → score threshold → **patience of 2 consecutive
  frames** → 2 s cooldown. The wake detector is gated off while TTS is speaking so
  VoiceDM never hears itself.
* Starts only from a visible app/tile/widget/notification (Android 12+ forbids background
  microphone-service starts); `BOOT_COMPLETED` restarts it only when the system allows it,
  otherwise a one-tap **Resume listening** notification is posted instead.
* The **Assistant screen** (mic icon in the top bar) has: the master toggle, a live state
  dot, the live detector readout (`score · frame ms · % of one core`), a **Test wake word**
  button, the phrase picker, the sensitivity slider with its computed threshold, the
  wake-ups counter (spot false triggers, reset after tuning), the command vocabulary, mic
  and battery setup rows, and a privacy card.

### Audio state machine

One `AudioSessionManager` owns the microphone, and one state machine
(`AssistantStateMachine`) owns every transition — unit-tested edge by edge:

```
 STOPPED
    │  start (app / tile / widget / notification / boot)
    ▼
 IDLE_WAKE_LISTENING ──── pause / focus lost / conversation reading ────┐
    │   melspec every frame; VAD gates embedding + keyword head         │
    │   wake phrase (patience 2 frames, 2 s cooldown) → chime           │
    ▼                                                                   │
 COMMAND_LISTENING ── 5 s timeout ──────────────────────────────────────┤
    │ understood → ack tone        │ failed → "sorry…" + 1 retry        │
    ▼                             └──────────────► stays here (max 1)   │
 EXECUTING ── run the command ──────────────────────────────────────────┤
    │ finished ─────────────────────────────────────────────────────────┘
    │ speaks                          │ replies
    ▼                                 ▼
 TTS_SPEAKING        RECORDING_REPLY ──► EXECUTING
 (wake detector gated during TTS)
```

`PAUSED` (toggle/notification/focus loss) releases the microphone entirely; resume returns
to `IDLE_WAKE_LISTENING`.

### Wake engine and model sizes

`openWakeWord` was chosen because it is Apache-2.0, purpose-built for always-on on-device
use, and shares one melspectrogram + embedding backbone across phrases — so a new phrase
is a single small file. The Kotlin port in `assistant/wakeword/OpenWakeWordEngine.kt` was
validated **bit-exact** against the reference Python implementation (identical input audio
→ score correlation 1.000000, max absolute difference 0.00, identical mel buffers).

Models in `app/src/main/assets/models/` (16 kHz mono input):

| File | Size | Role |
|---|---|---|
| `melspectrogram.tflite` | 745 KiB | audio → mel features *(shared)* |
| `embedding_model.tflite` | 1.27 MiB | mel → embedding *(shared)* |
| `hey_jarvis_v0.1.tflite` | 1.13 MiB | keyword head — **default phrase** |
| `hey_mycroft_v0.1.tflite` | 840 KiB | keyword head |
| `alexa_v0.1.tflite` | 835 KiB | keyword head |
| `hey_rhasspy_v0.1.tflite` | 406 KiB | keyword head |
| **Total** | **5.16 MiB (5.41 MB)** | |

Default sensitivity **0.5 → threshold 0.525** (`threshold = 0.75 − 0.45 × sensitivity`),
right at openWakeWord's documented working point.

**APK impact:** the models are stored uncompressed (5.41 MB) plus the LiteRT native library
for both ABIs (`libtensorflowlite_jni.so`, 3.48 MiB arm64 + 2.26 MiB armv7), so the release
APK grows from **1.5 MB to 12.8 MB** (+11.33 MB — dex/code growth itself is ~0). An
arm64-only build would land at ~10.5 MB at the cost of 32-bit devices.

**Calibration:** open the Assistant screen, tap **Test wake word** and say your phrase out
loud — the score updates live. Peaks should clear the displayed threshold with margin;
nudge the slider until they do (or until false triggers stop, watch the wake-ups counter).

### Replacing or retraining the wake phrase

There is no pretrained "Hey Echo" model — the four bundled phrases are the ones
openWakeWord publishes. To use your own phrase:

1. Train it with the openWakeWord pipeline (it reuses the same shared backbone, so a
   phrase costs only a small keyword-head file).
2. Export it to TFLite and drop it into `app/src/main/assets/models/`
   (`noCompress "tflite"` is already configured).
3. Register it in `WakePhrase.BUNDLED`
   (`app/src/main/java/com/voicedm/app/assistant/wakeword/WakeWordEngine.kt`) — one line —
   and point `DEFAULT_ID` at it. That file is the single place the vocabulary lives.
4. Any phrase trained with the stock pipeline fits the shapes the engine expects
   (keyword head `[1,16,96]` → `[1,1]`); re-run **Test wake word** to re-calibrate the
   threshold.

### Stages 2–4 (roadmap)

Stage 2 adds fixed-vocabulary command recognition and `CommandRouter` (synonyms editable
in one file), Stage 3 wires the assistant into the conversation loop, Stage 4 tunes
battery/notifications/debug tooling. The final command vocabulary is already listed on the
Assistant screen.

## Privacy

* **No `INTERNET` permission.** It is not in the manifest, so nothing can leave the device
  — not notifications, not transcripts, not audio, not telemetry.
* **On-device speech only.** Wake detection runs the bundled TFLite models on the CPU;
  transcriptions use Android's system speech recogniser (with an on-device-first
  preference). No cloud speech APIs anywhere.
* **No audio ever touches disk.** Microphone frames live in ring buffers in RAM for the
  lifetime of the capture loop and are discarded; there is no recording, no cache, no
  temp file.
* **Message text stays in the process.** Notifications are parsed in memory and shown in
  the in-app log; only *settings* (contact name, toggles, sensitivity, counters) are
  persisted, in app-private DataStore.
* The same rules apply to the conversation loop and the assistant alike — see the Privacy
  card on the Assistant screen for the same statement in-app.

## Architecture

```
app/src/main/java/com/voicedm/app/
├── domain/            # pure Kotlin, zero Android imports, fully unit-testable
│   ├── model/         # settings, conversation state, message kinds
│   ├── parse/         # NotificationParser, ChatMatcher, MessageDeduper
│   ├── queue/         # PendingMessageQueue (messages that arrive while busy)
│   └── reply/         # EchoGuard (never read back our own sent text)
├── data/              # DataStore settings, discovered chats, AppContainer (hand-rolled DI)
├── service/
│   ├── ConversationEngine.kt   # the state machine driving the loop
│   ├── VoiceDmService.kt       # foreground service, media session, notification
│   ├── VoiceDmNotificationListener.kt
│   ├── NotificationSnapshotFactory.kt  # Android Notification -> pure snapshot
│   ├── audio/ speech/ tts/     # beeps, audio focus, TTS, STT
│   ├── reply/                  # InlineReplySender (A), VoiceNoteSender (B)
│   ├── VoiceDmAccessibilityService.kt  # Mode B
│   └── WakeActivity.kt
├── assistant/         # voice assistant (Stage 1: wake detector)
│   ├── wakeword/      # WakeWordEngine + openWakeWord TFLite port, WakeTriggerPolicy, phrases
│   ├── audio/         # EnergyVad, AudioSessionManager (sole mic owner), AssistantStateMachine
│   ├── commands/      # vocabulary + synonyms in one file (router arrives in Stage 2)
│   ├── AssistantService.kt      # microphone-type FGS: capture loop, notification, boot
│   ├── AssistantBootReceiver.kt
│   └── AssistantRuntime.kt      # assistant UI state store
├── ui/                # Compose: onboarding, main screen, settings, assistant + ViewModels
└── widget/ tile/      # home-screen widget, quick-settings tile
```

Design rules used throughout:

* **One adapter boundary.** Only `NotificationSnapshotFactory` touches Android notification
  objects; everything downstream works on plain data classes, which is why the parser and
  dedupe logic can be tested on the JVM without Robolectric.
* **Single state owner.** The engine writes to `ConversationStore`; UI, widget, tile and
  the persistent notification all read the same `StateFlow`. The assistant mirrors the
  pattern with `AssistantRuntimeStore`.
* **One microphone owner.** `AudioSessionManager` is the only class that opens an
  `AudioRecord`; the conversation loop and the assistant take turns through the state
  machine instead of competing for the mic.
* **No DI framework / no KSP / no Room.** `AppContainer` is ~30 lines and everything else
  is constructor-injected.

## Releasing

Release builds are signed automatically when the signing material is present:

| File | Purpose | In git? |
|---|---|---|
| `keystore/voicedm-release.jks` | The signing key (PKCS12, alias `voicedm`) | ❌ ignored |
| `keystore.properties` | `storeFile`, `storePassword`, `keyAlias`, `keyPassword` | ❌ ignored |

`app/build.gradle.kts` reads `keystore.properties` from the project root. **If the file is
missing** (a fresh clone, CI without secrets) the release build type simply gets no signing
config and produces an unsigned APK instead of failing the build.

Cutting a release:

```bash
./gradlew :app:assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk  # sanity check
gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk \
     --title "VoiceDM X.Y.Z" --notes-file notes.md
```

> ⚠️ **Back up `keystore/voicedm-release.jks` and its password somewhere safe, offline.**
> Android only accepts updates signed with the same key — lose them and every future build
> becomes a different app that cannot be installed over the installed one. Never commit
> either file (`.gitignore` already blocks `keystore/`, `*.jks`, `*.keystore` and
> `keystore.properties`).

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

71 tests cover the parts that are easy to regress:

* `NotificationParserTest` — MessagingStyle vs. flat notifications, own-message filtering,
  media classification, group summaries, reply-action index, chat-name resolution.
* `MessageDeduperTest` — exact-key dedupe, the fuzzy window for messages whose key changes
  on every re-post, window expiry, LRU capacity, cross-chat isolation.
* `ChatMatcherTest` — normalization (case, whitespace, zero-width characters, trailing
  colons) and the deliberate *non*-fuzziness ("Jon" must never match "Jonathan").
* `AssistantStateMachineTest` — every legal edge of the assistant state machine, the
  single-retry limit, the wake gate during TTS, pause/focus/stop, invalid events as no-ops.
* `EnergyVadTest` — silence keeps the gate closed, onset opens it within one frame,
  hysteresis + hangover, and the noise floor never adapts to speech.
* `WakeTriggerPolicyTest` — sensitivity→threshold mapping, patience, cooldown, VAD gating,
  peak-hold smoothing, reset semantics.

## Known limitations

1. **Instagram's notification format changes.** VoiceDM parses documented Android
   notification structures only, but Instagram controls the strings it puts in them. If
   Instagram renames the chat title or changes the message placeholder wording, parsing
   may need a matching update. Nothing in the app reverse-engineers private protocols.
2. **Mode B is best-effort automation.** It depends on Instagram's view IDs, content
   descriptions and layout; any app update can break the lookup. It also needs the screen
   on and unlocked, records only live speech, and cannot send a pre-recorded file.
3. **The chat must be unmuted.** VoiceDM sees exactly what Android's notification shade
   sees — no notification, no conversation.
4. **One contact only.** By design: the app never answers any other chat, even if
   notifications from them arrive.
5. **Headset/media buttons are shared.** The media session only receives headset events
   while VoiceDM holds audio focus; another music app that grabs focus will take the
   buttons with it. When that happens the loop pauses and resumes on its own.
6. **Android 15+ background starts.** Starting the loop always happens through the app,
   the widget or the tile while something of yours is on screen — VoiceDM never tries to
   start a foreground service from a cold background state.
7. **Confirm mode is off by default** (per the product decision: replies are sent without
   confirmation). Turn on *Ask before sending* if you want to hear the transcript first.
8. **OEM battery killers.** Aggressive skins — Xiaomi (MIUI/HyperOS), Oppo/Realme
   (ColorOS), Vivo, Samsung One UI — happily murder background microphone services. Allow
   auto-start / "unrestricted" battery usage for VoiceDM and lock it out of task cleaners;
   otherwise the assistant stops waking after a while. If it did, the **Resume listening**
   notification restarts it in one tap.
9. **Google Assistant hotword conflict.** Two detectors sharing one microphone will fight:
   Google may steal the mic or react first. Turn off *Hey Google* detection
   (Google Assistant settings → Hey Google & Voice Match) or pick a different bundled
   phrase.
10. **Noisy environments.** The energy gate and the model both degrade when everything
    around you is loud — expect more misses and some false triggers; tune the sensitivity
    slider (the wake-ups counter tells you if the new setting helps).
11. **Always-on listening costs battery.** The VAD gate keeps idle cost low, but a
    background mic + per-frame DSP is not free; the Assistant screen's live `% of one core`
    readout shows the real cost on your device.

---

## Troubleshooting

| Symptom | What to do |
|---|---|
| Wake phrase never fires | Assistant screen → **Test wake word**, speak in a quiet room, watch the score. Raise sensitivity until peaks clear the displayed threshold. Check the mic permission and battery exemption rows. |
| Fires while the TV/conversation is on | Lower the sensitivity (threshold rises), watch the wake-ups counter to confirm the change helps. |
| Works only while the app is open | See *limitations 8* (OEM battery managers) — grant auto-start / unrestricted battery to VoiceDM. |
| "Hey Google" reacts too, or VoiceDM stops waking | See *limitations 9* — disable Google's hotword or change VoiceDM's phrase. |
| Service stops after a while | Android 12+ restricts background microphone services; keep the battery exemption granted. The persistent notification's **Resume** button restarts the loop immediately. |
| Notification not read aloud | Notification access granted, chat unmuted with notifications on, contact name matches exactly (see [Setup](#setup-first-run)). |
| Command does nothing after the chime | Expected in Stage 1 — command recognition ships in Stage 2. |

---

VoiceDM is an independent tool, not affiliated with Instagram or Meta. It uses only
Android's public notification, speech and accessibility APIs.
