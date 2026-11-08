@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.taskmind.ai.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ai.BuildConfig
import com.taskmind.ai.data.db.CaptureMode
import com.taskmind.ai.data.prefs.AiProvider
import com.taskmind.ai.data.prefs.AppSettings
import com.taskmind.ai.data.prefs.SPEECH_LANGUAGES
import com.taskmind.ai.data.prefs.ThemeMode
import com.taskmind.ai.ui.components.InfoBanner
import com.taskmind.ai.ui.components.SettingRow
import com.taskmind.ai.ui.components.SettingSwitch
import com.taskmind.ai.ui.components.TimePickDialog
import com.taskmind.ai.ui.components.TmCard
import com.taskmind.ai.ui.components.Tone
import com.taskmind.ai.ui.nav.LocalSnackbar
import com.taskmind.ai.ui.theme.LocalTm
import com.taskmind.ai.util.TimeUtil
import java.time.LocalDate

private enum class TimeTarget { REMINDER, MORNING, EVENING }

@Composable
fun SettingsScreen(onOpenPermissions: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val current = settings
    if (current != null) SettingsContent(current, onOpenPermissions, vm)
}

@Composable
private fun SettingsContent(s: AppSettings, onOpenPermissions: () -> Unit, vm: SettingsViewModel) {
    val profile by vm.profile.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val tm = LocalTm.current

    var timeTarget by remember { mutableStateOf<TimeTarget?>(null) }
    var showCallConsent by remember { mutableStateOf(false) }
    var confirmWipe by remember { mutableStateOf(false) }
    var includeTranscripts by remember { mutableStateOf(true) }

    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.consume() } }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri, includeTranscripts)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.import(uri)
    }
    val callPerms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val phoneOk = result[Manifest.permission.READ_PHONE_STATE] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        vm.enableCallPrompts(phoneOk)
        if (!phoneOk) vm.consume()
    }
    val contactsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> vm.setLookupContacts(granted) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.statusBarsPadding().padding(top = 18.dp)) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium)
            Text("TaskMind ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ------------------------------------------------ Profile
        Section("Profile") {
            var name by remember(profile?.displayName) { mutableStateOf(profile?.displayName.orEmpty()) }
            var manager by remember(profile?.managerName) { mutableStateOf(profile?.managerName.orEmpty()) }
            OutlinedTextField(
                value = name, onValueChange = { name = it; vm.setName(it) }, label = { Text("Your name") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = manager, onValueChange = { manager = it; vm.setManager(it) }, label = { Text("Your manager's name") },
                supportingText = { Text("Lets the assistant answer “What did my manager assign?”") },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
            )
        }

        // ------------------------------------------------ AI provider
        Section("AI provider") {
            AiProvider.entries.forEach { p ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = s.aiProvider == p, onClick = { vm.setProvider(p) })
                    Column(Modifier.weight(1f)) {
                        Text(p.label, style = MaterialTheme.typography.bodyLarge)
                        Text(p.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (s.aiProvider == AiProvider.OPENAI || s.cloudAudioConsent) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                KeyEditor("OpenAI API key", s.hasOpenAiKey, onSave = { vm.saveKey(AiProvider.OPENAI, it) }, onClear = { vm.clearKey(AiProvider.OPENAI) }, onTest = { vm.test(AiProvider.OPENAI) }, busy = busy)
                ModelField("Chat model", s.openAiModel, AppSettings.DEFAULT_OPENAI_MODEL, vm::setOpenAiModel)
                ModelField("Transcription model", s.transcriptionModel, AppSettings.DEFAULT_TRANSCRIPTION_MODEL, vm::setTranscriptionModel)
            }
            if (s.aiProvider == AiProvider.ANTHROPIC) {
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                KeyEditor("Anthropic API key", s.hasAnthropicKey, onSave = { vm.saveKey(AiProvider.ANTHROPIC, it) }, onClear = { vm.clearKey(AiProvider.ANTHROPIC) }, onTest = { vm.test(AiProvider.ANTHROPIC) }, busy = busy)
                ModelField("Model", s.anthropicModel, AppSettings.DEFAULT_ANTHROPIC_MODEL, vm::setAnthropicModel)
            }
        }

        // ------------------------------------------------ Privacy
        Section("Privacy & data sharing") {
            InfoBanner(
                Icons.Outlined.Shield,
                when (s.aiProvider) {
                    AiProvider.LOCAL -> "On-device mode: tasks, transcripts and recordings never leave this phone. Speech recognition uses your phone's recognizer (Google on most phones), which may process audio on Google servers unless the offline pack is installed."
                    else -> "With ${s.aiProvider.label} selected, only the data types you switch on below are sent to ${if (s.aiProvider == AiProvider.OPENAI) "api.openai.com" else "api.anthropic.com"} using your own key. Everything else stays on this phone."
                },
                Tone.INFO,
            )
            Spacer(Modifier.height(6.dp))
            SettingSwitch("Send task text", "Natural-language task input, task list and briefings are sent to the cloud model.", s.cloudTasksConsent, vm::setCloudTasks, enabled = s.aiProvider != AiProvider.LOCAL)
            SettingSwitch("Send conversation transcripts", "Transcripts are sent for task extraction and assistant answers.", s.cloudTranscriptsConsent, vm::setCloudTranscripts, enabled = s.aiProvider != AiProvider.LOCAL)
            SettingSwitch("Upload recordings for transcription", "Recorded audio is uploaded to OpenAI (needs an OpenAI key) to be transcribed. Claude does not transcribe audio.", s.cloudAudioConsent, vm::setCloudAudio)
            if (s.cloudAudioConsent && !s.hasOpenAiKey) {
                InfoBanner(Icons.Outlined.Key, "Add an OpenAI API key above to transcribe recordings.", Tone.WARNING)
            }
        }

        // ------------------------------------------------ Call capture
        Section("Call capture") {
            SettingSwitch(
                "Ask to capture tasks on calls",
                "Shows “Incoming call detected. Enable AI Task Capture?” when a call rings. Needs phone-state permission.",
                s.callPromptsEnabled,
                { on -> if (on) showCallConsent = true else vm.enableCallPrompts(false) },
            )
            Text("Capture mode", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            CaptureMode.entries.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.captureMode == m, onClick = { vm.setCaptureMode(m) })
                    Column {
                        Text(m.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (m == CaptureMode.LIVE_TRANSCRIPTION) "Text only, no audio file kept. Uses the phone's speech recognizer."
                            else "Saves an encrypted audio file. Transcribed with OpenAI if you allow it.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            SettingSwitch("Keep audio after transcription", "Off = the encrypted recording is deleted once it has been transcribed.", s.keepAudioAfterTranscription, vm::setKeepAudio)
            SettingSwitch(
                "Show caller names from contacts",
                "Optional. Needs contacts permission.",
                s.lookupContactNames,
                { on -> if (on) contactsPerm.launch(Manifest.permission.READ_CONTACTS) else vm.setLookupContacts(false) },
            )
            SettingRow("Permissions & call screening", "Open", onOpenPermissions, Icons.Outlined.Lock)
        }

        // ------------------------------------------------ Reminders & notifications
        Section("Reminders") {
            SettingRow("Default reminder time", TimeUtil.formatTime(s.defaultReminderHour, s.defaultReminderMinute), { timeTarget = TimeTarget.REMINDER })
            Text("Heads-up before timed deadlines", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 5, 15, 30, 60).forEach { m ->
                    FilterChip(selected = s.reminderLeadMinutes == m, onClick = { vm.setLead(m) }, label = { Text(if (m == 0) "None" else if (m == 60) "1 h" else "$m min") })
                }
            }
            SettingSwitch("Dates as day/month", "12/11 means 12 November (Indian/UK style).", s.dayFirstDates, vm::setDayFirst)
        }

        Section("Notifications") {
            SettingSwitch("Deadline reminders", null, s.deadlineReminders, vm::setDeadline)
            SettingSwitch("Overdue alerts", null, s.overdueAlerts, vm::setOverdue)
            SettingSwitch("Morning briefing", "At ${TimeUtil.formatTime(s.morningHour, s.morningMinute)}", s.morningBriefing, vm::setMorning)
            if (s.morningBriefing) SettingRow("Morning briefing time", TimeUtil.formatTime(s.morningHour, s.morningMinute), { timeTarget = TimeTarget.MORNING })
            SettingSwitch("Evening unfinished-task summary", "At ${TimeUtil.formatTime(s.eveningHour, s.eveningMinute)}", s.eveningSummary, vm::setEvening)
            if (s.eveningSummary) SettingRow("Evening summary time", TimeUtil.formatTime(s.eveningHour, s.eveningMinute), { timeTarget = TimeTarget.EVENING })
            SettingSwitch("Follow-up reminders", "Nudge me about conversations I haven't reviewed.", s.followUpReminders, vm::setFollowUp)
            SettingSwitch("Post-call task review", "Notify me when tasks are extracted from a conversation.", s.postCallReview, vm::setPostCall)
            Text(
                "Notifications follow Android's Do Not Disturb rules. Reminders use the “reminder” category, so they only break through DND if you allow reminders there.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Voice & language") {
            Text("Speech recognition and spoken replies", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEECH_LANGUAGES.forEach { l -> FilterChip(selected = s.speechLanguage == l.tag, onClick = { vm.setSpeechLanguage(l.tag) }, label = { Text(l.label) }) }
            }
            SettingSwitch("Speak assistant replies", "Uses the phone's text-to-speech voice.", s.speakAssistantReplies, vm::setSpeak)
        }

        Section("Appearance") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { t -> FilterChip(selected = s.themeMode == t, onClick = { vm.setTheme(t) }, label = { Text(t.label) }) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                SettingSwitch("Use wallpaper colours", "Material You dynamic colour", s.dynamicColor, vm::setDynamic)
            }
        }

        Section("Data") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = includeTranscripts, onCheckedChange = { includeTranscripts = it })
                Text("Include transcripts in backup", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { exportLauncher.launch("TaskMind-backup-${LocalDate.now()}.json") }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Backup, null); Spacer(Modifier.width(6.dp)); Text("Export")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Restore, null); Spacer(Modifier.width(6.dp)); Text("Restore")
                }
            }
            Text(
                "Backups are plain JSON files you choose where to save. Recordings and API keys are never included. Restoring replaces current data.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            TextButton(onClick = vm::deleteRecordings) { Text("Delete all recordings") }
            TextButton(onClick = { confirmWipe = true }) {
                Icon(Icons.Outlined.DeleteForever, null, tint = tm.danger); Spacer(Modifier.width(6.dp)); Text("Delete all data", color = tm.danger)
            }
        }

        Section("Known limitations") {
            Text(
                "• Android does not let third-party apps record the cellular call stream. During calls TaskMind records the microphone (best with speakerphone); some phones, including many Vivo models, silence it. TaskMind tells you when nothing was captured.\n" +
                    "• Best quality: use your dialer's built-in call recorder, then Import recording.\n" +
                    "• Vivo/Funtouch/OriginOS may stop background apps. Allow TaskMind to run in background (Settings → Battery) so reminders fire on time.\n" +
                    "• On-device mode understands English phrasing best. Cloud providers handle Hindi and Bengali transcripts better.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    timeTarget?.let { target ->
        val (h, m) = when (target) {
            TimeTarget.REMINDER -> s.defaultReminderHour to s.defaultReminderMinute
            TimeTarget.MORNING -> s.morningHour to s.morningMinute
            TimeTarget.EVENING -> s.eveningHour to s.eveningMinute
        }
        TimePickDialog(h, m, { timeTarget = null }) { hh, mm ->
            when (target) {
                TimeTarget.REMINDER -> vm.setReminderTime(hh, mm)
                TimeTarget.MORNING -> vm.setMorningTime(hh, mm)
                TimeTarget.EVENING -> vm.setEveningTime(hh, mm)
            }
        }
    }

    if (showCallConsent) {
        CallConsentDialog(
            onDismiss = { showCallConsent = false },
            onAccept = {
                showCallConsent = false
                val perms = buildList {
                    add(Manifest.permission.READ_PHONE_STATE)
                    add(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }
                callPerms.launch(perms.toTypedArray())
            },
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Delete all data?") },
            text = { Text("Every task, reminder, recording, transcript and contact note is permanently deleted from this phone. Settings and API keys are kept.") },
            confirmButton = { TextButton(onClick = { confirmWipe = false; vm.deleteEverything() }) { Text("Delete everything", color = tm.danger) } },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun CallConsentDialog(onDismiss: () -> Unit, onAccept: () -> Unit) {
    var agreed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Before you capture calls") },
        text = {
            Column {
                Text(
                    "• TaskMind never records secretly. A notification and on-screen indicator are shown for the whole capture.\n" +
                        "• Capture only starts when you tap “Enable for this call”.\n" +
                        "• Recording laws differ by country and state. Many require every participant's consent — tell the other person before you start.\n" +
                        "• TaskMind does not bypass Android's call-audio restrictions. If your phone blocks call audio, you'll be offered a voice note or recording import.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text("I will inform everyone on the call and get their consent before capturing.", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept, enabled = agreed) { Text("Continue") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
    TmCard(contentPadding = 14.dp) { content() }
}

@Composable
private fun KeyEditor(label: String, hasKey: Boolean, onSave: (String) -> Unit, onClear: () -> Unit, onTest: () -> Unit, busy: Boolean) {
    var key by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = key, onValueChange = { key = it },
        label = { Text(label) },
        placeholder = { Text(if (hasKey) "•••••••• saved — paste to replace" else "Paste your key") },
        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "Show key") } },
        supportingText = { Text(if (hasKey) "Stored encrypted on this phone" else "Never hard-coded or shared; encrypted with the Android Keystore") },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = { onSave(key); key = "" }, enabled = key.isNotBlank()) { Text("Save key") }
        if (hasKey) {
            OutlinedButton(onClick = onTest, enabled = !busy) { Text(if (busy) "Testing…" else "Test") }
            TextButton(onClick = onClear) { Text("Remove") }
        }
    }
}

@Composable
private fun ModelField(label: String, value: String, default: String, onChange: (String) -> Unit) {
    var v by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = v, onValueChange = { v = it; onChange(it) }, label = { Text(label) },
        supportingText = { Text("Default: $default") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp), shape = RoundedCornerShape(14.dp),
    )
}
