# TaskMind AI

**An Android personal secretary. Never forget anything someone asks you to do.**

TaskMind turns natural-language instructions, voice commands, captured conversations and imported call recordings into reviewed, dated, prioritised tasks — then reminds you at the right time and answers questions about your day.

- Kotlin · Jetpack Compose · Material 3 · MVVM · Hilt · Room · Coroutines/Flow · WorkManager · DataStore · Retrofit
- Min Android 10 (API 29) · target/compile API 35 · tested on an Android 14 emulator in CI · designed for Vivo phones and Android 16
- Works **fully offline** with an on-device language engine; OpenAI and Anthropic Claude are optional, with your own key

---

## Contents

1. [Features](#features)
2. [Install the APK](#install-the-apk)
3. [First-run setup](#first-run-setup)
4. [Call capture — how it really works](#call-capture--how-it-really-works)
5. [AI provider configuration](#ai-provider-configuration)
6. [Permissions](#permissions)
7. [Privacy & security](#privacy--security)
8. [Build from source](#build-from-source)
9. [Project structure](#project-structure)
10. [Testing](#testing)
11. [Known limitations](#known-limitations)

---

## Features

**AI call-to-task intelligence**
- Detects incoming and outgoing calls (`READ_PHONE_STATE` broadcast) and posts a silent notification: *“Incoming call detected. Enable AI Task Capture?”* with **Enable for this call** / **Not now**. It never covers the dialer.
- **Enable for this call** starts a visible foreground capture session (ongoing red notification with a timer and **Stop**, plus an in-app indicator). It starts when the call connects and stops automatically when it ends.
- Two capture modes: **live on-device transcription** (no audio kept) or **encrypted audio recording** (transcribed later, only with your consent).
- **Import** recordings made by your dialer (Vivo, Samsung, Xiaomi, Google dialer, etc.), **record an in-person conversation**, add a **voice note**, or **paste a transcript**.
- Extracts tasks assigned to you, commitments/promises you made, deadlines, meetings, follow-ups (including what *others* promised you), email follow-ups, deliverables, names and phone numbers.
- Shows the transcript and the AI interpretation side by side. **Every item must be approved, edited or rejected by you.** Nothing is sent, scheduled or promised on your behalf.

**Task management**
- Natural-language entry: *“Remind me to call the doctor next Wednesday at 11 AM”* → title, date, time, category, priority.
- Automatic deadlines, priority and category detection; tags (`#home`), recurring tasks (daily, weekdays, weekly, monthly), subtasks, dependencies (“blocked by”), notes, file attachments, completion tracking, overdue detection.
- Deadline reminders (exact alarms, survive reboot), heads-up reminders, custom reminders, snooze from the notification.

**AI assistant** — chat (typed or spoken, optional spoken replies) that answers from your real data:
“What should I complete today?”, “What tasks are overdue?”, “What did I promise during yesterday's calls?”, “What tasks did my manager assign?”, “What do I need to finish this week?”, “Which tasks have the highest priority?”, “Summarize my last five captured conversations.”, “What am I forgetting?” — and “Remind me to…” creates a task.

**Dashboard** — time-of-day greeting, today's progress ring, pending/overdue/this-week stats, AI daily briefing, overdue & today lists, upcoming deadlines, recent call insights, quick-add and voice buttons. Light and dark themes, optional Material You colours.

**Smart notifications** — deadline reminders, overdue alerts, morning briefing, evening unfinished-task summary, follow-up nudges for unreviewed conversations, call-capture prompts, post-call review. Each is configurable.

**Voice** — speech-to-text for commands (“Remind me to pay electricity bill tomorrow”, “Add a task to call my manager at 4 PM”, “Show my pending tasks”), dictation and assistant questions; text-to-speech replies. English (India/US/UK), Hindi and Bengali recognition.

---

## Install the APK

1. Download **`TaskMindAI.apk`** (from this conversation, the repo's *Releases* page, or `output/TaskMindAI.apk`).
2. On the phone, open it. If asked, allow *Install unknown apps* for your browser / file manager.
   - Vivo (Funtouch / OriginOS): *Settings → Security & privacy → Install unknown apps*, or confirm the prompt; Vivo may also ask you to sign in to your Vivo account or wait for a scan — choose **Install anyway**.
3. Open **TaskMind AI**, enter your name, allow notifications.

Later builds are signed with the same key, so they install over the old one and keep your data.

## First-run setup

1. **Settings → Profile**: your name and your manager's name (used by “What did my manager assign?”).
2. **Settings → Call capture → Ask to capture tasks on calls**: read and accept the consent notice; allow *Phone*, *Microphone*, *Notifications*.
3. **Settings → Permissions & call screening**:
   - *Exact alarms* → allow (on-time reminders).
   - *Background activity* → set TaskMind's battery usage to **Unrestricted** (Vivo: *Settings → Battery → Background power consumption management → TaskMind → Allow*). Without this, Vivo may delay reminders.
   - Optional: *Contacts* (caller names in the prompt), *Call screening role* (incoming number for the prompt; TaskMind never blocks or alters calls).
4. Optional: connect a cloud AI provider (below).

## Call capture — how it really works

Android does **not** allow third-party apps to read the cellular call audio stream (`VOICE_CALL`/`VOICE_DOWNLINK` require a system-only permission since Android 9/10). TaskMind follows the rules and never uses Accessibility Services, hidden APIs, root, or any workaround.

| Method | What you get | Notes |
|---|---|---|
| **Enable for this call** (prompt) | Microphone capture while the call is active | Usually hears your side only; use **speakerphone** to capture both. Many phones (including many Vivo models) silence the microphone for apps during calls. TaskMind measures the audio level and tells you honestly when nothing was captured. **Not verified on a real Vivo device** — test it on yours. |
| **Import recording** | Best quality, both sides | Use your dialer's built-in call recorder (Vivo: *Phone → Settings → Call recording*), then *Call Insights → Import recording*. The file is copied and encrypted. |
| **Record conversation** | In-person meetings, speakerphone calls | Explicit consent checkbox before starting. |
| **Voice note** | Your own summary after a call | Live on-device transcription. |
| **Paste transcript** | Any text (notes, chat, email) | Analysed the same way. |

Every capture shows a persistent notification and the red in-app indicator. Capture never starts without a tap from you. Recording laws differ by country/state — many require consent from everyone on the call; TaskMind reminds you before every recording.

Turning **audio** into text: live mode uses Android's `SpeechRecognizer` during the capture. Recorded or imported audio is transcribed with **OpenAI** (Whisper / gpt-4o-transcribe) only if you add an OpenAI key and switch on *Upload recordings for transcription*. Otherwise the recording stays encrypted on the phone and you can type the key points.

## AI provider configuration

*Settings → AI provider*

| Provider | Use | Key |
|---|---|---|
| **On-device** (default) | Rule-based engine for dates, priorities, categories, transcript extraction and assistant answers. Nothing leaves the phone. | none |
| **OpenAI** | GPT models for parsing, extraction, briefings, chat; plus audio transcription | <https://platform.openai.com/api-keys> |
| **Anthropic Claude** | Claude models for parsing, extraction, briefings, chat (Claude does not transcribe audio) | <https://console.anthropic.com/settings/keys> |

1. Choose the provider, paste the key, tap **Save key**, then **Test**.
2. Model names are editable (defaults: `gpt-4o-mini`, `whisper-1`, `claude-sonnet-4-5`). If a model is retired, type its replacement — no app update needed.
3. Switch on exactly what may be sent (*Settings → Privacy & data sharing*):
   - **Send task text** — natural-language input, task list (for briefings and chat).
   - **Send conversation transcripts** — for task extraction and assistant answers about calls.
   - **Upload recordings for transcription** — audio files to OpenAI.
4. If a cloud call fails (no network, quota, bad key), TaskMind falls back to the on-device engine and tells you.

Keys are never hard-coded. They are encrypted with AES-256-GCM using a non-exportable Android Keystore key and are excluded from backups and exports. Requests go directly from your phone to `api.openai.com` / `api.anthropic.com`; nothing is logged.

## Permissions

Each permission is requested only when the feature that needs it is turned on. See [docs/PERMISSIONS.md](docs/PERMISSIONS.md) for the full table.

| Permission | Why | When asked |
|---|---|---|
| `POST_NOTIFICATIONS` | Reminders, briefings, prompts, recording indicator | First run |
| `RECORD_AUDIO` | Voice input, voice notes, user-started capture | First voice/capture use |
| `READ_PHONE_STATE` | Detect ringing / connected / ended (no call content) | Enabling call prompts |
| `READ_CONTACTS` (optional) | Caller name in prompt | Only if you enable it |
| `FOREGROUND_SERVICE_MICROPHONE` | Visible capture session | Normal permission |
| `SCHEDULE_EXACT_ALARM` | On-time deadline reminders | You grant in system settings |
| `RECEIVE_BOOT_COMPLETED` | Re-arm reminders after restart | Normal permission |
| `INTERNET` | Only for cloud AI you configure | Normal permission |
| Call screening role (optional) | Incoming number for the prompt; calls are always allowed through | Only if you request it |

Not requested: `READ_CALL_LOG`, `PROCESS_OUTGOING_CALLS`, `CAPTURE_AUDIO_OUTPUT`, Accessibility Service, storage permissions (files are picked with the system picker).

## Privacy & security

- **Local first**: Room database in app-private storage (protected by Android's file-based encryption). Recordings are encrypted with AES-256-GCM (Android Keystore) before they are written; plaintext temp files are deleted immediately.
- **No hidden recording**: microphone use only inside a visible foreground service started by a tap; Android's own mic indicator also shows.
- **Explicit consent**: one-time call-capture notice + per-recording consent checkbox; separate switches for each data type sent to a cloud provider.
- **Deletion**: delete a recording, a transcript or a whole conversation; *Delete all recordings*; *Delete all data*.
- **Export & backup**: *Settings → Data → Export* writes a JSON file wherever you choose (transcripts optional; recordings and keys never included). *Restore* reads it back. Android cloud backup/device transfer is disabled for app data.

## Build from source

Requirements: JDK 17, Android SDK with platform 35 (Android Studio Ladybug or newer handles this).

```bash
git clone https://github.com/Abhijit425/TaskMindAI.git
cd TaskMindAI
./gradlew assembleDebug            # → app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest        # JVM tests for the language engine
./gradlew connectedDebugAndroidTest   # with a device/emulator attached
```

Or open the folder in Android Studio and press **Run**.

The debug build is signed with `keystore/taskmind-debug.jks` (password `taskmind`) so every build — local or CI — installs over the previous one. For a Play Store release, create your own keystore and change `signingConfigs` in `app/build.gradle.kts`.

**CI**: every push to `main` runs `.github/workflows/android.yml`: unit tests → APK → instrumented tests on an Android 14 emulator (with screenshots of every screen) → a GitHub Release containing `TaskMindAI.apk`. Logs, reports and screenshots are published to the `ci-results` branch.

## Project structure

```
app/src/main/java/com/taskmind/ai/
├── TaskMindApp.kt, MainActivity.kt
├── ai/            On-device NLP (LocalNlp.kt), OpenAI & Claude clients, AiEngine (provider + consent routing), AssistantEngine
├── call/          Call-state receiver, optional CallScreeningService, CaptureService (visible FGS), recorder, live transcriber, post-call worker
├── data/db/       Room entities, relations, DAOs, AppDatabase (+ exported schema in app/schemas)
├── data/repo/     Task, Category, Call, Profile repositories
├── data/prefs/    DataStore settings + encrypted API keys
├── notify/        Channels, AlarmManager reminders, receivers, WorkManager briefing/overdue/follow-up workers
├── security/      Android Keystore AES-GCM
├── backup/        JSON export/import, delete-all
├── voice/         SpeechRecognizer input, TextToSpeech
└── ui/            Compose screens: home, tasks (+ editor, quick add), assistant, calls (+ detail), settings (+ permissions), onboarding
```

Database entities: `TaskEntity`, `CategoryEntity`, `ReminderEntity`, `CallSessionEntity`, `TranscriptEntity`, `ExtractedActionEntity`, `ContactReferenceEntity`, `UserSettingsEntity`, plus `TaskDependencyEntity` and `TaskAttachmentEntity`, with foreign keys (cascade / set-null) and indices. Schema version 1 is exported to `app/schemas`; future versions add explicit `Migration`s (no destructive fallback).

## Testing

- `app/src/test/.../LocalNlpTest.kt` — 13 JVM tests: the spec's examples (doctor next Wednesday 11 AM, electricity bill tomorrow, manager at 4 PM, the QAD/client conversation → 2 high-priority Work tasks due Monday/tomorrow), relative/explicit dates, recurrence, priority, assigned-by, commitments, follow-ups, others' promises, phone numbers and emails.
- `app/src/androidTest/.../DatabaseTest.kt` — Room relations, priority ordering, cascades, dependency blocking.
- `app/src/androidTest/.../AppTourTest.kt` — drives the real app on an emulator through onboarding, dashboard, tasks, editor, natural-language quick add, assistant Q&A, call insights review/approval, settings and dark mode, saving screenshots.

## Known limitations

- **Cellular call audio** cannot be captured by third-party apps on stock Android. Microphone capture during calls depends on the phone; it has **not been verified on a real Vivo device**. Use the dialer's recorder + Import for reliable results.
- **Speech recognition** uses the phone's installed recognizer (Google on most phones). Without the offline language pack it needs a connection and audio goes to that service. Continuous live transcription restarts the recognizer between phrases, so a word can occasionally be missed and some phones play a short tone.
- **Transcribing recorded/imported audio** requires OpenAI (25 MB per file, about 1.5 hours at TaskMind's recording quality).
- **On-device engine** is rule-based and tuned for English phrasing; Hindi/Bengali transcripts are stored and searchable, but extraction quality is much better with a cloud provider. App UI text is English.
- **Speaker separation** is not automatic. Transcripts in `Name: text` form (or typed that way) let TaskMind tell your commitments from other people's.
- **Vivo battery management** can delay or kill background work; set battery usage to Unrestricted.
- **Exact alarms**: on Android 14+ exact alarms are off by default; without them reminders may arrive a few minutes late.
- **Signing**: the APK is signed with a debug-style key committed to the repo; fine for personal installs, not for Play Store distribution.
- CameraX/document capture is not included (not needed for the current features).
