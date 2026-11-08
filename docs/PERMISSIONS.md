# TaskMind AI — Android permissions

TaskMind requests a permission only when you turn on the feature that needs it. Everything can be reviewed in **Settings → Permissions & call screening**.

| Permission | Type | Feature | Requested when | If denied |
|---|---|---|---|---|
| `android.permission.POST_NOTIFICATIONS` | Runtime (Android 13+) | Reminders, briefings, call prompts, post-call review, recording indicator | “Get started” on first run | App works; you get no notifications. The capture indicator still shows inside the app. |
| `android.permission.RECORD_AUDIO` | Runtime | Voice commands, dictation, voice notes, user-started recording, call capture | First tap on a mic/capture button, or enabling call prompts | Voice and capture are unavailable; import and paste still work. |
| `android.permission.READ_PHONE_STATE` | Runtime | Detect ringing / off-hook / idle to show the capture prompt and stop capture when a call ends | Enabling “Ask to capture tasks on calls” (after the consent notice) | No call prompts; manual capture still works. |
| `android.permission.READ_CONTACTS` | Runtime, optional | Show the caller's name in the prompt | Only if you enable “Show caller names from contacts” | Prompts show the number (if known) or no name. |
| `android.permission.SCHEDULE_EXACT_ALARM` | Special access (Android 12+) | Deadline reminders at the exact minute in Doze | You allow it from the Permissions screen | Reminders use inexact alarms (may be a few minutes late). |
| `android.permission.FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | Normal | The visible capture session (ongoing notification + Stop) | Granted at install | — |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Normal | Re-schedule reminders and briefings after a reboot or app update | Granted at install | — |
| `android.permission.INTERNET` | Normal | Only used for the cloud AI provider you configure | Granted at install | — |
| `ROLE_CALL_SCREENING` | Role, optional | Gives TaskMind the incoming number for the prompt. Calls are always allowed, never blocked, silenced or modified. | Only if you tap “Allow” on that row | Prompts show no number. |

## Deliberately **not** used

- `READ_CALL_LOG`, `PROCESS_OUTGOING_CALLS` — not needed; duration is measured by TaskMind itself.
- `CAPTURE_AUDIO_OUTPUT`, `MODIFY_PHONE_STATE`, `VOICE_CALL` audio source — system-only; TaskMind never tries to access call audio.
- Accessibility Service, notification listener, overlay (`SYSTEM_ALERT_WINDOW`), root — never used.
- `READ/WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_AUDIO` — files are chosen with the system document picker, which grants access to just that file.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — TaskMind only opens the system battery settings page so you can choose.

## Visible indicators

While the microphone is in use TaskMind always shows: (1) an ongoing “● Recording / Transcribing” notification with a timer and **Stop**, (2) a red pill at the top of the app, and (3) Android's own green microphone indicator in the status bar.
