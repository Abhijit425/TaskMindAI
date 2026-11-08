@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.taskmind.ai.ui.calls

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.ai.AiEngine
import com.taskmind.ai.data.db.ActionStatus
import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.CallSessionWithDetails
import com.taskmind.ai.data.db.ExtractedActionEntity
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.SessionStatus
import com.taskmind.ai.data.db.TranscriptSource
import com.taskmind.ai.data.db.transcriptText
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.notify.WorkScheduler
import com.taskmind.ai.ui.components.DatePickDialog
import com.taskmind.ai.ui.components.InfoBanner
import com.taskmind.ai.ui.components.Pill
import com.taskmind.ai.ui.components.PriorityPill
import com.taskmind.ai.ui.components.SectionHeader
import com.taskmind.ai.ui.components.TimePickDialog
import com.taskmind.ai.ui.components.TmCard
import com.taskmind.ai.ui.components.Tone
import com.taskmind.ai.ui.nav.LocalSnackbar
import com.taskmind.ai.ui.theme.LocalTm
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

@HiltViewModel
class CallDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val calls: CallRepository,
    private val scheduler: WorkScheduler,
    private val ai: AiEngine,
    private val settings: SettingsRepository,
) : ViewModel() {
    val id: Long = savedState.get<Long>("id") ?: 0L
    val session: StateFlow<CallSessionWithDetails?> = calls.observeSession(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _canTranscribe = MutableStateFlow(false)
    val canTranscribe: StateFlow<Boolean> = _canTranscribe.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch { _canTranscribe.value = ai.canTranscribeAudio() }
    }

    fun consume() { _message.value = null }

    fun approve(a: ExtractedActionEntity) = viewModelScope.launch {
        calls.approveAction(a.id)
        _message.value = if (a.type.actionable) "Added to your tasks: ${a.title}" else "Saved"
    }

    fun reject(a: ExtractedActionEntity) = viewModelScope.launch { calls.rejectAction(a.id) }
    fun restore(a: ExtractedActionEntity) = viewModelScope.launch { calls.updateAction(a.copy(status = ActionStatus.PENDING)) }

    fun approveAll() = viewModelScope.launch {
        val n = calls.approveAll(id)
        _message.value = if (n == 0) "Nothing left to approve" else "Added $n task${if (n > 1) "s" else ""}"
    }

    fun saveEdit(a: ExtractedActionEntity, approveAfter: Boolean) = viewModelScope.launch {
        calls.updateAction(a)
        if (approveAfter) {
            calls.approveAction(a.id)
            _message.value = "Added to your tasks: ${a.title}"
        }
    }

    fun reanalyze() = viewModelScope.launch {
        calls.setStatus(id, SessionStatus.PROCESSING)
        scheduler.enqueueSessionProcessing(id)
        _message.value = "Analyzing again…"
    }

    fun saveTranscript(text: String) = viewModelScope.launch {
        val lang = settings.current().speechLanguage
        if (text.isBlank()) calls.replaceTranscript(id, "", TranscriptSource.MANUAL, lang)
        else {
            calls.replaceTranscript(id, text, TranscriptSource.MANUAL, lang)
            calls.setStatus(id, SessionStatus.PROCESSING)
            scheduler.enqueueSessionProcessing(id)
        }
    }

    fun setContact(name: String, relation: String) = viewModelScope.launch {
        calls.setContact(id, name, relation, session.value?.session?.phoneNumber)
        _message.value = "Saved"
    }

    fun deleteRecording() = viewModelScope.launch {
        calls.deleteRecording(id)
        _message.value = "Recording deleted"
    }

    fun deleteTranscript() = viewModelScope.launch {
        calls.replaceTranscript(id, "", TranscriptSource.MANUAL, null)
        _message.value = "Transcript deleted"
    }

    fun deleteSession(onDone: () -> Unit) = viewModelScope.launch {
        calls.deleteSession(id)
        onDone()
    }
}

