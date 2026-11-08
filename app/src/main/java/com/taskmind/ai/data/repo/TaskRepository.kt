package com.taskmind.ai.data.repo

import com.taskmind.ai.ai.ParsedTask
import com.taskmind.ai.data.db.AppDatabase
import com.taskmind.ai.data.db.CategoryEntity
import com.taskmind.ai.data.db.Recurrence
import com.taskmind.ai.data.db.ReminderEntity
import com.taskmind.ai.data.db.ReminderKind
import com.taskmind.ai.data.db.TaskAttachmentEntity
import com.taskmind.ai.data.db.TaskDependencyEntity
import com.taskmind.ai.data.db.TaskEntity
import com.taskmind.ai.data.db.TaskSource
import com.taskmind.ai.data.db.TaskStatus
import com.taskmind.ai.data.db.TaskWithDetails
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.notify.ReminderScheduler
import com.taskmind.ai.util.TimeUtil
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryRepository @Inject constructor(private val db: AppDatabase) {
    private val dao get() = db.categoryDao()

    fun observe(): Flow<List<CategoryEntity>> = dao.observeAll()

    suspend fun ensureDefaults() {
        if (dao.count() > 0) return
        DEFAULTS.forEach { (name, color, icon) ->
            dao.insert(CategoryEntity(name = name, colorArgb = color, icon = icon, isDefault = true))
        }
    }

    suspend fun findOrCreate(name: String?): Long? {
        val n = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        dao.findByName(n)?.let { return it.id }
        val palette = listOf(0xFF7C6CFFL, 0xFF14B8A6L, 0xFFF59E0BL, 0xFFEF4444L, 0xFF3B82F6L, 0xFFEC4899L, 0xFF22C55EL)
        val id = dao.insert(CategoryEntity(name = n.replaceFirstChar { it.uppercase() }, colorArgb = palette[(n.hashCode() and 0x7fffffff) % palette.size]))
        return if (id > 0) id else dao.findByName(n)?.id
    }

    suspend fun delete(id: Long) = dao.deleteCustom(id)

    companion object {
        val DEFAULTS = listOf(
            Triple("Work", 0xFF6366F1L, "work"),
            Triple("Personal", 0xFF14B8A6L, "person"),
            Triple("Health", 0xFFEF4444L, "health"),
            Triple("Finance", 0xFFF59E0BL, "finance"),
            Triple("Family", 0xFFEC4899L, "family"),
            Triple("Shopping", 0xFF22C55EL, "shopping"),
        )
    }
}

