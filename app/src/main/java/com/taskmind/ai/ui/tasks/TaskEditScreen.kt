@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.taskmind.ai.ui.tasks

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.Recurrence
import com.taskmind.ai.data.db.ReminderKind
import com.taskmind.ai.data.db.isCompleted
import com.taskmind.ai.ui.components.AnimatedCheck
import com.taskmind.ai.ui.components.DatePickDialog
import com.taskmind.ai.ui.components.InfoBanner
import com.taskmind.ai.ui.components.SectionHeader
import com.taskmind.ai.ui.components.TimePickDialog
import com.taskmind.ai.ui.components.TmCard
import com.taskmind.ai.ui.components.Tone
import com.taskmind.ai.ui.nav.LocalSnackbar
import com.taskmind.ai.ui.theme.LocalTm
import com.taskmind.ai.ui.theme.priorityColor
import com.taskmind.ai.util.TimeUtil
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

@Composable
fun TaskEditScreen(
    onBack: () -> Unit,
    onOpenCall: (Long) -> Unit,
    onOpenTask: (Long) -> Unit,
    vm: TaskEditViewModel = hiltViewModel(),
) {
    val form by vm.form.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val isNew by vm.isNew.collectAsStateWithLifecycle()
    val details by vm.details.collectAsStateWithLifecycle()
    val deps by vm.dependencies.collectAsStateWithLifecycle()
    val candidates by vm.candidates.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val session by vm.session.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val tm = LocalTm.current

    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var reminderDate by remember { mutableStateOf<LocalDate?>(null) }
    var pickReminderDate by remember { mutableStateOf(false) }
    var showDepDialog by remember { mutableStateOf(false) }
    var showCategoryDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var newSubtask by remember { mutableStateOf("") }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            var name = uri.lastPathSegment ?: "Attachment"
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { name = it }
                }
            }
            vm.addAttachment(uri.toString(), name, context.contentResolver.getType(uri))
        }
    }

    val completed = details?.task?.isCompleted == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "New task" else "Task") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = vm::toggleComplete) {
                            Icon(Icons.Filled.Check, if (completed) "Mark as not done" else "Mark as done", tint = if (completed) tm.success else MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.DeleteOutline, "Delete") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding().padding(16.dp)) {
                Button(onClick = { vm.save(onBack) }, enabled = !ui.saving && form.title.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    if (ui.saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text(if (isNew) "Create task" else "Save changes")
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (completed) InfoBanner(Icons.Filled.Check, "Completed ${details?.task?.completedAt?.let { TimeUtil.relative(it) }.orEmpty()}", Tone.SUCCESS)

            if (isNew) {
                TmCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Describe it naturally", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ui.nlText, onValueChange = vm::setNlText, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Send the updated document to the client tomorrow, urgent") },
                        shape = RoundedCornerShape(16.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = vm::applyNaturalLanguage, enabled = ui.nlText.isNotBlank() && !ui.parsing) {
                        if (ui.parsing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Fill in the details")
                    }
                }
            }

            OutlinedTextField(
                value = form.title, onValueChange = { v -> vm.update { it.copy(title = v) } },
                modifier = Modifier.fillMaxWidth(), label = { Text("Title") },
                textStyle = MaterialTheme.typography.titleMedium, shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            OutlinedTextField(
                value = form.notes, onValueChange = { v -> vm.update { it.copy(notes = v) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp), label = { Text("Notes") },
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )

            SectionHeader("When")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { pickDate = true },
                    label = { Text(form.dueDate?.let { TimeUtil.dayLabel(it) } ?: "Add date") },
                    leadingIcon = { Icon(Icons.Outlined.Event, null, Modifier.size(18.dp)) },
                )
                AssistChip(
                    onClick = { if (form.dueDate == null) vm.update { it.copy(dueDate = TimeUtil.today()) }; pickTime = true },
                    label = { Text(form.dueTime?.let { TimeUtil.formatTime(it.hour, it.minute) } ?: "Add time") },
                    leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) },
                )
                if (form.dueDate != null) {
                    AssistChip(onClick = { vm.update { it.copy(dueDate = null, dueTime = null) } }, label = { Text("Clear") }, leadingIcon = { Icon(Icons.Outlined.Close, null, Modifier.size(18.dp)) })
                }
                listOf("Today" to 0L, "Tomorrow" to 1L, "Next week" to 7L).forEach { (label, days) ->
                    FilterChip(
                        selected = form.dueDate == TimeUtil.today().plusDays(days),
                        onClick = { vm.update { it.copy(dueDate = TimeUtil.today().plusDays(days)) } },
                        label = { Text(label) },
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Recurrence.entries.forEach { r ->
                    FilterChip(selected = form.recurrence == r, onClick = { vm.update { it.copy(recurrence = r) } }, label = { Text(if (r == Recurrence.NONE) "Once" else r.label) })
                }
            }

            SectionHeader("Priority")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { p ->
                    FilterChip(
                        selected = form.priority == p,
                        onClick = { vm.update { it.copy(priority = p) } },
                        label = { Text(p.label) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(priorityColor(p))) },
                    )
                }
            }

            SectionHeader("Category")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { c ->
                    FilterChip(
                        selected = form.categoryId == c.id,
                        onClick = { vm.update { it.copy(categoryId = c.id) } },
                        label = { Text(c.name) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.colorArgb))) },
                    )
                }
                AssistChip(onClick = { showCategoryDialog = true }, label = { Text("New") }, leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)) })
            }

            OutlinedTextField(
                value = form.tags, onValueChange = { v -> vm.update { it.copy(tags = v) } },
                modifier = Modifier.fillMaxWidth(), label = { Text("Tags (comma separated)") }, singleLine = true, shape = RoundedCornerShape(16.dp),
            )
            OutlinedTextField(
                value = form.assignedBy, onValueChange = { v -> vm.update { it.copy(assignedBy = v) } },
                modifier = Modifier.fillMaxWidth(), label = { Text("Asked by (optional)") }, singleLine = true, shape = RoundedCornerShape(16.dp),
            )

            session?.let { s ->
                TmCard(onClick = { onOpenCall(s.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Call, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Captured from a conversation", style = MaterialTheme.typography.titleSmall)
                            Text("${s.contactName ?: s.title} · ${TimeUtil.formatFull(s.startedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // Reminders
            SectionHeader("Reminders", action = "Add") { pickReminderDate = true }
            val reminders = details?.reminders.orEmpty().filter { !it.fired }.sortedBy { it.triggerAt }
            if (reminders.isEmpty()) {
                Text(
                    if (form.dueDate != null) "A reminder is set automatically from the due date when you save." else "Add a due date or a custom reminder.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            reminders.forEach { r ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(TimeUtil.formatDue(r.triggerAt, true), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            when (r.kind) {
                                ReminderKind.DEADLINE -> "At the deadline"
                                ReminderKind.EARLY -> "Heads-up before the deadline"
                                ReminderKind.CUSTOM -> "Custom reminder"
                                ReminderKind.SNOOZE -> "Snoozed"
                            },
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { vm.removeReminder(r.id) }) { Icon(Icons.Outlined.Close, "Remove reminder") }
                }
            }

            // Subtasks
            SectionHeader("Subtasks")
            details?.subtasks.orEmpty().sortedBy { it.createdAt }.forEach { sub ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedCheck(sub.isCompleted, MaterialTheme.colorScheme.primary, { vm.toggleSubtask(sub) })
                    Spacer(Modifier.width(10.dp))
                    Text(sub.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    IconButton(onClick = { vm.deleteSubtask(sub) }) { Icon(Icons.Outlined.Close, "Delete subtask") }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newSubtask, onValueChange = { newSubtask = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Add a subtask") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                )
                IconButton(onClick = { vm.addSubtask(newSubtask); newSubtask = "" }, enabled = newSubtask.isNotBlank()) { Icon(Icons.Outlined.Add, "Add subtask") }
            }

            // Dependencies
            SectionHeader("Blocked by", action = "Add") { showDepDialog = true }
            if (deps.isEmpty()) {
                Text("No dependencies. Add tasks that must be finished first.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                deps.forEach { d ->
                    InputChip(
                        selected = d.isCompleted,
                        onClick = { onOpenTask(d.id) },
                        label = { Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(if (d.isCompleted) Icons.Filled.Check else Icons.Outlined.Lock, null, Modifier.size(16.dp)) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remove", Modifier.size(16.dp).clickable { vm.removeDependency(d.id) }) },
                    )
                }
            }

            // Attachments
            SectionHeader("Attachments", action = "Attach") { attachLauncher.launch(arrayOf("*/*")) }
            details?.attachments.orEmpty().forEach { a ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(a.uri), a.mimeType ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        } catch (_: ActivityNotFoundException) {
                        } catch (_: SecurityException) {
                        }
                    }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.AttachFile, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(a.displayName, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.removeAttachment(a.id) }) { Icon(Icons.Outlined.Close, "Remove attachment") }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickDate) DatePickDialog(form.dueDate, { pickDate = false }) { d -> vm.update { it.copy(dueDate = d) } }
    if (pickTime) {
        val t = form.dueTime ?: LocalTime.of(9, 0)
        TimePickDialog(t.hour, t.minute, { pickTime = false }) { h, m -> vm.update { it.copy(dueTime = LocalTime.of(h, m)) } }
    }
    if (pickReminderDate) DatePickDialog(form.dueDate ?: TimeUtil.today(), { pickReminderDate = false }) { d -> reminderDate = d }
    reminderDate?.let { d ->
        val now = TimeUtil.now().plusHours(1)
        TimePickDialog(now.hour, 0, { reminderDate = null }) { h, m -> vm.addReminder(LocalDateTime.of(d, LocalTime.of(h, m))) }
    }
    if (showDepDialog) {
        AlertDialog(
            onDismissRequest = { showDepDialog = false },
            title = { Text("This task is blocked by…") },
            text = {
                if (candidates.isEmpty()) Text("No other open tasks.")
                else LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(candidates.filter { c -> deps.none { it.id == c.id } }, key = { it.id }) { c ->
                        Text(
                            c.title,
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { vm.addDependency(c.id); showDepDialog = false }.padding(12.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDepDialog = false }) { Text("Close") } },
        )
    }
    if (showCategoryDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCategoryDialog = false },
            title = { Text("New category") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("e.g. Side project") }) },
            confirmButton = { TextButton(onClick = { vm.createCategory(name); showCategoryDialog = false }, enabled = name.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { showCategoryDialog = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this task?") },
            text = { Text("Its subtasks and reminders will be deleted too.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete", color = tm.danger) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