@Composable
fun CallDetailScreen(onBack: () -> Unit, onOpenTask: (Long) -> Unit, vm: CallDetailViewModel = hiltViewModel()) {
    val data by vm.session.collectAsStateWithLifecycle()
    val canTranscribe by vm.canTranscribe.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val tm = LocalTm.current

    var menu by remember { mutableStateOf(false) }
    var editTranscript by remember { mutableStateOf(false) }
    var editContact by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ExtractedActionEntity?>(null) }
    var transcriptExpanded by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consume() }
    }

    val d = data
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(d?.session?.contactName ?: d?.session?.title ?: "Conversation", maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Analyze again") }, onClick = { menu = false; vm.reanalyze() })
                        DropdownMenuItem(text = { Text("Edit transcript") }, onClick = { menu = false; editTranscript = true })
                        if (d?.session?.audioPath != null) DropdownMenuItem(text = { Text("Delete recording") }, onClick = { menu = false; vm.deleteRecording() })
                        if (d?.transcripts?.isNotEmpty() == true) DropdownMenuItem(text = { Text("Delete transcript") }, onClick = { menu = false; vm.deleteTranscript() })
                        DropdownMenuItem(text = { Text("Delete everything", color = tm.danger) }, onClick = { menu = false; confirmDelete = true })
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        if (d == null) {
            Text("This conversation was deleted.", Modifier.padding(padding).padding(24.dp))
        } else {
            CallDetailBody(d, padding, canTranscribe, vm, onOpenTask,
                onEditTranscript = { editTranscript = true }, onEditContact = { editContact = true },
                onEditAction = { editing = it }, expanded = transcriptExpanded, onToggleExpanded = { transcriptExpanded = !transcriptExpanded })
        }
    }

    val current = data
    if (editTranscript && current != null) {
        var text by remember { mutableStateOf(current.transcriptText) }
        AlertDialog(
            onDismissRequest = { editTranscript = false },
            title = { Text("Transcript") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    placeholder = { Text("Type what was said or agreed, e.g. “Rahul: please send the report by Friday. Me: I'll call the vendor tomorrow.”") },
                )
            },
            confirmButton = { TextButton(onClick = { editTranscript = false; vm.saveTranscript(text) }) { Text("Save & analyze") } },
            dismissButton = { TextButton(onClick = { editTranscript = false }) { Text("Cancel") } },
        )
    }
    if (editContact && current != null) {
        var name by remember { mutableStateOf(current.session.contactName.orEmpty()) }
        var relation by remember { mutableStateOf(current.contact?.relation.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editContact = false },
            title = { Text("Who was this with?") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = relation, onValueChange = { relation = it }, label = { Text("Relation (e.g. Manager, Client)") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("Manager", "Client", "Colleague", "Family", "Vendor").forEach { r ->
                            FilterChip(selected = relation.equals(r, true), onClick = { relation = r }, label = { Text(r) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editContact = false; vm.setContact(name, relation) }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editContact = false }) { Text("Cancel") } },
        )
    }
    editing?.let { a -> EditActionDialog(a, onDismiss = { editing = null }) { updated, approve -> editing = null; vm.saveEdit(updated, approve) } }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this conversation?") },
            text = { Text("The recording, transcript and unreviewed items are deleted from this phone. Tasks you already approved stay in your task list.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteSession(onBack) }) { Text("Delete", color = tm.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ActionCard(
    a: ExtractedActionEntity,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit,
    onRestore: () -> Unit,
    onOpenTask: (Long) -> Unit,
) {
    val tm = LocalTm.current
    val typeColor = when (a.type) {
        ActionType.COMMITMENT, ActionType.PROMISE -> MaterialTheme.colorScheme.tertiary
        ActionType.FOLLOW_UP, ActionType.EMAIL -> MaterialTheme.colorScheme.secondary
        ActionType.MEETING, ActionType.DEADLINE -> tm.warning
        else -> MaterialTheme.colorScheme.primary
    }
    TmCard(contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(a.type.label, typeColor)
            Spacer(Modifier.width(6.dp))
            if (a.priority.weight >= Priority.HIGH.weight) PriorityPill(a.priority)
            Spacer(Modifier.weight(1f))
            when (a.status) {
                ActionStatus.APPROVED -> Pill("Added", tm.success, icon = Icons.Filled.Check)
                ActionStatus.REJECTED -> Pill("Dismissed", MaterialTheme.colorScheme.outline)
                ActionStatus.PENDING -> {}
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            a.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            textDecoration = if (a.status == ActionStatus.REJECTED) TextDecoration.LineThrough else null,
        )
        val meta = listOfNotNull(
            a.dueAt?.let { "Due ${TimeUtil.formatDue(it, a.hasDueTime)}" },
            a.category,
            a.assignedBy?.let { "asked by $it" },
        )
        if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (a.detail.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(a.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (a.status) {
                ActionStatus.PENDING -> {
                    Button(onClick = onApprove, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Approve")
                    }
                    OutlinedButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Edit") }
                    TextButton(onClick = onReject) {
                        Icon(Icons.Outlined.Close, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Reject")
                    }
                }
                ActionStatus.APPROVED -> a.createdTaskId?.let { id -> OutlinedButton(onClick = { onOpenTask(id) }) { Text("Open task") } }
                ActionStatus.REJECTED -> TextButton(onClick = onRestore) { Text("Restore") }
            }
        }
    }
}

@Composable
private fun EditActionDialog(a: ExtractedActionEntity, onDismiss: () -> Unit, onSave: (ExtractedActionEntity, Boolean) -> Unit) {
    var title by remember { mutableStateOf(a.title) }
    var due by remember { mutableStateOf(a.dueAt?.let { TimeUtil.toLdt(it) }) }
    var hasTime by remember { mutableStateOf(a.hasDueTime) }
    var priority by remember { mutableStateOf(a.priority) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    fun result() = a.copy(title = title.trim(), dueAt = due?.let { TimeUtil.toMillis(it) }, hasDueTime = due != null && hasTime, priority = priority)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit ${a.type.label.lowercase()}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pickDate = true }, label = { Text(due?.let { TimeUtil.dayLabel(it.toLocalDate()) } ?: "Add date") })
                    AssistChip(onClick = { pickTime = true }, label = { Text(if (due != null && hasTime) TimeUtil.formatTime(due!!.hour, due!!.minute) else "Add time") })
                    if (due != null) AssistChip(onClick = { due = null; hasTime = false }, label = { Text("No date") })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Priority.entries.forEach { p -> FilterChip(selected = priority == p, onClick = { priority = p }, label = { Text(p.label) }) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(result(), true) }, enabled = title.isNotBlank()) { Text("Save & approve") } },
        dismissButton = { TextButton(onClick = { onSave(result(), false) }, enabled = title.isNotBlank()) { Text("Save") } },
    )
    if (pickDate) DatePickDialog(due?.toLocalDate(), { pickDate = false }) { d -> due = LocalDateTime.of(d, due?.toLocalTime() ?: LocalTime.of(9, 0)) }
    if (pickTime) {
        val base = due ?: TimeUtil.now().plusHours(1)
        TimePickDialog(base.hour, base.minute, { pickTime = false }) { h, m ->
            due = LocalDateTime.of(base.toLocalDate(), LocalTime.of(h, m)); hasTime = true
        }
    }
}


