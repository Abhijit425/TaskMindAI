@file:OptIn(ExperimentalCoroutinesApi::class)

package com.taskmind.ai.ui.tasks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.taskmind.ai.ai.AiEngine
import com.taskmind.ai.data.db.CallSessionEntity
import com.taskmind.ai.data.db.CategoryEntity
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.Recurrence
import com.taskmind.ai.data.db.TaskEntity
import com.taskmind.ai.data.db.TaskSource
import com.taskmind.ai.data.db.TaskStatus
import com.taskmind.ai.data.db.TaskWithDetails
import com.taskmind.ai.data.db.isCompleted
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.data.repo.CategoryRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

data class TaskForm(
    val title: String = "",
    val notes: String = "",
    val dueDate: LocalDate? = null,
    val dueTime: LocalTime? = null,
    val priority: Priority = Priority.MEDIUM,
    val categoryId: Long? = null,
    val tags: String = "",
    val recurrence: Recurrence = Recurrence.NONE,
    val assignedBy: String = "",
)

data class EditUi(
    val saving: Boolean = false,
    val parsing: Boolean = false,
    val nlText: String = "",
    val message: String? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class TaskEditViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val tasks: TaskRepository,
    private val categoriesRepo: CategoryRepository,
    private val calls: CallRepository,
    private val ai: AiEngine,
) : ViewModel() {

    private val currentId = MutableStateFlow(savedState.get<Long>("id") ?: 0L)
    val isNew: StateFlow<Boolean> = currentId.map { it == 0L }.stateIn(viewModelScope, SharingStarted.Eagerly, currentId.value == 0L)

    private val _form = MutableStateFlow(TaskForm())
    val form: StateFlow<TaskForm> = _form.asStateFlow()
    private val _ui = MutableStateFlow(EditUi(loaded = currentId.value == 0L))
    val ui: StateFlow<EditUi> = _ui.asStateFlow()

    val details: StateFlow<TaskWithDetails?> = currentId.flatMapLatest { id -> if (id == 0L) flowOf(null) else tasks.observeTask(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val dependencies: StateFlow<List<TaskEntity>> = currentId.flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else tasks.observeDependencies(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val candidates: StateFlow<List<TaskEntity>> = combine(tasks.observeTasks(), currentId) { all, id ->
        all.map { it.task }.filter { !it.isCompleted && it.id != id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<CategoryEntity>> = categoriesRepo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _session = MutableStateFlow<CallSessionEntity?>(null)
    val session: StateFlow<CallSessionEntity?> = _session.asStateFlow()

    init {
        val id = currentId.value
        if (id != 0L) {
            viewModelScope.launch {
                val d = tasks.observeTask(id).first()
                if (d != null) {
                    val t = d.task
                    _form.value = TaskForm(
                        title = t.title,
                        notes = t.notes,
                        dueDate = t.dueAt?.let { TimeUtil.toDate(it) },
                        dueTime = t.dueAt?.takeIf { t.hasDueTime }?.let { TimeUtil.toLdt(it).toLocalTime() },
                        priority = t.priority,
                        categoryId = t.categoryId,
                        tags = t.tags,
                        recurrence = t.recurrence,
                        assignedBy = t.assignedBy.orEmpty(),
                    )
                    t.callSessionId?.let { sid -> _session.value = calls.getSession(sid) }
                }
                _ui.value = _ui.value.copy(loaded = true)
            }
        } else {
            viewModelScope.launch {
                val personal = categoriesRepo.findOrCreate("Personal")
                if (_form.value.categoryId == null) _form.value = _form.value.copy(categoryId = personal)
            }
        }
    }

    fun update(transform: (TaskForm) -> TaskForm) {
        _form.value = transform(_form.value)
    }

    fun setNlText(t: String) {
        _ui.value = _ui.value.copy(nlText = t)
    }

    fun consumeMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    /** Fill the form from a natural-language description. */
    fun applyNaturalLanguage() {
        val text = _ui.value.nlText.trim()
        if (text.isEmpty()) return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(parsing = true)
            val out = ai.parseTask(text)
            val p = out.value
            val catId = categoriesRepo.findOrCreate(p.category) ?: _form.value.categoryId
            _form.value = _form.value.copy(
                title = p.title,
                notes = listOf(_form.value.notes, p.notes).filter { it.isNotBlank() }.joinToString("\n"),
                dueDate = p.dueAt?.toLocalDate() ?: _form.value.dueDate,
                dueTime = if (p.dueAt != null && p.hasTime) p.dueAt.toLocalTime() else if (p.dueAt != null) null else _form.value.dueTime,
                priority = p.priority,
                categoryId = catId,
                tags = (p.tags + _form.value.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }).distinct().joinToString(","),
                recurrence = p.recurrence,
                assignedBy = p.assignedBy ?: _form.value.assignedBy,
            )
            _ui.value = _ui.value.copy(parsing = false, nlText = "", message = "Filled by ${out.engine}" + (out.note?.let { " — $it" } ?: ""))
        }
    }

    private fun buildEntity(existing: TaskEntity?): TaskEntity {
        val f = _form.value
        val dueAt = f.dueDate?.let { d -> TimeUtil.toMillis(LocalDateTime.of(d, f.dueTime ?: LocalTime.of(9, 0))) }
        val now = System.currentTimeMillis()
        val base = existing ?: TaskEntity(title = "", createdAt = now, updatedAt = now, source = TaskSource.MANUAL)
        return base.copy(
            title = f.title.trim(),
            notes = f.notes.trim(),
            dueAt = dueAt,
            hasDueTime = f.dueDate != null && f.dueTime != null,
            priority = f.priority,
            categoryId = f.categoryId,
            tags = f.tags.split(',').map { it.trim().lowercase().removePrefix("#") }.filter { it.isNotEmpty() }.distinct().joinToString(","),
            recurrence = f.recurrence,
            assignedBy = f.assignedBy.trim().ifEmpty { null },
        )
    }

    /** Saves (creating the task if new). Returns the id, or null if the title is empty. */
    suspend fun persist(): Long? {
        if (_form.value.title.isBlank()) {
            _ui.value = _ui.value.copy(message = "Give the task a title first.")
            return null
        }
        _ui.value = _ui.value.copy(saving = true)
        val id = currentId.value
        val existing = if (id != 0L) tasks.getTask(id) else null
        val saved = tasks.save(buildEntity(existing))
        currentId.value = saved
        _ui.value = _ui.value.copy(saving = false)
        return saved
    }

    fun save(onDone: () -> Unit) {
        viewModelScope.launch { if (persist() != null) onDone() }
    }

    fun delete(onDone: () -> Unit) {
        viewModelScope.launch {
            val id = currentId.value
            if (id != 0L) tasks.delete(id)
            onDone()
        }
    }

    fun toggleComplete() {
        viewModelScope.launch {
            val id = persist() ?: return@launch
            val t = tasks.getTask(id) ?: return@launch
            tasks.setCompleted(id, t.status != TaskStatus.COMPLETED)
        }
    }

    fun addSubtask(title: String) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val id = persist() ?: return@launch
            tasks.addSubtask(id, title)
        }
    }

    fun toggleSubtask(sub: TaskEntity) {
        viewModelScope.launch { tasks.setCompleted(sub.id, !sub.isCompleted) }
    }

    fun deleteSubtask(sub: TaskEntity) {
        viewModelScope.launch { tasks.delete(sub.id) }
    }

    fun addDependency(dependsOn: Long) {
        viewModelScope.launch {
            val id = persist() ?: return@launch
            tasks.addDependency(id, dependsOn)
        }
    }

    fun removeDependency(dependsOn: Long) {
        viewModelScope.launch { tasks.removeDependency(currentId.value, dependsOn) }
    }

    fun addAttachment(uri: String, name: String, mime: String?) {
        viewModelScope.launch {
            val id = persist() ?: return@launch
            tasks.addAttachment(id, uri, name, mime)
        }
    }

    fun removeAttachment(id: Long) {
        viewModelScope.launch { tasks.removeAttachment(id) }
    }

    fun addReminder(at: LocalDateTime) {
        viewModelScope.launch {
            val ms = TimeUtil.toMillis(at)
            if (ms <= System.currentTimeMillis()) {
                _ui.value = _ui.value.copy(message = "Pick a time in the future.")
                return@launch
            }
            val id = persist() ?: return@launch
            tasks.addCustomReminder(id, ms)
            _ui.value = _ui.value.copy(message = "Reminder set for ${TimeUtil.formatDue(ms, true)}")
        }
    }

    fun removeReminder(id: Long) {
        viewModelScope.launch { tasks.removeReminder(id) }
    }

    fun createCategory(name: String) {
        viewModelScope.launch {
            val id = categoriesRepo.findOrCreate(name) ?: return@launch
            update { it.copy(categoryId = id) }
        }
    }
}
