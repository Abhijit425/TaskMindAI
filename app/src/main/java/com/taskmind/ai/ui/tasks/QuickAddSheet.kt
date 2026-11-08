@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.taskmind.ai.ui.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.ai.AiEngine
import com.taskmind.ai.ai.ParsedTask
import com.taskmind.ai.ai.TaskTextParser
import com.taskmind.ai.data.db.Recurrence
import com.taskmind.ai.data.db.TaskSource
import com.taskmind.ai.data.prefs.AppSettings
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.ui.components.Pill
import com.taskmind.ai.ui.components.PriorityPill
import com.taskmind.ai.ui.nav.LocalSnackbar
import com.taskmind.ai.ui.theme.LocalTm
import com.taskmind.ai.util.TimeUtil
import com.taskmind.ai.voice.rememberSpeechInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuickAddState(val text: String = "", val preview: ParsedTask? = null, val saving: Boolean = false)

@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val ai: AiEngine,
    private val tasks: TaskRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _state = MutableStateFlow(QuickAddState())
    val state: StateFlow<QuickAddState> = _state.asStateFlow()

    fun setText(t: String) {
        val s = settings.value
        val preview = if (t.isBlank()) null else TaskTextParser.parse(t, TimeUtil.now(), s.defaultReminderHour, s.defaultReminderMinute, s.dayFirstDates)
        _state.value = _state.value.copy(text = t, preview = preview)
    }

    /** Parses with the configured AI engine (cloud if allowed, otherwise on-device) and saves. */
    suspend fun save(): Pair<Long, String>? {
        val text = _state.value.text.trim()
        if (text.isEmpty()) return null
        _state.value = _state.value.copy(saving = true)
        return try {
            val outcome = ai.parseTask(text)
            val id = tasks.createFromParsed(outcome.value, TaskSource.NATURAL_LANGUAGE)
            id to outcome.value.title
        } finally {
            _state.value = QuickAddState()
        }
    }
}

@Composable
fun QuickAddSheet(prefill: String, onDismiss: () -> Unit, onOpenTask: (Long) -> Unit, vm: QuickAddViewModel = hiltViewModel()) {
    val st by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    val focus = remember { FocusRequester() }
    val speech = rememberSpeechInput(settings.speechLanguage) { heard -> vm.setText(heard) }

    LaunchedEffect(Unit) {
        if (prefill.isNotBlank()) vm.setText(prefill)
        runCatching { focus.requestFocus() }
    }

    fun submit(openAfter: Boolean) {
        scope.launch {
            val res = vm.save() ?: return@launch
            sheet.hide()
            onDismiss()
            if (openAfter) onOpenTask(res.first) else snackbar.showSnackbar("Added “${res.second}”")
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().imePadding().padding(bottom = 16.dp)) {
            Text("New task", style = MaterialTheme.typography.titleLarge)
            Text(
                "Type or say it naturally — dates, times, priority and category are detected for you.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = st.text,
                onValueChange = vm::setText,
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                placeholder = { Text("Remind me to call the doctor next Wednesday at 11 AM") },
                minLines = 2,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit(false) }),
                trailingIcon = {
                    IconButton(onClick = { if (speech.listening) speech.stop() else speech.start() }) {
                        Icon(Icons.Filled.Mic, "Dictate", tint = if (speech.listening) LocalTm.current.danger else MaterialTheme.colorScheme.primary)
                    }
                },
            )
            if (speech.listening && speech.partial.isNotBlank()) {
                Text(speech.partial, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            }
            speech.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = LocalTm.current.danger, modifier = Modifier.padding(top = 6.dp)) }

            AnimatedVisibility(st.preview != null) {
                val p = st.preview
                if (p != null) {
                    Column(Modifier.padding(top = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(p.title, style = MaterialTheme.typography.titleSmall)
                        }
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            p.dueAt?.let { Pill(TimeUtil.formatDue(TimeUtil.toMillis(it), p.hasTime), MaterialTheme.colorScheme.primary, icon = Icons.Outlined.Schedule) }
                            PriorityPill(p.priority)
                            Pill(p.category ?: "Personal", MaterialTheme.colorScheme.secondary, icon = Icons.Outlined.Category)
                            if (p.recurrence != Recurrence.NONE) Pill(p.recurrence.label, MaterialTheme.colorScheme.tertiary, icon = Icons.Outlined.Repeat)
                            p.assignedBy?.let { Pill("from $it", MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Person) }
                            p.tags.forEach { Pill("#$it", MaterialTheme.colorScheme.onSurfaceVariant, icon = Icons.Outlined.Tag) }
                        }
                        if (settings.activeCloudReady && settings.cloudTasksConsent) {
                            Text(
                                "Preview is on-device. On save, ${settings.aiProvider.label} refines it.",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { submit(true) }, enabled = st.text.isNotBlank() && !st.saving, modifier = Modifier.weight(1f)) {
                    Text("Add details")
                }
                Button(onClick = { submit(false) }, enabled = st.text.isNotBlank() && !st.saving, modifier = Modifier.weight(1f)) {
                    if (st.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    else Text("Add task")
                }
            }
        }
    }
}
