package com.taskmind.ai.data.repo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.taskmind.ai.ai.ExtractionResult
import com.taskmind.ai.ai.ParsedTask
import com.taskmind.ai.data.db.ActionStatus
import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.AppDatabase
import com.taskmind.ai.data.db.CallDirection
import com.taskmind.ai.data.db.CallSessionEntity
import com.taskmind.ai.data.db.CallSessionWithDetails
import com.taskmind.ai.data.db.ContactReferenceEntity
import com.taskmind.ai.data.db.ExtractedActionEntity
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.SessionStatus
import com.taskmind.ai.data.db.TaskSource
import com.taskmind.ai.data.db.TranscriptEntity
import com.taskmind.ai.data.db.TranscriptSource
import com.taskmind.ai.data.db.UserSettingsEntity
import com.taskmind.ai.security.KeystoreCrypto
import com.taskmind.ai.util.TimeUtil
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val crypto: KeystoreCrypto,
    private val tasks: TaskRepository,
) {
    private val dao get() = db.callDao()
    private val contactDao get() = db.contactDao()

    private val recordingsDir: File get() = File(context.filesDir, "recordings").apply { mkdirs() }

    fun observeSessions(): Flow<List<CallSessionWithDetails>> = dao.observeSessions()
    fun observeSession(id: Long): Flow<CallSessionWithDetails?> = dao.observeSession(id)
    fun observePendingActionCount(): Flow<Int> = dao.observePendingActionCount()
    fun observeContacts(): Flow<List<ContactReferenceEntity>> = contactDao.observeAll()

    suspend fun getSession(id: Long) = dao.getSession(id)
    suspend fun getSessionWithDetails(id: Long) = dao.getSessionWithDetails(id)
    suspend fun updateSession(s: CallSessionEntity) = dao.updateSession(s)

    suspend fun createSession(session: CallSessionEntity): Long = dao.insertSession(session)

    suspend fun addTranscript(sessionId: Long, text: String, source: TranscriptSource, language: String?) {
        if (text.isBlank()) return
        dao.insertTranscript(
            TranscriptEntity(sessionId = sessionId, text = text.trim(), source = source, language = language, createdAt = System.currentTimeMillis())
        )
    }

    /** Replace the transcript (user edit or a fresh transcription). */
    suspend fun replaceTranscript(sessionId: Long, text: String, source: TranscriptSource, language: String?) {
        dao.deleteTranscripts(sessionId)
        addTranscript(sessionId, text, source, language)
    }

    /** Encrypt a finished recording into app-private storage and delete the plaintext temp file. */
    suspend fun storeEncryptedAudio(sessionId: Long, plainFile: File, mime: String): Unit = withContext(Dispatchers.IO) {
        val target = File(recordingsDir, "session_${sessionId}_${System.currentTimeMillis()}.enc")
        try {
            crypto.encryptFile(plainFile, target)
        } finally {
            plainFile.delete()
        }
        dao.getSession(sessionId)?.let { s ->
            s.audioPath?.let { old -> File(old).delete() }
            dao.updateSession(s.copy(audioPath = target.absolutePath, audioMime = mime))
        }
    }

    /** Decrypt to a short-lived cache file. Caller must delete it when finished. */
    suspend fun decryptAudioToCache(session: CallSessionEntity): File? = withContext(Dispatchers.IO) {
        val path = session.audioPath ?: return@withContext null
        val src = File(path)
        if (!src.exists()) return@withContext null
        val ext = when {
            session.audioMime?.contains("mp4") == true || session.audioMime?.contains("m4a") == true || session.audioMime?.contains("aac") == true -> "m4a"
            session.audioMime?.contains("mpeg") == true || session.audioMime?.contains("mp3") == true -> "mp3"
            session.audioMime?.contains("wav") == true -> "wav"
            session.audioMime?.contains("ogg") == true -> "ogg"
            session.audioMime?.contains("webm") == true -> "webm"
            session.audioMime?.contains("amr") == true -> "amr"
            else -> "m4a"
        }
        val out = File(context.cacheDir, "audio_${session.id}_${System.nanoTime()}.$ext")
        crypto.decryptFile(src, out)
        out
    }

    /** Copy a recording chosen by the user (e.g. from the phone's dialer recordings) into encrypted storage. */
    suspend fun importRecording(uri: Uri, consentConfirmed: Boolean): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = "Imported recording"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        if ((size ?: 0L) > 200L * 1024 * 1024) throw IllegalArgumentException("That file is larger than 200 MB.")
        val mime = resolver.getType(uri) ?: "audio/mp4"
        val temp = File(context.cacheDir, "import_${System.nanoTime()}")
        resolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("Could not open the selected file.")
        val now = System.currentTimeMillis()
        val id = dao.insertSession(
            CallSessionEntity(
                title = name.substringBeforeLast('.').take(80),
                direction = CallDirection.IMPORTED,
                startedAt = now,
                status = SessionStatus.PROCESSING,
                consentConfirmed = consentConfirmed,
                contactName = guessContactFromFileName(name),
            )
        )
        storeEncryptedAudio(id, temp, mime)
        id
    }

    /** Many dialers name recordings like "Rahul Sharma_20261107_1032.m4a" or "Call@+9198...". */
    private fun guessContactFromFileName(name: String): String? {
        val base = name.substringBeforeLast('.')
        val candidate = base.split('_', '@', '(', '-').firstOrNull()?.trim().orEmpty()
        return candidate.takeIf { it.length in 2..40 && it.any(Char::isLetter) && !it.startsWith("Call", ignoreCase = true) && !it.startsWith("Record", ignoreCase = true) }
    }

    suspend fun createTextSession(title: String, text: String, direction: CallDirection, source: TranscriptSource, language: String?, contactName: String?): Long {
        val now = System.currentTimeMillis()
        val id = dao.insertSession(
            CallSessionEntity(
                title = title, direction = direction, startedAt = now, endedAt = now, status = SessionStatus.PROCESSING,
                consentConfirmed = true, contactName = contactName?.takeIf { it.isNotBlank() },
            )
        )
        addTranscript(id, text, source, language)
        return id
    }

    suspend fun applyExtraction(sessionId: Long, result: ExtractionResult, engine: String, note: String?) {
        val session = dao.getSession(sessionId) ?: return
        val existing = dao.getSessionWithDetails(sessionId)?.actions.orEmpty()
        val decided = existing.filter { it.status != ActionStatus.PENDING }.map { it.title.lowercase() }.toSet()
        dao.deletePendingActions(sessionId)
        val fresh = result.items
            .filter { it.title.lowercase() !in decided }
            .distinctBy { it.type to it.title.lowercase() }
            .map { item ->
                ExtractedActionEntity(
                    sessionId = sessionId,
                    type = item.type,
                    title = item.title.take(200),
                    detail = item.detail.take(600),
                    dueAt = item.dueAt?.let { TimeUtil.toMillis(it) },
                    hasDueTime = item.hasTime,
                    priority = item.priority,
                    category = item.category,
                    assignedBy = item.assignedBy,
                )
            }
        if (fresh.isNotEmpty()) dao.insertActions(fresh)
        dao.updateSession(
            session.copy(
                summary = result.summary, status = SessionStatus.READY, analyzedBy = engine,
                lastError = note, reviewNudgedAt = null,
            )
        )
    }

    suspend fun setStatus(sessionId: Long, status: SessionStatus, error: String? = null) {
        dao.getSession(sessionId)?.let { dao.updateSession(it.copy(status = status, lastError = error)) }
    }

    suspend fun updateAction(a: ExtractedActionEntity) = dao.updateAction(a)

    /** User approved an extracted item: create a real task linked to the conversation. */
    suspend fun approveAction(actionId: Long): Long? {
        val a = dao.getAction(actionId) ?: return null
        if (a.status == ActionStatus.APPROVED && a.createdTaskId != null) return a.createdTaskId
        if (!a.type.actionable) {
            dao.updateAction(a.copy(status = ActionStatus.APPROVED))
            return null
        }
        val session = dao.getSession(a.sessionId)
        val who = a.assignedBy ?: session?.contactName
        val title = when (a.type) {
            ActionType.FOLLOW_UP -> if (a.title.contains("follow", true)) a.title else "Follow up: ${a.title}"
            else -> a.title
        }
        val tags = buildList {
            add(a.type.name.lowercase().replace('_', '-'))
            if (a.type == ActionType.COMMITMENT || a.type == ActionType.PROMISE) add("promise")
        }
        val notes = buildString {
            append("From ${session?.direction?.label?.lowercase() ?: "conversation"}")
            session?.contactName?.let { append(" with $it") }
            session?.startedAt?.let { append(" on ${TimeUtil.formatFull(it)}") }
            if (a.detail.isNotBlank()) append("\n${a.detail}")
        }
        val taskId = tasks.createFromParsed(
            ParsedTask(
                title = title,
                dueAt = a.dueAt?.let { TimeUtil.toLdt(it) },
                hasTime = a.hasDueTime,
                priority = a.priority,
                category = a.category,
                tags = tags,
                assignedBy = if (a.type == ActionType.COMMITMENT || a.type == ActionType.PROMISE) null else who,
            ),
            source = TaskSource.CALL,
            callSessionId = a.sessionId,
            extraNotes = notes,
        )
        dao.updateAction(a.copy(status = ActionStatus.APPROVED, createdTaskId = taskId))
        return taskId
    }

    suspend fun rejectAction(actionId: Long) {
        dao.getAction(actionId)?.let { dao.updateAction(it.copy(status = ActionStatus.REJECTED)) }
    }

    suspend fun approveAll(sessionId: Long): Int {
        val pending = dao.getSessionWithDetails(sessionId)?.actions.orEmpty()
            .filter { it.status == ActionStatus.PENDING && it.type.actionable }
        pending.forEach { approveAction(it.id) }
        return pending.size
    }

    suspend fun setContact(sessionId: Long, name: String, relation: String?, phone: String?) {
        val session = dao.getSession(sessionId) ?: return
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            dao.updateSession(session.copy(contactId = null, contactName = null))
            return
        }
        val existing = contactDao.findByName(cleanName)
            ?: phone?.takeIf { it.isNotBlank() }?.let { contactDao.findByPhone(it) }
        val contact = (existing ?: ContactReferenceEntity(name = cleanName)).copy(
            name = cleanName,
            relation = relation?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.relation,
            phoneNumber = phone?.takeIf { it.isNotBlank() } ?: existing?.phoneNumber,
        )
        val upserted = contactDao.upsert(contact)
        val id = if (contact.id != 0L) contact.id else upserted
        dao.updateSession(session.copy(contactId = id, contactName = cleanName))
    }

    suspend fun findContactName(phone: String?): ContactReferenceEntity? =
        phone?.takeIf { it.isNotBlank() }?.let { contactDao.findByPhone(it) }

    suspend fun deleteRecording(sessionId: Long) {
        val s = dao.getSession(sessionId) ?: return
        s.audioPath?.let { File(it).delete() }
        dao.updateSession(s.copy(audioPath = null, audioMime = null))
    }

    suspend fun deleteSession(sessionId: Long) {
        dao.getSession(sessionId)?.audioPath?.let { File(it).delete() }
        dao.deleteSession(sessionId)
    }

    suspend fun deleteAllRecordings() {
        dao.getSessionsWithAudio().forEach { s ->
            s.audioPath?.let { File(it).delete() }
            dao.updateSession(s.copy(audioPath = null, audioMime = null))
        }
        recordingsDir.listFiles()?.forEach { it.delete() }
    }

    fun wipeRecordingFiles() {
        recordingsDir.listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.filter { it.name.startsWith("audio_") || it.name.startsWith("capture_") || it.name.startsWith("import_") }
            ?.forEach { it.delete() }
    }
}

@Singleton
class ProfileRepository @Inject constructor(private val db: AppDatabase) {
    fun observe(): Flow<UserSettingsEntity?> = db.userSettingsDao().observe()
    suspend fun get(): UserSettingsEntity = db.userSettingsDao().get() ?: UserSettingsEntity()
    suspend fun update(transform: (UserSettingsEntity) -> UserSettingsEntity) {
        db.userSettingsDao().upsert(transform(get()))
    }
}

/** Default priority used when the user quickly adds a task without one. */
val DEFAULT_PRIORITY = Priority.MEDIUM
