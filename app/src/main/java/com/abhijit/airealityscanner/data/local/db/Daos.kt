package com.abhijit.airealityscanner.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanSessionDao {
    @Transaction
    @Query(
        """
        SELECT s.* FROM scan_sessions s
        LEFT JOIN scan_results r ON r.sessionId = s.id
        WHERE s.isSaved = 1
          AND (:favouritesOnly = 0 OR s.isFavourite = 1)
          AND (:categoryId = '' OR s.categoryId = :categoryId)
          AND (
            :query = ''
            OR s.title LIKE '%' || :query || '%'
            OR s.notes LIKE '%' || :query || '%'
            OR r.subject LIKE '%' || :query || '%'
            OR r.summary LIKE '%' || :query || '%'
            OR r.extractedText LIKE '%' || :query || '%'
          )
        ORDER BY s.updatedAt DESC
        """,
    )
    fun search(query: String, favouritesOnly: Boolean, categoryId: String): Flow<List<SessionWithDetails>>

    @Transaction
    @Query("SELECT * FROM scan_sessions WHERE isSaved = 1 ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatest(): Flow<SessionWithDetails?>

    @Transaction
    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionWithDetails?>

    @Transaction
    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun get(id: String): SessionWithDetails?

    @Transaction
    @Query("SELECT * FROM scan_sessions WHERE isSaved = 1 ORDER BY updatedAt DESC")
    suspend fun getSaved(): List<SessionWithDetails>

    @Upsert
    suspend fun upsert(session: ScanSessionEntity)

    @Query("UPDATE scan_sessions SET isFavourite = :favourite, updatedAt = :now WHERE id = :id")
    suspend fun setFavourite(id: String, favourite: Boolean, now: Long)

    @Query("UPDATE scan_sessions SET isSaved = :saved, updatedAt = :now WHERE id = :id")
    suspend fun setSaved(id: String, saved: Boolean, now: Long)

    @Query("UPDATE scan_sessions SET notes = :notes, updatedAt = :now WHERE id = :id")
    suspend fun setNotes(id: String, notes: String, now: Long)

    @Query("UPDATE scan_sessions SET providerName = :provider, updatedAt = :now WHERE id = :id")
    suspend fun setProvider(id: String, provider: String, now: Long)

    @Query("UPDATE scan_sessions SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: String, now: Long)

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM scan_sessions")
    suspend fun deleteAll()

    @Query("SELECT id FROM scan_sessions WHERE isSaved = 0 AND updatedAt < :before")
    suspend fun unsavedOlderThan(before: Long): List<String>

    @Query("SELECT id FROM scan_sessions WHERE updatedAt < :before")
    suspend fun olderThan(before: Long): List<String>
}

@Dao
interface ScannedImageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(images: List<ScannedImageEntity>)

    @Query("SELECT * FROM scanned_images WHERE sessionId = :sessionId ORDER BY position")
    suspend fun forSession(sessionId: String): List<ScannedImageEntity>

    @Query("SELECT filePath FROM scanned_images")
    suspend fun allPaths(): List<String>

    @Query("DELETE FROM scanned_images WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)

    @Query("DELETE FROM scanned_images")
    suspend fun deleteAll()
}

@Dao
interface ScanResultDao {
    @Upsert
    suspend fun upsert(result: ScanResultEntity)

    @Query("SELECT * FROM scan_results WHERE sessionId = :sessionId")
    suspend fun get(sessionId: String): ScanResultEntity?
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_messages WHERE sessionId = :sessionId ORDER BY createdAt, id")
    fun observe(sessionId: String): Flow<List<ConversationMessageEntity>>

    @Query("SELECT * FROM conversation_messages WHERE sessionId = :sessionId ORDER BY createdAt, id")
    suspend fun list(sessionId: String): List<ConversationMessageEntity>

    @Insert
    suspend fun insert(message: ConversationMessageEntity): Long

    @Query("DELETE FROM conversation_messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: String)
}

@Dao
interface ScanCategoryDao {
    @Query("SELECT * FROM scan_categories ORDER BY sortOrder")
    fun observeAll(): Flow<List<ScanCategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<ScanCategoryEntity>)
}

@Dao
interface UserPreferenceDao {
    @Query("SELECT * FROM user_preferences WHERE `key` = :key")
    suspend fun get(key: String): UserPreferenceEntity?

    @Upsert
    suspend fun upsert(pref: UserPreferenceEntity)
}
