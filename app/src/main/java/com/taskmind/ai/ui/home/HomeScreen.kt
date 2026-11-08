@file:OptIn(ExperimentalMaterial3Api::class)

package com.taskmind.ai.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.taskmind.ai.data.db.CallSessionWithDetails
import com.taskmind.ai.data.db.TaskWithDetails
import com.taskmind.ai.data.db.isCompleted
import com.taskmind.ai.data.db.pendingCount
import com.taskmind.ai.ui.components.EmptyState
import com.taskmind.ai.ui.components.InfoBanner
import com.taskmind.ai.ui.components.LevelBars
import com.taskmind.ai.ui.components.Pill
import com.taskmind.ai.ui.components.ProgressRing
import com.taskmind.ai.ui.components.SectionHeader
import com.taskmind.ai.ui.components.TaskRow
import com.taskmind.ai.ui.components.TmCard
import com.taskmind.ai.ui.components.Tone
import com.taskmind.ai.ui.nav.LocalSnackbar
import com.taskmind.ai.ui.theme.LocalTm
import com.taskmind.ai.util.TimeUtil
import com.taskmind.ai.voice.rememberSpeechInput
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onOpenTask: (Long) -> Unit,
    onOpenCall: (Long) -> Unit,
    onQuickAdd: (String) -> Unit,
    onNavigateTab: (String) -> Unit,
    onAsk: (String) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val briefing by vm.briefing.collectAsStateWithLifecycle()
    val lang by vm.speechLanguage.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()

    val speech = rememberSpeechInput(lang) { text ->
        scope.launch {
            when (val r = vm.handleVoice(text)) {
                is VoiceResult.Navigate -> onNavigateTab(r.route)
                is VoiceResult.Ask -> onAsk(r.question)
                is VoiceResult.Created -> {
                    val res = snackbar.showSnackbar(
                        "Added “${r.title}”${r.due?.let { " · $it" }.orEmpty()}",
                        actionLabel = "Undo",
                        withDismissAction = true,
                    )
                    if (res == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.deleteTask(r.taskId)
                }
            }
        }
    }

    // Keep the on-device briefing in step with the task list.
    LaunchedEffect(state.pendingTotal, state.overdue.size, state.today.size, state.completedToday, state.pendingReview) {
        if (state.loaded) vm.refreshBriefing(force = false)
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Header(state.name) }
            item { HeroCard(state) { onNavigateTab("tasks") } }

            if (state.pendingReview > 0) {
                item {
                    InfoBanner(
                        Icons.Outlined.Call,
                        "${state.pendingReview} item${if (state.pendingReview > 1) "s" else ""} from your conversations ${if (state.pendingReview > 1) "are" else "is"} waiting for review.",
                        Tone.WARNING,
                    ) { TextButton(onClick = { onNavigateTab("calls") }, contentPadding = PaddingValues(0.dp)) { Text("Review now") } }
                }
            }

            item { BriefingCard(briefing) { vm.refreshBriefing(true) } }

            if (speech.listening || speech.partial.isNotBlank() || speech.error != null) {
                item {
                    TmCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (speech.listening) LevelBars(speech.level) else Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                speech.error ?: speech.partial.ifBlank { "Listening… say “Remind me to…” or ask a question" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (speech.error != null) LocalTm.current.danger else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            if (state.overdue.isNotEmpty()) {
                item { SectionHeader("Overdue", action = "See all") { onNavigateTab("tasks") } }
                items(state.overdue.take(3), key = { "o${it.task.id}" }) { t -> HomeTaskRow(t, state, vm, onOpenTask) }
            }

            item { SectionHeader("Today", action = "All tasks") { onNavigateTab("tasks") } }
            if (state.today.isEmpty()) {
                item {
                    TmCard {
                        Text(
                            if (state.loaded && state.completedToday > 0) "All of today's tasks are done." else "Nothing due today. Add something or enjoy the breathing room.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(state.today, key = { "t${it.task.id}" }) { t -> HomeTaskRow(t, state, vm, onOpenTask) }
            }

            if (state.upcoming.isNotEmpty()) {
                item { SectionHeader("Upcoming deadlines") }
                items(state.upcoming, key = { "u${it.task.id}" }) { t -> HomeTaskRow(t, state, vm, onOpenTask) }
            }

            item { SectionHeader("Recent call insights", action = "Open") { onNavigateTab("calls") } }
            if (state.recentCalls.isEmpty()) {
                item {
                    TmCard {
                        EmptyState(
                            Icons.Outlined.Inbox, "No conversations yet",
                            "Capture a call, record a conversation or import a dialer recording — TaskMind will pull out what you were asked to do.",
                        )
                    }
                }
            } else {
                items(state.recentCalls, key = { "c${it.session.id}" }) { c -> CallInsightCard(c) { onOpenCall(c.session.id) } }
            }
        }

        Column(
            Modifier.align(Alignment.BottomEnd).padding(18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SmallFloatingActionButton(
                onClick = { if (speech.listening) speech.stop() else speech.start() },
                containerColor = if (speech.listening) LocalTm.current.danger else MaterialTheme.colorScheme.secondaryContainer,
            ) { Icon(Icons.Filled.Mic, "Voice command") }
            ExtendedFloatingActionButton(
                onClick = { onQuickAdd("") },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add task") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun HomeTaskRow(t: TaskWithDetails, state: HomeState, vm: HomeViewModel, onOpenTask: (Long) -> Unit) {
    TaskRow(
        task = t.task,
        category = t.category,
        subtasksDone = t.subtasks.count { it.isCompleted },
        subtasksTotal = t.subtasks.size,
        blocked = t.task.id in state.blockedIds,
        onToggle = { vm.toggle(t.task.id, !t.task.isCompleted) },
        onClick = { onOpenTask(t.task.id) },
    )
}

@Composable
private fun Header(name: String) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(TimeUtil.headerDate().uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                TimeUtil.greeting() + if (name.isNotBlank()) ",\n$name" else "",
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Box(
            Modifier.size(46.dp).clip(CircleShape).background(LocalTm.current.accentGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.firstOrNull()?.uppercase() ?: "✓", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun HeroCard(state: HomeState, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(LocalTm.current.heroGradient)
            .padding(20.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(state.progress) {
                    Text("${(state.progress * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text("Today's progress", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (state.todayTotal == 0) "A clear day" else "${state.completedToday} of ${state.todayTotal} done",
                        color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            state.overdue.isNotEmpty() -> "Clear the overdue items first"
                            state.today.isNotEmpty() -> "Next: ${state.today.first().task.title}"
                            else -> "You're on top of things"
                        },
                        color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("${state.pendingTotal}", "Pending", Modifier.weight(1f), onClick)
                Stat("${state.overdue.size}", "Overdue", Modifier.weight(1f), onClick)
                Stat("${state.weekCount}", "This week", Modifier.weight(1f), onClick)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.14f))
            .then(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)),
    ) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleLarge)
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BriefingCard(b: BriefingState, onRefresh: () -> Unit) {
    TmCard(Modifier.animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Daily briefing", style = MaterialTheme.typography.titleSmall)
                if (b.engine.isNotBlank()) Text(b.engine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (b.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh briefing") }
        }
        Spacer(Modifier.height(10.dp))
        Text(b.text.ifBlank { "Preparing your day…" }, style = MaterialTheme.typography.bodyMedium)
        AnimatedVisibility(b.note != null) {
            Text(b.note.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
fun CallInsightCard(c: CallSessionWithDetails, onClick: () -> Unit) {
    val s = c.session
    TmCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.Call, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.contactName ?: s.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(s.direction.label, TimeUtil.relative(s.startedAt), s.durationSec?.let { TimeUtil.formatDuration(it) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val pending = c.pendingCount
            if (pending > 0) Pill("$pending to review", LocalTm.current.warning)
            else Pill(s.status.label, MaterialTheme.colorScheme.primary, icon = Icons.Outlined.TaskAlt)
        }
        s.summary?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
