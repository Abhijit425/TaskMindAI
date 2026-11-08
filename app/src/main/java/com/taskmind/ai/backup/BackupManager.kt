package com.taskmind.ai.backup

import android.content.Context
import android.net.Uri
import com.taskmind.ai.data.db.AppDatabase
import com.taskmind.ai.data.db.CallSessionEntity
import com.taskmind.ai.data.db.CategoryEntity
import com.taskmind.ai.data.db.ContactReferenceEntity
import com.taskmind.ai.data.db.ExtractedActionEntity
import com.taskmind.ai.data.db.ReminderEntity
import com.taskmind.ai.data.db.TaskAttachmentEntity
import com.taskmind.ai.data.db.TaskDependencyEntity
import com.taskmind.ai.data.db.TaskEntity
import com.taskmind.ai.data.db.TranscriptEntity
import com.taskmind.ai.data.db.UserSettingsEntity
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.CallRepository
import com.taskmind.ai.data.repo.CategoryRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.notify.ReminderScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class BackupData(
    val format: Int = 1,
    val app: String = "TaskMind AI",
    val exportedAt: Long,
    val includesTranscripts: Boolean,
    val categories: List<CategoryEntity> = emptyList(),
    val contacts: List<ContactReferenceEntity> = emptyList(),
    val sessions: List<CallSessionEntity> = emptyList(),
    val transcripts: List<TranscriptEntity> = emptyList(),
    val tasks: List<TaskEntity> = emptyList(),
    val dependencies: List<TaskDependencyEntity> = emptyList(),
    val attachments: List<TaskAttachmentEntity> = emptyList(),
    val reminders: List<ReminderEntity> = emptyList(),
    val actions: List<ExtractedActionEntity> = emptyList(),
    val profile: UserSettingsEntity? = null,
)

/**
 * User-initiated export/import as a JSON file the user picks with the system file picker.
 * Recordings and API keys are never included (keys are bound to this phone's keystore).
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val json: Json,
    private val tasks: TaskRepository,
    private val categories: CategoryRepository,
    private val calls: CallRepository,
    private val scheduler: ReminderScheduler,
    private val settings: SettingsRepository,
) {
    suspend fun export(uri: Uri, includeTranscripts: Boolean): Int = withContext(Dispatchers.IO) {
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            includesTranscripts = includeTranscripts,
            categories = db.categoryDao().getAll(),
            contacts = db.contactDao().getAll(),
            sessions = db.callDao().getAllSessions().map { it.copy(audioPath = null, audioMime = null) },
            transcripts = if (includeTranscripts) db.callDao().getAllTranscripts() else emptyList(),
            tasks = db.taskDao().getAll(),
            dependencies = db.taskDao().getAllDependencies(),
            attachments = db.taskDao().getAllAttachments(),
            reminders = db.reminderDao().getAll().filter { !it.fired },
            actions = db.callDao().getAllActions(),
            profile = db.userSettingsDao().get(),
        )
        val bytes = json.encodeToString(BackupData.serializer(), data).toByteArray(Charsets.UTF_8)
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) }
            ?: throw IllegalStateException("Could not write to the selected file")
        data.tasks.size
    }

    /** Replaces all current data with the backup. Returns the number of tasks restored. */
    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IllegalStateException("Could not read the selected file")
        val data = runCatching { json.decodeFromString(BackupData.serializer(), text) }
            .getOrElse { throw IllegalArgumentException("This is not a TaskMind backup file.") }
        if (data.app != "TaskMind AI") throw IllegalArgumentException("This is not a TaskMind backup file.")

        db.reminderDao().getAll().forEach { scheduler.cancel(it.id) }
        db.clearAllTables()
        calls.wipeRecordingFiles()

        db.categoryDao().insertAll(data.categories)
        db.contactDao().insertAll(data.contacts)
        db.callDao().insertSessions(data.sessions.map { s -> if (s.audioPath != null && !File(s.audioPath).exists()) s.copy(audioPath = null) else s })
        db.callDao().insertTranscripts(data.transcripts)
        db.taskDao().insertAll(data.tasks.sortedWith(compareBy<TaskEntity> { it.parentId != null }.thenBy { it.id }))
        db.taskDao().insertDependencies(data.dependencies)
        db.taskDao().insertAttachments(data.attachments)
        db.reminderDao().insertAll(data.reminders.filter { it.triggerAt > System.currentTimeMillis() })
        db.callDao().insertActionsReplace(data.actions)
        data.profile?.let { db.userSettingsDao().upsert(it) }
        categories.ensureDefaults()
        tasks.rescheduleAll()
        data.tasks.size
    }

    suspend fun deleteEverything() = withContext(Dispatchers.IO) {
        db.reminderDao().getAll().forEach { scheduler.cancel(it.id) }
        db.clearAllTables()
        calls.wipeRecordingFiles()
        settings.clearPrivateState()
        categories.ensureDefaults()
    }
}
