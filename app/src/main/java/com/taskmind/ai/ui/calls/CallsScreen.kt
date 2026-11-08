@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmind.ai.ui.calls

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.PhoneInTalk
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.call.CaptureService
import com.taskmind.ai.call.CaptureState
import com.taskmind.ai.call.CaptureStateHolder
import com.taskmind.ai.data.db.CallDirection
import com.taskmind.ai.data.db.CallSessionWithDetails
import com.taskmind.ai.data.db.CaptureMode
import com.taskmind.ai.data.db.TranscriptSource
import com.taskmind.ai.data.db.pendingCount
import com.taskmind.ai.data.db.transcriptText
import com.taskmind.ai.data.prefs.AppSettings
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.notify.WorkScheduler
import com.taskmind.ai.ui.components.EmptyState
import com.taskmind.ai.ui.components.InfoBanner
import com.taskmind.ai.ui.components.LevelBars
import com.taskmind.ai.ui.components.RecordingDot
import com.taskmind.ai.ui.components.SectionHeader
import com.taskmind.ai.ui.components.TmCard
import com.taskmind.ai.ui.components.Tone
import com.taskmind.ai.ui.home.CallInsightCard
import com.taskmind.ai.ui.nav.LocalSnackbar
import com.taskmind.ai.ui.theme.LocalTm
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SessionFilter(val label: String) { ALL("All"), REVIEW("To review"), CALLS("Calls"), CONVERSATIONS("Conversations & notes"), IMPORTED("Imported") }