@Composable
private fun CallDetailBody(
    d: CallSessionWithDetails,
    padding: PaddingValues,
    canTranscribe: Boolean,
    vm: CallDetailViewModel,
    onOpenTask: (Long) -> Unit,
    onEditTranscript: () -> Unit,
    onEditContact: () -> Unit,
    onEditAction: (ExtractedActionEntity) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
) {
    val tm = LocalTm.current
        val s = d.session
        val actionable = d.actions.filter { it.type.actionable }
        val info = d.actions.filter { !it.type.actionable }
        val pending = actionable.count { it.status == ActionStatus.PENDING }

        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                TmCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.contactName ?: "Unknown person", style = MaterialTheme.typography.titleMedium)
                            d.contact?.relation?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                        }
                        IconButton(onClick = onEditContact) { Icon(Icons.Outlined.Edit, "Edit person") }
                    }
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(s.direction.label, MaterialTheme.colorScheme.primary)
                        Pill(TimeUtil.formatFull(s.startedAt), MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Schedule)
                        s.durationSec?.let { Pill(TimeUtil.formatDuration(it), MaterialTheme.colorScheme.onSurfaceVariant) }
                        s.phoneNumber?.let { Pill(it, MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Phone) }
                        Pill(s.status.label, if (s.status == SessionStatus.READY) tm.success else tm.warning)
                        s.analyzedBy?.let { Pill("Analyzed: $it", MaterialTheme.colorScheme.secondary, icon = Icons.Outlined.AutoAwesome) }
                        if (s.audioPath != null) Pill("Encrypted recording", MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Lock)
                    }
                }
            }

            if (s.audioBlocked) {
                item {
                    InfoBanner(
                        Icons.Outlined.Warning,
                        "Your phone didn't give TaskMind the call audio. Android lets only the system dialer record calls on most devices. " +
                            "Import the dialer's own recording, or add a voice note or the key points below.",
                        Tone.WARNING,
                    )
                }
            }
            if (s.status == SessionStatus.PROCESSING) {
                item { InfoBanner(Icons.Outlined.GraphicEq, "Analyzing this conversation…", Tone.INFO) }
            }
            if (s.status == SessionStatus.NEEDS_TRANSCRIPT || s.status == SessionStatus.FAILED || !s.lastError.isNullOrBlank()) {
                item {
                    InfoBanner(Icons.Outlined.Warning, s.lastError ?: "This conversation needs a transcript.", if (s.status == SessionStatus.READY) Tone.INFO else Tone.WARNING) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = onEditTranscript, contentPadding = PaddingValues(0.dp)) { Text("Type key points") }
                            if (s.audioPath != null && canTranscribe && d.transcripts.isEmpty()) {
                                TextButton(onClick = { vm.reanalyze() }, contentPadding = PaddingValues(0.dp)) { Text("Transcribe recording") }
                            }
                        }
                    }
                }
            }

            s.summary?.let { summary ->
                item {
                    TmCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("AI summary", style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Tasks & commitments (${actionable.size})", Modifier.weight(1f))
                    if (pending > 0) FilledTonalButton(onClick = { vm.approveAll() }) {
                        Icon(Icons.Outlined.DoneAll, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Approve all")
                    }
                }
                if (actionable.isEmpty()) {
                    Text(
                        if (s.status == SessionStatus.READY) "Nothing actionable was found. Edit the transcript or add tasks manually." else "Items will appear here after analysis.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(actionable.sortedBy { it.status.ordinal }, key = { it.id }) { a ->
                ActionCard(a, onApprove = { vm.approve(a) }, onReject = { vm.reject(a) }, onEdit = { onEditAction(a) }, onRestore = { vm.restore(a) }, onOpenTask = onOpenTask)
            }

            if (info.isNotEmpty()) {
                item {
                    SectionHeader("People & numbers")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        info.forEach { a ->
                            SuggestionChip(
                                onClick = {},
                                label = { Text(a.title) },
                                icon = { Icon(if (a.type == ActionType.PHONE) Icons.Outlined.Phone else Icons.Outlined.Person, null, Modifier.size(16.dp)) },
                            )
                        }
                    }
                }
            }

            item {
                TmCard(Modifier.animateContentSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Transcript", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        d.transcripts.firstOrNull()?.let { Text(it.source.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = onEditTranscript) { Icon(Icons.Outlined.Edit, "Edit transcript") }
                    }
                    val text = d.transcriptText
                    if (text.isBlank()) {
                        Text("No transcript yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 8)
                        if (text.length > 400) TextButton(onClick = onToggleExpanded, contentPadding = PaddingValues(0.dp)) {
                            Text(if (expanded) "Show less" else "Show full transcript")
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { vm.reanalyze() }, modifier = Modifier.fillMaxWidth(), enabled = d.transcripts.isNotEmpty() || (s.audioPath != null && canTranscribe)) {
                    Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Analyze again")
                }
            }
        }
    }
