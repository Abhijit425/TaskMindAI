package com.taskmind.ai.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import kotlinx.serialization.Serializable

@Serializable
@Entity(tableName = "categories", indices = [Index(value = ["name"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorArgb: Long,
    val icon: String = "label",
    val isDefault: Boolean = false,
)

@Serializable
@Entity(
    tableName = "contacts",
    indices = [Index("phoneNumber"), Index("name")],
)
data class ContactReferenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phoneNumber: String? = null,
    /** e.g. "Manager", "Client", "Family" — used by the assistant ("what did my manager assign?") */
    val relation: String? = null,
    val notes: String = "",
)

@Serializable
@Entity(
    tableName = "call_sessions",
    foreignKeys = [
        ForeignKey(
            entity = ContactReferenceEntity::class,
            parentColumns = ["id"], childColumns = ["contactId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("contactId"), Index("startedAt")],
)
data class CallSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val contactId: Long? = null,
    val contactName: String? = null,
    val phoneNumber: String? = null,
    val direction: CallDirection,
    val captureMode: CaptureMode? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    val durationSec: Long? = null,
    val status: SessionStatus,
    /** Encrypted audio file inside app-private storage, or null. */
    val audioPath: String? = null,
    val audioMime: String? = null,
    /** True when a call capture ran but the device delivered no usable audio. */
    val audioBlocked: Boolean = false,
    val consentConfirmed: Boolean = false,
    val summary: String? = null,
    val analyzedBy: String? = null,
    val lastError: String? = null,
    val reviewNudgedAt: Long? = null,
)

@Serializable
@Entity(
    tableName = "transcripts",
    foreignKeys = [
        ForeignKey(
            entity = CallSessionEntity::class,
            parentColumns = ["id"], childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val source: TranscriptSource,
    val language: String? = null,
    val createdAt: Long,
)

@Serializable
@Entity(
    tableName = "tasks",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = TaskEntity::class,
            parentColumns = ["id"], childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CallSessionEntity::class,
            parentColumns = ["id"], childColumns = ["callSessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("categoryId"), Index("parentId"), Index("callSessionId"), Index("dueAt"), Index("status")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String = "",
    val categoryId: Long? = null,
    val priority: Priority = Priority.MEDIUM,
    val status: TaskStatus = TaskStatus.PENDING,
    val dueAt: Long? = null,
    val hasDueTime: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long? = null,
    val recurrence: Recurrence = Recurrence.NONE,
    val parentId: Long? = null,
    /** Comma separated tags, lower-case. */
    val tags: String = "",
    val source: TaskSource = TaskSource.MANUAL,
    val callSessionId: Long? = null,
    val assignedBy: String? = null,
)

val TaskEntity.isCompleted: Boolean get() = status == TaskStatus.COMPLETED
val TaskEntity.tagList: List<String> get() = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
fun TaskEntity.isOverdue(now: Long = System.currentTimeMillis()): Boolean {
    val due = dueAt ?: return false
    if (isCompleted) return false
    if (hasDueTime) return due < now
    val zone = java.time.ZoneId.systemDefault()
    val startToday = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    return due < startToday
}

@Serializable
@Entity(
    tableName = "task_dependencies",
    primaryKeys = ["taskId", "dependsOnId"],
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["dependsOnId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("dependsOnId")],
)
data class TaskDependencyEntity(
    val taskId: Long,
    val dependsOnId: Long,
)

@Serializable
@Entity(
    tableName = "task_attachments",
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("taskId")],
)
data class TaskAttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val uri: String,
    val displayName: String,
    val mimeType: String? = null,
    val addedAt: Long,
)

@Serializable
@Entity(
    tableName = "reminders",
    foreignKeys = [
        ForeignKey(entity = TaskEntity::class, parentColumns = ["id"], childColumns = ["taskId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("taskId"), Index("triggerAt")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val triggerAt: Long,
    val kind: ReminderKind,
    val fired: Boolean = false,
)

@Serializable
@Entity(
    tableName = "extracted_actions",
    foreignKeys = [
        ForeignKey(entity = CallSessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId")],
)
data class ExtractedActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: ActionType,
    val title: String,
    val detail: String = "",
    val dueAt: Long? = null,
    val hasDueTime: Boolean = false,
    val priority: Priority = Priority.MEDIUM,
    val category: String? = null,
    val assignedBy: String? = null,
    val status: ActionStatus = ActionStatus.PENDING,
    val createdTaskId: Long? = null,
)

/** Single-row profile used by the assistant and greetings. App preferences live in DataStore. */
@Serializable
@Entity(tableName = "user_settings")
data class UserSettingsEntity(
    @PrimaryKey val id: Int = 1,
    val displayName: String = "",
    val managerName: String = "",
    val organization: String = "",
)

// ---- Relations ----

data class TaskWithDetails(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "categoryId", entityColumn = "id")
    val category: CategoryEntity?,
    @Relation(parentColumn = "id", entityColumn = "parentId")
    val subtasks: List<TaskEntity>,
    @Relation(parentColumn = "id", entityColumn = "taskId")
    val attachments: List<TaskAttachmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "taskId")
    val reminders: List<ReminderEntity>,
)

data class CallSessionWithDetails(
    @Embedded val session: CallSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val transcripts: List<TranscriptEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val actions: List<ExtractedActionEntity>,
    @Relation(parentColumn = "contactId", entityColumn = "id")
    val contact: ContactReferenceEntity?,
)

val CallSessionWithDetails.transcriptText: String get() = transcripts.sortedBy { it.createdAt }.joinToString("\n\n") { it.text }
val CallSessionWithDetails.pendingCount: Int get() = actions.count { it.status == ActionStatus.PENDING && it.type.actionable }