@HiltViewModel
class CallsViewModel @Inject constructor(
    private val calls: CallRepository,
    private val scheduler: WorkScheduler,
    captureState: CaptureStateHolder,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val capture: StateFlow<CaptureState> = captureState.state
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    val query = MutableStateFlow("")
    val filter = MutableStateFlow(SessionFilter.ALL)

    val sessions: StateFlow<List<CallSessionWithDetails>> = combine(calls.observeSessions(), query, filter) { all, q, f ->
        val ql = q.trim().lowercase()
        all.filter { s ->
            val d = s.session.direction
            val byFilter = when (f) {
                SessionFilter.ALL -> true
                SessionFilter.REVIEW -> s.pendingCount > 0
                SessionFilter.CALLS -> d == CallDirection.INCOMING || d == CallDirection.OUTGOING
                SessionFilter.CONVERSATIONS -> d == CallDirection.IN_PERSON || d == CallDirection.VOICE_NOTE || d == CallDirection.MANUAL_TRANSCRIPT
                SessionFilter.IMPORTED -> d == CallDirection.IMPORTED
            }
            byFilter && (ql.isEmpty() ||
                s.session.title.lowercase().contains(ql) ||
                s.session.contactName?.lowercase()?.contains(ql) == true ||
                s.session.summary?.lowercase()?.contains(ql) == true ||
                s.transcriptText.lowercase().contains(ql) ||
                s.actions.any { it.title.lowercase().contains(ql) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun import(uri: Uri): Long {
        val id = calls.importRecording(uri, consentConfirmed = true)
        scheduler.enqueueSessionProcessing(id)
        return id
    }

    fun pasteTranscript(contact: String, text: String, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val s = settings.value
            val id = calls.createTextSession(
                title = if (contact.isBlank()) "Conversation notes" else "Notes · $contact",
                text = text,
                direction = CallDirection.MANUAL_TRANSCRIPT,
                source = TranscriptSource.MANUAL,
                language = s.speechLanguage,
                contactName = contact,
            )
            scheduler.enqueueSessionProcessing(id)
            onCreated(id)
        }
    }
}

private enum class PendingCapture { CONVERSATION, VOICE_NOTE, IMPORT }

@Composable
fun CallsScreen(onOpenCall: (Long) -> Unit, onOpenPermissions: () -> Unit, vm: CallsViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current

    var consentFor by remember { mutableStateOf<PendingCapture?>(null) }
    var contactForCapture by remember { mutableStateOf("") }
    var showPaste by remember { mutableStateOf(false) }
    var micDenied by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val id = vm.import(uri)
                onOpenCall(id)
            } catch (e: Exception) {
                snackbar.showSnackbar("Import failed: ${e.message}")
            }
        }
    }

    var afterMicGrant by remember { mutableStateOf<(() -> Unit)?>(null) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterMicGrant?.invoke() else micDenied = true
        afterMicGrant = null
    }

    fun withMic(block: () -> Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) block()
        else {
            afterMicGrant = block
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 18.dp)) {
                Text("Call Insights", style = MaterialTheme.typography.headlineMedium)
                Text("Everything you were asked to do — captured, never secretly.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (!settings.callPromptsEnabled) {
            item {
                InfoBanner(Icons.Outlined.PhoneInTalk, "Turn on call detection to get an “Enable AI Task Capture?” prompt when a call starts.", Tone.INFO) {
                    TextButton(onClick = onOpenPermissions, contentPadding = PaddingValues(0.dp)) { Text("Set up call capture") }
                }
            }
        }

        if (micDenied) {
            item { InfoBanner(Icons.Outlined.Info, "Microphone permission was denied. You can still import recordings or paste a transcript.", Tone.WARNING) }
        }

        item { ActiveCaptureCard(capture) { CaptureService.stop(context) } }

        if (capture is CaptureState.Idle) {
            item {
                TmCard {
                    Text("New capture", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CaptureButton(Icons.Outlined.Forum, "Record\nconversation", Modifier.weight(1f)) { consentFor = PendingCapture.CONVERSATION }
                        CaptureButton(Icons.Outlined.KeyboardVoice, "Voice\nnote", Modifier.weight(1f)) {
                            withMic { CaptureService.startManual(context, CallDirection.VOICE_NOTE, CaptureMode.LIVE_TRANSCRIPTION, null) }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CaptureButton(Icons.Outlined.FileUpload, "Import\nrecording", Modifier.weight(1f)) { consentFor = PendingCapture.IMPORT }
                        CaptureButton(Icons.Outlined.ContentPaste, "Paste\ntranscript", Modifier.weight(1f)) { showPaste = true }
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = query, onValueChange = { vm.query.value = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search people, transcripts, tasks") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Outlined.Close, "Clear") } },
                singleLine = true, shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedBorderColor = Color.Transparent,
                ),
            )
        }
        item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SessionFilter.entries) { f -> FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(f.label) }) }
            }
        }

        item { SectionHeader("Recent (${sessions.size})") }
        if (sessions.isEmpty()) {
            item {
                EmptyState(
                    Icons.Outlined.PhoneInTalk,
                    if (query.isNotBlank() || filter != SessionFilter.ALL) "No matches" else "No captured conversations",
                    "When a call or conversation is captured, its transcript, summary and extracted tasks appear here.",
                )
            }
        }
        items(sessions, key = { it.session.id }) { s -> CallInsightCard(s) { onOpenCall(s.session.id) } }
    }

    consentFor?.let { kind ->
        var agreed by remember(kind) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { consentFor = null },
            title = { Text(if (kind == PendingCapture.IMPORT) "Import a recording" else "Record this conversation") },
            text = {
                Column {
                    Text(
                        if (kind == PendingCapture.IMPORT)
                            "Pick a call recording made by your phone's dialer (on Vivo: File Manager → Recordings → Call). It is copied into TaskMind and encrypted."
                        else
                            "TaskMind records with your microphone and shows a recording notification the whole time. Use speakerphone if this is a call.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (kind == PendingCapture.CONVERSATION) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = contactForCapture, onValueChange = { contactForCapture = it }, label = { Text("Who is it with? (optional)") }, singleLine = true)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                        Text("Everyone in this conversation knows it is being recorded and has agreed.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = agreed, onClick = {
                    consentFor = null
                    when (kind) {
                        PendingCapture.IMPORT -> importLauncher.launch(arrayOf("audio/*", "video/mp4", "application/ogg"))
                        PendingCapture.CONVERSATION -> withMic {
                            CaptureService.startManual(context, CallDirection.IN_PERSON, null, contactForCapture.trim().ifEmpty { null })
                            contactForCapture = ""
                        }
                        PendingCapture.VOICE_NOTE -> withMic { CaptureService.startManual(context, CallDirection.VOICE_NOTE, CaptureMode.LIVE_TRANSCRIPTION, null) }
                    }
                }) { Text(if (kind == PendingCapture.IMPORT) "Choose file" else "Start recording") }
            },
            dismissButton = { TextButton(onClick = { consentFor = null }) { Text("Cancel") } },
        )
    }

    if (showPaste) {
        var who by remember { mutableStateOf("") }
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPaste = false },
            title = { Text("Paste or type a transcript") },
            text = {
                Column {
                    OutlinedTextField(value = who, onValueChange = { who = it }, label = { Text("Who was it with?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                        placeholder = { Text("Abhijit, please complete the QAD customization by Monday. Also send the updated document to the client tomorrow.") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    showPaste = false
                    vm.pasteTranscript(who.trim(), text) { onOpenCall(it) }
                }) { Text("Analyze") }
            },
            dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CaptureButton(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = modifier.height(76.dp), shape = RoundedCornerShape(20.dp), contentPadding = PaddingValues(10.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun ActiveCaptureCard(state: CaptureState, onStop: () -> Unit) {
    val tm = LocalTm.current
    when (state) {
        is CaptureState.Active -> {
            val elapsed by produceState(0L, state.startedAt) {
                while (true) {
                    value = (System.currentTimeMillis() - state.startedAt) / 1000
                    delay(1000)
                }
            }
            TmCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RecordingDot()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        (if (state.mode == CaptureMode.LIVE_TRANSCRIPTION) "Transcribing" else "Recording") + " · ${TimeUtil.formatDuration(elapsed)}",
                        style = MaterialTheme.typography.titleSmall, color = tm.danger, modifier = Modifier.weight(1f),
                    )
                    if (state.mode == CaptureMode.AUDIO_RECORDING) LevelBars(state.level, color = tm.danger)
                }
                Spacer(Modifier.height(6.dp))
                Text(state.title, style = MaterialTheme.typography.titleMedium)
                if (state.preview.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text("…" + state.preview.takeLast(240), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                } else if (state.isCall) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Many phones block call audio for apps like TaskMind. If nothing is captured, you'll be offered a voice note or recording import afterwards.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = tm.danger), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Stop and analyze")
                }
            }
        }
        is CaptureState.Armed -> InfoBanner(Icons.Outlined.PhoneInTalk, "AI Task Capture is armed and will start when the call connects.", Tone.INFO) {
            TextButton(onClick = onStop, contentPadding = PaddingValues(0.dp)) { Text("Cancel") }
        }
        CaptureState.Finishing -> InfoBanner(Icons.Outlined.Info, "Saving and analyzing the capture…", Tone.INFO)
        CaptureState.Idle -> Box(Modifier) {}
    }
}