@Singleton
class TaskRepository @Inject constructor(
    private val db: AppDatabase,
    private val categories: CategoryRepository,
    private val scheduler: ReminderScheduler,
    private val settings: SettingsRepository,
) {
    private val taskDao get() = db.taskDao()
    private val reminderDao get() = db.reminderDao()

    fun observeTasks(): Flow<List<TaskWithDetails>> = taskDao.observeTopLevel()
    fun observeTask(id: Long): Flow<TaskWithDetails?> = taskDao.observeTask(id)
    fun observeDependencies(id: Long): Flow<List<TaskEntity>> = taskDao.observeDependencies(id)
    fun observeBlockedIds(): Flow<List<Long>> = taskDao.observeBlockedTaskIds()

    suspend fun getTask(id: Long) = taskDao.getTask(id)

    suspend fun createFromParsed(
        p: ParsedTask,
        source: TaskSource,
        callSessionId: Long? = null,
        extraNotes: String = "",
    ): Long {
        val now = System.currentTimeMillis()
        val categoryId = categories.findOrCreate(p.category ?: "Personal")
        val task = TaskEntity(
            title = p.title.take(200),
            notes = listOf(p.notes, extraNotes).filter { it.isNotBlank() }.joinToString("\n\n"),
            categoryId = categoryId,
            priority = p.priority,
            dueAt = p.dueAt?.let { TimeUtil.toMillis(it) },
            hasDueTime = p.hasTime,
            createdAt = now,
            updatedAt = now,
            recurrence = p.recurrence,
            tags = p.tags.joinToString(","),
            source = source,
            callSessionId = callSessionId,
            assignedBy = p.assignedBy,
        )
        val id = taskDao.insert(task)
        rebuildAutoReminders(id)
        return id
    }

    /** Insert or update a task, then refresh its automatic reminders. */
    suspend fun save(task: TaskEntity): Long {
        val now = System.currentTimeMillis()
        val id = if (task.id == 0L) {
            taskDao.insert(task.copy(createdAt = now, updatedAt = now))
        } else {
            taskDao.update(task.copy(updatedAt = now))
            task.id
        }
        rebuildAutoReminders(id)
        return id
    }

    suspend fun setCompleted(id: Long, completed: Boolean) {
        val task = taskDao.getTask(id) ?: return
        val now = System.currentTimeMillis()
        if (completed) {
            taskDao.update(task.copy(status = TaskStatus.COMPLETED, completedAt = now, updatedAt = now))
            cancelReminders(id, all = false)
            if (task.recurrence != Recurrence.NONE && task.parentId == null) createNextOccurrence(task)
        } else {
            taskDao.update(task.copy(status = TaskStatus.PENDING, completedAt = null, updatedAt = now))
            rebuildAutoReminders(id)
        }
    }

    private suspend fun createNextOccurrence(task: TaskEntity) {
        val base = task.dueAt?.let { TimeUtil.toLdt(it) } ?: TimeUtil.now()
        var next = when (task.recurrence) {
            Recurrence.DAILY -> base.plusDays(1)
            Recurrence.WEEKDAYS -> {
                var d = base.plusDays(1)
                while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                d
            }
            Recurrence.WEEKLY -> base.plusWeeks(1)
            Recurrence.MONTHLY -> base.plusMonths(1)
            Recurrence.NONE -> return
        }
        // If the task was completed late, roll forward until the next occurrence is in the future.
        val now = TimeUtil.now()
        var guard = 0
        while (next.isBefore(now) && guard++ < 400) {
            next = when (task.recurrence) {
                Recurrence.MONTHLY -> next.plusMonths(1)
                Recurrence.WEEKLY -> next.plusWeeks(1)
                else -> next.plusDays(1)
            }
        }
        val ms = System.currentTimeMillis()
        val newId = taskDao.insert(
            task.copy(
                id = 0, status = TaskStatus.PENDING, completedAt = null, dueAt = TimeUtil.toMillis(next),
                createdAt = ms, updatedAt = ms,
            )
        )
        rebuildAutoReminders(newId)
    }

    suspend fun delete(id: Long) {
        cancelReminders(id, all = true)
        taskDao.getTaskWithDetails(id)?.subtasks?.forEach { cancelReminders(it.id, all = true) }
        taskDao.deleteById(id)
    }

    suspend fun addSubtask(parentId: Long, title: String): Long {
        val parent = taskDao.getTask(parentId) ?: return 0
        val now = System.currentTimeMillis()
        return taskDao.insert(
            TaskEntity(
                title = title.trim(), parentId = parentId, categoryId = parent.categoryId,
                priority = parent.priority, createdAt = now, updatedAt = now, source = TaskSource.MANUAL,
            )
        )
    }

    suspend fun addDependency(taskId: Long, dependsOnId: Long) {
        if (taskId == dependsOnId) return
        // Prevent a direct cycle (A depends on B and B depends on A).
        if (taskDao.getDependencies(dependsOnId).any { it.id == taskId }) return
        taskDao.insertDependency(TaskDependencyEntity(taskId, dependsOnId))
    }

    suspend fun removeDependency(taskId: Long, dependsOnId: Long) =
        taskDao.deleteDependency(TaskDependencyEntity(taskId, dependsOnId))

    suspend fun addAttachment(taskId: Long, uri: String, name: String, mime: String?) =
        taskDao.insertAttachment(TaskAttachmentEntity(taskId = taskId, uri = uri, displayName = name, mimeType = mime, addedAt = System.currentTimeMillis()))

    suspend fun removeAttachment(id: Long) = taskDao.deleteAttachment(id)

    suspend fun addCustomReminder(taskId: Long, at: Long) {
        if (at <= System.currentTimeMillis()) return
        val id = reminderDao.insert(ReminderEntity(taskId = taskId, triggerAt = at, kind = ReminderKind.CUSTOM))
        reminderDao.getById(id)?.let { scheduler.schedule(it) }
    }

    suspend fun removeReminder(id: Long) {
        scheduler.cancel(id)
        reminderDao.deleteById(id)
    }

    suspend fun snooze(taskId: Long, minutes: Int) {
        val at = System.currentTimeMillis() + minutes * 60_000L
        val id = reminderDao.insert(ReminderEntity(taskId = taskId, triggerAt = at, kind = ReminderKind.SNOOZE))
        reminderDao.getById(id)?.let { scheduler.schedule(it) }
    }

    /** Recreate the deadline (and optional early) reminder for a task from its due date. */
    suspend fun rebuildAutoReminders(taskId: Long) {
        val task = taskDao.getTask(taskId) ?: return
        val autoKinds = listOf(ReminderKind.DEADLINE, ReminderKind.EARLY)
        reminderDao.getForTask(taskId).filter { it.kind in autoKinds }.forEach { scheduler.cancel(it.id) }
        reminderDao.deleteForTaskKinds(taskId, autoKinds.map { it.name })
        val due = task.dueAt ?: return
        if (task.status == TaskStatus.COMPLETED) return
        val s = settings.current()
        if (!s.deadlineReminders) return
        val now = System.currentTimeMillis()
        val times = mutableListOf<Pair<Long, ReminderKind>>()
        if (task.hasDueTime) {
            times += due to ReminderKind.DEADLINE
            if (s.reminderLeadMinutes > 0) times += (due - s.reminderLeadMinutes * 60_000L) to ReminderKind.EARLY
        } else {
            // Date-only task: remind on that day at the default reminder time.
            val day = TimeUtil.toDate(due)
            times += TimeUtil.toMillis(day.atTime(s.defaultReminderHour, s.defaultReminderMinute)) to ReminderKind.DEADLINE
        }
        times.filter { it.first > now }.forEach { (at, kind) ->
            val id = reminderDao.insert(ReminderEntity(taskId = taskId, triggerAt = at, kind = kind))
            reminderDao.getById(id)?.let { scheduler.schedule(it) }
        }
    }

    private suspend fun cancelReminders(taskId: Long, all: Boolean) {
        reminderDao.getForTask(taskId).forEach { r ->
            scheduler.cancel(r.id)
            if (all || r.kind != ReminderKind.CUSTOM) reminderDao.deleteById(r.id)
        }
    }

    /** After boot, app update, or a settings change: re-arm every future reminder. */
    suspend fun rescheduleAll() {
        taskDao.getPendingWithFutureDue(System.currentTimeMillis() - 24 * 3600_000L).forEach { rebuildAutoReminders(it.id) }
        reminderDao.getUpcomingUnfired(System.currentTimeMillis())
            .filter { it.kind == ReminderKind.CUSTOM || it.kind == ReminderKind.SNOOZE }
            .forEach { scheduler.schedule(it) }
    }
}
