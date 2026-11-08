package com.taskmind.ai.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.ai.AiEngine
import com.taskmind.ai.ai.AssistantEngine
import com.taskmind.ai.data.db.CallSessionWithDetails
import com.taskmind.ai.data.db.TaskSource
import com.taskmind.ai.data.db.TaskWithDetails
import com.taskmind.ai.data.db.isCompleted
import com.taskmind.ai.data.db.isOverdue
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.data.repo.ProfileRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    val loaded: Boolean = false,
    val name: String = "",
    val overdue: List<TaskWithDetails> = emptyList(),
    val today: List<TaskWithDetails> = emptyList(),
    val upcoming: List<TaskWithDetails> = emptyList(),
    val completedToday: Int = 0,
    val pendingTotal: Int = 0,
    val weekCount: Int = 0,
    val recentCalls: List<CallSessionWithDetails> = emptyList(),
    val pendingReview: Int = 0,
    val blockedIds: Set<Long> = emptySet(),
) {
    val todayTotal: Int get() = today.size + completedToday + overdue.size
    val progress: Float get() = if (todayTotal == 0) 0f else completedToday.toFloat() / todayTotal
}

data class BriefingState(val text: String = "", val engine: String = "", val loading: Boolean = true, val note: String? = null)

sealed interface VoiceResult {
    data class Navigate(val route: String) : VoiceResult
    data class Ask(val question: String) : VoiceResult
    data class Created(val taskId: Long, val title: String, val due: String?) : VoiceResult
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tasks: TaskRepository,
    private val calls: CallRepository,
    private val profile: ProfileRepository,
    private val assistant: AssistantEngine,
    private val ai: AiEngine,
    settings: SettingsRepository,
) : ViewModel() {

    val speechLanguage: StateFlow<String> = settings.settings.map { it.speechLanguage }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "en-IN")

    val state: StateFlow<HomeState> = combine(
        tasks.observeTasks(),
        calls.observeSessions(),
        profile.observe(),
        calls.observePendingActionCount(),
        tasks.observeBlockedIds(),
    ) { all, sessions, prof, pendingReview, blocked ->
        val now = System.currentTimeMillis()
        val today = TimeUtil.today()
        val startToday = TimeUtil.startOfDay(today)
        val endToday = TimeUtil.endOfDay(today)
        val endWeek = TimeUtil.endOfDay(today.plusDays(7))
        val pending = all.filter { !it.task.isCompleted }
        HomeState(
            loaded = true,
            name = prof?.displayName.orEmpty(),
            overdue = pending.filter { it.task.isOverdue(now) }.sortedBy { it.task.dueAt },
            today = pending.filter { t -> !t.task.isOverdue(now) && t.task.dueAt?.let { it in startToday..endToday } == true }.sortedBy { it.task.dueAt },
            upcoming = pending.filter { t -> t.task.dueAt?.let { it in (endToday + 1)..endWeek } == true }.sortedBy { it.task.dueAt }.take(6),
            completedToday = all.count { it.task.isCompleted && (it.task.completedAt ?: 0) >= startToday },
            pendingTotal = pending.size,
            weekCount = pending.count { t -> t.task.dueAt?.let { it <= endWeek } == true },
            recentCalls = sessions.take(3),
            pendingReview = pendingReview,
            blockedIds = blocked.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeState())

    private val _briefing = MutableStateFlow(BriefingState())
    val briefing: StateFlow<BriefingState> = _briefing.asStateFlow()

    init {
        refreshBriefing(force = false)
    }

    fun refreshBriefing(force: Boolean) {
        viewModelScope.launch {
            if (force || _briefing.value.text.isBlank()) _briefing.value = _briefing.value.copy(loading = true)
            val out = runCatching { assistant.dailyBriefing(force) }.getOrNull()
            _briefing.value = if (out != null) BriefingState(out.value, out.engine, false, out.note)
            else BriefingState("Couldn't build your briefing right now.", "On-device", false)
        }
    }

    fun toggle(id: Long, done: Boolean) {
        viewModelScope.launch { tasks.setCompleted(id, done) }
    }

    private val rxShowTasks = Regex("""^(?:please\s+)?(?:show|open|list|display)(?: me)?(?: my| the| all)?(?: pending| open| all| today'?s)? (?:tasks|to-?dos|list)\b""", RegexOption.IGNORE_CASE)
    private val rxOpen = Regex("""^(?:open|go to|show)(?: the)? (assistant|calls?|call insights|settings|home)\b""", RegexOption.IGNORE_CASE)
    private val rxQuestion = Regex("""^(?:what|which|when|who|how|why|summari[sz]e|recap|do i|did i|am i|have i|is there|are there|any|tell me)\b""", RegexOption.IGNORE_CASE)

    suspend fun handleVoice(text: String): VoiceResult {
        val t = text.trim()
        if (rxShowTasks.containsMatchIn(t)) return VoiceResult.Navigate("tasks")
        rxOpen.find(t)?.let { m ->
            val target = m.groupValues[1].lowercase()
            return VoiceResult.Navigate(
                when {
                    target.startsWith("assist") -> "assistant"
                    target.startsWith("call") -> "calls"
                    target.startsWith("setting") -> "settings"
                    else -> "home"
                }
            )
        }
        if (rxQuestion.containsMatchIn(t) || t.endsWith("?")) return VoiceResult.Ask(t)
        val parsed = ai.parseTask(t).value
        val id = tasks.createFromParsed(parsed, TaskSource.VOICE)
        return VoiceResult.Created(id, parsed.title, parsed.dueAt?.let { TimeUtil.formatDue(TimeUtil.toMillis(it), parsed.hasTime) })
    }

    fun deleteTask(id: Long) {
        viewModelScope.launch { tasks.delete(id) }
    }
}
