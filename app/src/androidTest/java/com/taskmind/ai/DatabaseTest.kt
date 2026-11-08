package com.taskmind.ai

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.AppDatabase
import com.taskmind.ai.data.db.CallDirection
import com.taskmind.ai.data.db.CallSessionEntity
import com.taskmind.ai.data.db.CategoryEntity
import com.taskmind.ai.data.db.ExtractedActionEntity
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.SessionStatus
import com.taskmind.ai.data.db.TaskDependencyEntity
import com.taskmind.ai.data.db.TaskEntity
import com.taskmind.ai.data.db.TranscriptEntity
import com.taskmind.ai.data.db.TranscriptSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun tasksSubtasksDependenciesAndPriorityOrdering() = runBlocking {
        val now = System.currentTimeMillis()
        val work = db.categoryDao().insert(CategoryEntity(name = "Work", colorArgb = 0xFF6366F1, isDefault = true))
        val low = db.taskDao().insert(TaskEntity(title = "Low", priority = Priority.LOW, categoryId = work, createdAt = now, updatedAt = now))
        val urgent = db.taskDao().insert(TaskEntity(title = "Urgent", priority = Priority.URGENT, categoryId = work, createdAt = now, updatedAt = now))
        db.taskDao().insert(TaskEntity(title = "Sub", parentId = urgent, createdAt = now, updatedAt = now))
        db.taskDao().insertDependency(TaskDependencyEntity(low, urgent))

        val top = db.taskDao().observeTopLevel().first()
        assertEquals(listOf("Urgent", "Low"), top.map { it.task.title })
        assertEquals("Work", top.first().category?.name)
        assertEquals(1, top.first().subtasks.size)
        assertEquals(listOf(urgent), db.taskDao().getDependencies(low).map { it.id })
        assertEquals(listOf(low), db.taskDao().observeBlockedTaskIds().first())

        db.taskDao().deleteById(urgent)
        assertEquals(1, db.taskDao().getAll().size) // subtask cascaded
        assertEquals(0, db.taskDao().getAllDependencies().size)
    }

    @Test
    fun callSessionCascadeAndPendingCount() = runBlocking {
        val now = System.currentTimeMillis()
        val sid = db.callDao().insertSession(
            CallSessionEntity(title = "Call", direction = CallDirection.INCOMING, startedAt = now, status = SessionStatus.READY)
        )
        db.callDao().insertTranscript(TranscriptEntity(sessionId = sid, text = "Please send the report", source = TranscriptSource.MANUAL, createdAt = now))
        db.callDao().insertActions(
            listOf(
                ExtractedActionEntity(sessionId = sid, type = ActionType.TASK, title = "Send the report"),
                ExtractedActionEntity(sessionId = sid, type = ActionType.NAME, title = "Rahul"),
            )
        )
        val taskId = db.taskDao().insert(TaskEntity(title = "Send the report", callSessionId = sid, createdAt = now, updatedAt = now))
        assertEquals(1, db.callDao().observePendingActionCount().first())

        db.callDao().deleteSession(sid)
        assertEquals(0, db.callDao().getAllTranscripts().size)
        assertEquals(0, db.callDao().getAllActions().size)
        assertNull(db.taskDao().getTask(taskId)?.callSessionId) // task survives, link cleared
    }
}
