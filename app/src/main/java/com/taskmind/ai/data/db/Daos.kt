package com.taskmind.ai.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Transaction
    @Query(
        """SELECT * FROM tasks WHERE parentId IS NULL
           ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END,
                    CASE WHEN dueAt IS NULL THEN 1 ELSE 0 END, dueAt ASC, priority DESC, createdAt DESC"""
    )
    fun observeTopLevel(): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observeTask(id: Long): Flow<TaskWithDetails?>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTaskWithDetails(id: Long): TaskWithDetails?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getTask(id: Long): TaskEntity?

    @Query("SELECT * FROM tasks WHERE parentId = :parentId ORDER BY createdAt ASC")
    fun observeSubtasks(parentId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND parentId IS NULL ORDER BY priority DESC, dueAt ASC")
    suspend fun getPendingTopLevel(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' ORDER BY dueAt ASC")
    suspend fun getAllPending(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND dueAt IS NOT NULL AND dueAt < :before AND parentId IS NULL ORDER BY dueAt ASC")
    suspend fun getPendingDueBefore(before: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND dueAt BETWEEN :start AND :end AND parentId IS NULL ORDER BY dueAt ASC")
    suspend fun getPendingDueBetween(start: Long, end: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'COMPLETED' AND completedAt BETWEEN :start AND :end ORDER BY completedAt DESC")
    suspend fun getCompletedBetween(start: Long, end: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' AND dueAt IS NOT NULL AND dueAt > :now")
    suspend fun getPendingWithFutureDue(now: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE callSessionId = :sessionId")
    suspend fun getTasksForSession(sessionId: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks ORDER BY id ASC")
    suspend fun getAll(): List<TaskEntity>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<TaskEntity>)

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun deleteById(id: Long)

    // Dependencies
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependency(dep: TaskDependencyEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependencies(deps: List<TaskDependencyEntity>)

    @Delete
    suspend fun deleteDependency(dep: TaskDependencyEntity)

    @Query("SELECT t.* FROM tasks t INNER JOIN task_dependencies d ON t.id = d.dependsOnId WHERE d.taskId = :taskId")
    fun observeDependencies(taskId: Long): Flow<List<TaskEntity>>

    @Query("SELECT t.* FROM tasks t INNER JOIN task_dependencies d ON t.id = d.dependsOnId WHERE d.taskId = :taskId")
    suspend fun getDependencies(taskId: Long): List<TaskEntity>

    @Query("SELECT * FROM task_dependencies")
    suspend fun getAllDependencies(): List<TaskDependencyEntity>

    @Query("SELECT taskId FROM task_dependencies d INNER JOIN tasks t ON t.id = d.dependsOnId WHERE t.status = 'PENDING'")
    fun observeBlockedTaskIds(): Flow<List<Long>>

    // Attachments
    @Insert
    suspend fun insertAttachment(a: TaskAttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAttachments(a: List<TaskAttachmentEntity>)

    @Query("DELETE FROM task_attachments WHERE id = :id")
    suspend fun deleteAttachment(id: Long)

    @Query("SELECT * FROM task_attachments")
    suspend fun getAllAttachments(): List<TaskAttachmentEntity>
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(c: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(c: List<CategoryEntity>)

    @Query("DELETE FROM categories WHERE id = :id AND isDefault = 0")
    suspend fun deleteCustom(id: Long)

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(r: ReminderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(r: List<ReminderEntity>)

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE taskId = :taskId ORDER BY triggerAt")
    suspend fun getForTask(taskId: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE fired = 0 AND triggerAt > :now")
    suspend fun getUpcomingUnfired(now: Long): List<ReminderEntity>

    @Query("SELECT * FROM reminders")
    suspend fun getAll(): List<ReminderEntity>

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM reminders WHERE taskId = :taskId AND kind IN (:kinds)")
    suspend fun deleteForTaskKinds(taskId: Long, kinds: List<String>)
}

@Dao
interface CallDao {
    @Transaction
    @Query("SELECT * FROM call_sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<CallSessionWithDetails>>

    @Transaction
    @Query("SELECT * FROM call_sessions WHERE id = :id")
    fun observeSession(id: Long): Flow<CallSessionWithDetails?>

    @Transaction
    @Query("SELECT * FROM call_sessions WHERE id = :id")
    suspend fun getSessionWithDetails(id: Long): CallSessionWithDetails?

    @Transaction
    @Query("SELECT * FROM call_sessions ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int): List<CallSessionWithDetails>

    @Transaction
    @Query("SELECT * FROM call_sessions WHERE startedAt BETWEEN :start AND :end ORDER BY startedAt DESC")
    suspend fun getBetween(start: Long, end: Long): List<CallSessionWithDetails>

    @Query("SELECT * FROM call_sessions WHERE id = :id")
    suspend fun getSession(id: Long): CallSessionEntity?

    @Query("SELECT * FROM call_sessions ORDER BY id")
    suspend fun getAllSessions(): List<CallSessionEntity>

    @Insert
    suspend fun insertSession(s: CallSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSessions(s: List<CallSessionEntity>)

    @Update
    suspend fun updateSession(s: CallSessionEntity)

    @Query("DELETE FROM call_sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Query("SELECT * FROM call_sessions WHERE audioPath IS NOT NULL")
    suspend fun getSessionsWithAudio(): List<CallSessionEntity>

    @Insert
    suspend fun insertTranscript(t: TranscriptEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTranscripts(t: List<TranscriptEntity>)

    @Query("DELETE FROM transcripts WHERE sessionId = :sessionId")
    suspend fun deleteTranscripts(sessionId: Long)

    @Query("SELECT * FROM transcripts")
    suspend fun getAllTranscripts(): List<TranscriptEntity>

    @Insert
    suspend fun insertActions(a: List<ExtractedActionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertActionsReplace(a: List<ExtractedActionEntity>)

    @Update
    suspend fun updateAction(a: ExtractedActionEntity)

    @Query("SELECT * FROM extracted_actions WHERE id = :id")
    suspend fun getAction(id: Long): ExtractedActionEntity?

    @Query("DELETE FROM extracted_actions WHERE sessionId = :sessionId AND status = 'PENDING'")
    suspend fun deletePendingActions(sessionId: Long)

    @Query("SELECT * FROM extracted_actions")
    suspend fun getAllActions(): List<ExtractedActionEntity>

    @Query(
        """SELECT COUNT(*) FROM extracted_actions
           WHERE status = 'PENDING' AND type NOT IN ('NAME', 'PHONE')"""
    )
    fun observePendingActionCount(): Flow<Int>
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY name")
    fun observeAll(): Flow<List<ContactReferenceEntity>>

    @Query("SELECT * FROM contacts ORDER BY name")
    suspend fun getAll(): List<ContactReferenceEntity>

    @Query("SELECT * FROM contacts WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): ContactReferenceEntity?

    @Query("SELECT * FROM contacts WHERE phoneNumber = :phone LIMIT 1")
    suspend fun findByPhone(phone: String): ContactReferenceEntity?

    @Query("SELECT * FROM contacts WHERE id = :id")
    suspend fun getById(id: Long): ContactReferenceEntity?

    @Upsert
    suspend fun upsert(c: ContactReferenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(c: List<ContactReferenceEntity>)
}

@Dao
interface UserSettingsDao {
    @Query("SELECT * FROM user_settings WHERE id = 1")
    fun observe(): Flow<UserSettingsEntity?>

    @Query("SELECT * FROM user_settings WHERE id = 1")
    suspend fun get(): UserSettingsEntity?

    @Upsert
    suspend fun upsert(s: UserSettingsEntity)
}
