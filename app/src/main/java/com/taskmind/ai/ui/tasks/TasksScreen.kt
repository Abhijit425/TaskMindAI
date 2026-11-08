@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.taskmind.ai.ui.tasks

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.data.db.CategoryEntity
import com.taskmind.ai.data.db.TaskWithDetails
import com.taskmind.ai.data.db.isCompleted
import com.taskmind.ai.data.db.isOverdue
import com.taskmind.ai.data.repo.CategoryRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.ui.components.EmptyState
import com.taskmind.ai.ui.components.TaskRow
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TaskFilter(val label: String) { TODAY("Today"), UPCOMING("Upcoming"), OVERDUE("Overdue"), ALL("All"), DONE("Completed") }

data class TaskListState(
    val filter: TaskFilter = TaskFilter.ALL,
    val query: String = "",
    val categoryId: Long? = null,
    val categories: List<CategoryEntity> = emptyList(),
    val items: List<TaskWithDetails> = emptyList(),
    val counts: Map<TaskFilter, Int> = emptyMap(),
    val blocked: Set<Long> = emptySet(),
)

@HiltViewModel
class TasksViewModel @Inject constructor(
    private val tasks: TaskRepository,
    categories: CategoryRepository,
) : ViewModel() {
    private val filter = MutableStateFlow(TaskFilter.ALL)
    private val query = MutableStateFlow("")
    private val category = MutableStateFlow<Long?>(null)

    private val controls = combine(filter, query, category) { f, q, c -> Triple(f, q, c) }

    val state: StateFlow<TaskListState> = combine(
        tasks.observeTasks(), categories.observe(), controls, tasks.observeBlockedIds(),
    ) { all, cats, (f, q, c), blocked ->
        val now = System.currentTimeMillis()
        val endToday = TimeUtil.endOfDay()
        fun matches(t: TaskWithDetails, which: TaskFilter): Boolean = when (which) {
            TaskFilter.TODAY -> !t.task.isCompleted && t.task.dueAt?.let { it <= endToday } == true
            TaskFilter.UPCOMING -> !t.task.isCompleted && t.task.dueAt?.let { it > endToday } == true
            TaskFilter.OVERDUE -> t.task.isOverdue(now)
            TaskFilter.ALL -> !t.task.isCompleted
            TaskFilter.DONE -> t.task.isCompleted
        }
        val ql = q.trim().lowercase()
        val filtered = all.filter { t ->
            matches(t, f) &&
                (c == null || t.task.categoryId == c) &&
                (ql.isEmpty() || t.task.title.lowercase().contains(ql) || t.task.notes.lowercase().contains(ql) ||
                    t.task.tags.contains(ql) || t.task.assignedBy?.lowercase()?.contains(ql) == true ||
                    t.subtasks.any { it.title.lowercase().contains(ql) })
        }.let { list ->
            if (f == TaskFilter.DONE) list.sortedByDescending { it.task.completedAt ?: 0 } else list
        }
        TaskListState(
            filter = f, query = q, categoryId = c, categories = cats, items = filtered,
            counts = TaskFilter.entries.associateWith { which -> all.count { matches(it, which) } },
            blocked = blocked.toSet(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TaskListState())

    fun setFilter(f: TaskFilter) { filter.value = f }
    fun setQuery(q: String) { query.value = q }
    fun setCategory(id: Long?) { category.value = if (category.value == id) null else id }
    fun toggle(id: Long, done: Boolean) { viewModelScope.launch { tasks.setCompleted(id, done) } }
}

@Composable
fun TasksScreen(onOpenTask: (Long) -> Unit, onQuickAdd: () -> Unit, vm: TasksViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 18.dp)) {
                    Text("Tasks", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "${s.counts[TaskFilter.ALL] ?: 0} open · ${s.counts[TaskFilter.OVERDUE] ?: 0} overdue",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    OutlinedTextField(
                        value = s.query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Search tasks, notes, people, #tags") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Close, "Clear") }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(18.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TaskFilter.entries) { f ->
                        val n = s.counts[f] ?: 0
                        FilterChip(
                            selected = s.filter == f,
                            onClick = { vm.setFilter(f) },
                            label = { Text(if (f == TaskFilter.DONE) f.label else "${f.label} · $n") },
                        )
                    }
                }
            }
            if (s.categories.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(s.categories, key = { it.id }) { c ->
                            FilterChip(
                                selected = s.categoryId == c.id,
                                onClick = { vm.setCategory(c.id) },
                                label = { Text(c.name) },
                                leadingIcon = {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.colorArgb)))
                                },
                            )
                        }
                    }
                }
            }
            if (s.items.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Outlined.TaskAlt,
                        when (s.filter) {
                            TaskFilter.OVERDUE -> "Nothing overdue"
                            TaskFilter.DONE -> "Nothing completed yet"
                            TaskFilter.TODAY -> "Nothing due today"
                            else -> if (s.query.isNotBlank()) "No matches" else "No tasks yet"
                        },
                        if (s.query.isNotBlank()) "Try a different search." else "Tap + and type naturally, e.g. “Call the doctor next Wednesday at 11 AM”.",
                    )
                }
            }
            items(s.items, key = { it.task.id }) { t ->
                TaskRow(
                    task = t.task,
                    category = t.category,
                    subtasksDone = t.subtasks.count { it.isCompleted },
                    subtasksTotal = t.subtasks.size,
                    blocked = t.task.id in s.blocked,
                    onToggle = { vm.toggle(t.task.id, !t.task.isCompleted) },
                    onClick = { onOpenTask(t.task.id) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        FloatingActionButton(
            onClick = onQuickAdd,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) { Icon(Icons.Filled.Add, "Add task") }
    }
}
