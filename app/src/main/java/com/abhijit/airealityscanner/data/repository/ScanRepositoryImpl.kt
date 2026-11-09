package com.abhijit.airealityscanner.data.repository

import androidx.room.withTransaction
import com.abhijit.airealityscanner.data.local.ImageStorage
import com.abhijit.airealityscanner.data.local.db.AppDatabase
import com.abhijit.airealityscanner.data.local.db.ConversationMessageEntity
import com.abhijit.airealityscanner.data.local.db.ScanSessionEntity
import com.abhijit.airealityscanner.data.local.db.ScannedImageEntity
import com.abhijit.airealityscanner.data.local.db.UserPreferenceEntity
import com.abhijit.airealityscanner.data.mapper.toDomain
import com.abhijit.airealityscanner.data.mapper.toEntity
import com.abhijit.airealityscanner.domain.model.ChatMessage
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import com.abhijit.airealityscanner.domain.model.ScanCategory
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ScanSession
import com.abhijit.airealityscanner.domain.model.StoredImage
import com.abhijit.airealityscanner.domain.repository.ScanRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScanRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val imageStorage: ImageStorage,
) : ScanRepository {

    private val sessions = db.sessionDao()
    private val images = db.imageDao()
    private val results = db.resultDao()
    private val messages = db.conversationDao()
    private val categories = db.categoryDao()
    private val prefs = db.preferenceDao()

    override fun observeHistory(query: String, favouritesOnly: Boolean, categoryId: String?): Flow<List<ScanSession>> =
        sessions.search(query.trim(), favouritesOnly, categoryId.orEmpty()).map { list -> list.map { it.toDomain() } }

    override fun observeLatest(): Flow<ScanSession?> = sessions.observeLatest().map { it?.toDomain() }

    override fun observeSession(id: String): Flow<ScanSession?> = sessions.observe(id).map { it?.toDomain() }

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        messages.observe(sessionId).map { list -> list.map { it.toDomain() } }

    override fun observeCategories(): Flow<List<ScanCategory>> =
        categories.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun getSession(id: String): ScanSession? = sessions.get(id)?.toDomain()

    override suspend fun getMessages(sessionId: String): List<ChatMessage> =
        messages.list(sessionId).map { it.toDomain() }

    override suspend fun getSavedSessions(): List<ScanSession> = sessions.getSaved().map { it.toDomain() }

    override suspend fun createSession(
        mode: ScanMode,
        title: String,
        images: List<StoredImage>,
        saved: Boolean,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.withTransaction {
            sessions.upsert(
                ScanSessionEntity(
                    id = id,
                    title = title,
                    mode = mode.id,
                    categoryId = mode.id,
                    createdAt = now,
                    updatedAt = now,
                    isSaved = saved,
                ),
            )
            this.images.insertAll(
                images.mapIndexed { index, img ->
                    ScannedImageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = id,
                        filePath = img.path,
                        width = img.width,
                        height = img.height,
                        position = index,
                        source = img.source,
                        createdAt = now,
                    )
                },
            )
        }
        return id
    }

    override suspend fun saveResult(sessionId: String, analysis: ScanAnalysis) {
        db.withTransaction {
            results.upsert(analysis.toEntity(sessionId))
            sessions.setProvider(sessionId, analysis.providerName, System.currentTimeMillis())
        }
    }

    override suspend fun addMessage(sessionId: String, role: ChatRole, text: String) {
        val now = System.currentTimeMillis()
        messages.insert(ConversationMessageEntity(sessionId = sessionId, role = role.name, text = text, createdAt = now))
        sessions.touch(sessionId, now)
    }

    override suspend fun setFavourite(sessionId: String, favourite: Boolean) =
        sessions.setFavourite(sessionId, favourite, System.currentTimeMillis())

    override suspend fun setSaved(sessionId: String, saved: Boolean) =
        sessions.setSaved(sessionId, saved, System.currentTimeMillis())

    override suspend fun setNotes(sessionId: String, notes: String) =
        sessions.setNotes(sessionId, notes, System.currentTimeMillis())

    override suspend fun deleteSession(sessionId: String) {
        val paths = images.forSession(sessionId).map { it.filePath }
        db.withTransaction {
            messages.deleteForSession(sessionId)
            images.deleteForSession(sessionId)
            sessions.delete(sessionId)
        }
        paths.forEach(imageStorage::delete)
    }

    override suspend fun deleteSessionImages(sessionId: String) {
        val paths = images.forSession(sessionId).map { it.filePath }
        images.deleteForSession(sessionId)
        paths.forEach(imageStorage::delete)
    }

    override suspend fun deleteAllHistory() {
        db.withTransaction {
            images.deleteAll()
            sessions.deleteAll()
        }
        imageStorage.deleteAll()
    }

    override suspend fun deleteAllImages() {
        images.deleteAll()
        imageStorage.deleteAll()
    }

    override suspend fun cleanup(unsavedOlderThanMs: Long, autoDeleteDays: Int): Int {
        val now = System.currentTimeMillis()
        val ids = buildSet {
            addAll(sessions.unsavedOlderThan(now - unsavedOlderThanMs))
            if (autoDeleteDays > 0) addAll(sessions.olderThan(now - TimeUnit.DAYS.toMillis(autoDeleteDays.toLong())))
        }
        ids.forEach { deleteSession(it) }
        // Remove image files that are no longer referenced by any scan.
        val referenced = images.allPaths().toSet()
        imageStorage.listFiles()
            .filter { it.absolutePath !in referenced && now - it.lastModified() > TimeUnit.HOURS.toMillis(1) }
            .forEach { it.delete() }
        return ids.size
    }

    override suspend fun getPreference(key: String): String? = prefs.get(key)?.value

    override suspend fun setPreference(key: String, value: String) =
        prefs.upsert(UserPreferenceEntity(key, value, System.currentTimeMillis()))
}
