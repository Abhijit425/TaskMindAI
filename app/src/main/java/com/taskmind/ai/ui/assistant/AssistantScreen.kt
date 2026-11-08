@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.taskmind.ai.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.ai.AssistantEngine
import com.taskmind.ai.ai.ChatMessage
import com.taskmind.ai.data.prefs.AppSettings
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.ui.components.LevelBars
import com.taskmind.ai.ui.theme.LocalTm
import com.taskmind.ai.voice.TtsManager
import com.taskmind.ai.voice.rememberSpeechInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UiMessage(val id: Long, val fromUser: Boolean, val text: String, val engine: String? = null, val note: String? = null, val taskId: Long? = null)

val SUGGESTIONS = listOf(
    "What should I complete today?",
    "What tasks are overdue?",
    "What did I promise during yesterday's calls?",
    "What tasks did my manager assign?",
    "What do I need to finish this week?",
    "Which tasks have the highest priority?",
    "Summarize my last five captured conversations.",
    "What am I forgetting?",
)

@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val engine: AssistantEngine,
    private val tts: TtsManager,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())
    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()
    private var handledInitial = false
    private var nextId = 1L

    fun handleInitial(q: String?) {
        if (handledInitial || q.isNullOrBlank()) return
        handledInitial = true
        send(q)
    }

    fun send(text: String) {
        val q = text.trim()
        if (q.isEmpty() || _thinking.value) return
        val history = _messages.value.takeLast(10).map { ChatMessage(if (it.fromUser) ChatMessage.USER else ChatMessage.ASSISTANT, it.text) }
        _messages.value = _messages.value + UiMessage(nextId++, true, q)
        _thinking.value = true
        viewModelScope.launch {
            val reply = runCatching { engine.answer(q, history) }.getOrElse {
                AssistantEngine.Reply("Something went wrong: ${it.message}", "On-device")
            }
            _messages.value = _messages.value + UiMessage(nextId++, false, reply.text, reply.engine, reply.note, reply.createdTaskId)
            _thinking.value = false
            val s = settings.value
            if (s.speakAssistantReplies) tts.speak(reply.text, s.speechLanguage)
        }
    }

    fun clear() {
        tts.stop()
        _messages.value = emptyList()
    }

    fun stopSpeaking() = tts.stop()
}

@Composable
fun AssistantScreen(initialQuestion: String?, onOpenTask: (Long) -> Unit, vm: AssistantViewModel = hiltViewModel()) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val thinking by vm.thinking.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val speech = rememberSpeechInput(settings.speechLanguage) { heard -> vm.send(heard) }

    LaunchedEffect(initialQuestion) { vm.handleInitial(initialQuestion) }
    LaunchedEffect(messages.size, thinking) {
        val count = messages.size + (if (thinking) 1 else 0)
        if (count > 0) listState.animateScrollToItem(count)
    }

    val engineLabel = if (settings.activeCloudReady && settings.cloudTasksConsent) settings.aiProvider.label else "On-device"

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AI Assistant", style = MaterialTheme.typography.headlineMedium)
                Text("Answers from your tasks and conversations · $engineLabel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (settings.speakAssistantReplies) {
                IconButton(onClick = vm::stopSpeaking) { Icon(Icons.Outlined.StopCircle, "Stop speaking") }
            }
            if (messages.isNotEmpty()) IconButton(onClick = vm::clear) { Icon(Icons.Outlined.DeleteSweep, "Clear chat") }
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(LocalTm.current.accentGradient), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(30.dp))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Your personal secretary", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Ask about your day, deadlines and what people asked you to do.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(18.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SUGGESTIONS.forEach { s -> SuggestionChip(onClick = { vm.send(s) }, label = { Text(s) }) }
                        }
                    }
                }
            }
            items(messages, key = { it.id }) { m -> Bubble(m, onOpenTask) }
            if (thinking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 8.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (messages.isNotEmpty() && !thinking) {
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SUGGESTIONS) { s -> SuggestionChip(onClick = { vm.send(s) }, label = { Text(s) }) }
            }
        }

        if (speech.listening || speech.error != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (speech.listening) LevelBars(speech.level)
                Spacer(Modifier.width(10.dp))
                Text(speech.error ?: speech.partial.ifBlank { "Listening…" }, style = MaterialTheme.typography.bodySmall, color = if (speech.error != null) LocalTm.current.danger else MaterialTheme.colorScheme.primary)
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (speech.listening) speech.stop() else speech.start() }) {
                Icon(Icons.Filled.Mic, "Speak", tint = if (speech.listening) LocalTm.current.danger else MaterialTheme.colorScheme.primary)
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask, or “Remind me to…”") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { vm.send(input); input = "" }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { vm.send(input); input = "" }, enabled = input.isNotBlank() && !thinking) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}

@Composable
private fun Bubble(m: UiMessage, onOpenTask: (Long) -> Unit) {
    val shape = if (m.fromUser) RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp) else RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = if (m.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 320.dp).clip(shape)
                .background(if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                m.text.replace("**", ""),
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
            )
            if (!m.fromUser) {
                val footer = listOfNotNull(m.engine, m.note).joinToString(" · ")
                if (footer.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(footer, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                m.taskId?.let { id ->
                    TextButton(onClick = { onOpenTask(id) }, contentPadding = PaddingValues(0.dp)) { Text("Open task") }
                }
            }
        }
    }
}
