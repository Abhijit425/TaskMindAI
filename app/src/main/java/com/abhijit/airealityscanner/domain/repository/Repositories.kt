package com.abhijit.airealityscanner.domain.repository

import com.abhijit.airealityscanner.domain.model.AiProviderType
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.model.ChatMessage
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import com.abhijit.airealityscanner.domain.model.ScanCategory
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ScanSession
import com.abhijit.airealityscanner.domain.model.StoredImage
import kotlinx.coroutines.flow.Flow

interface ScanRepository {
    fun observeHistory(query: String, favouritesOnly: Boolean, categoryId: String?): Flow<List<ScanSession>>
    fun observeLatest(): Flow<ScanSession?>
    fun observeSession(id: String): Flow<ScanSession?>
    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>
    fun observeCategories(): Flow<List<ScanCategory>>

    suspend fun getSession(id: String): ScanSession?
    suspend fun getMessages(sessionId: String): List<ChatMessage>
    suspend fun getSavedSessions(): List<ScanSession>

    suspend fun createSession(mode: ScanMode, title: String, images: List<StoredImage>, saved: Boolean): String
    suspend fun saveResult(sessionId: String, analysis: ScanAnalysis)
    suspend fun addMessage(sessionId: String, role: ChatRole, text: String)

    suspend fun setFavourite(sessionId: String, favourite: Boolean)
    suspend fun setSaved(sessionId: String, saved: Boolean)
    suspend fun setNotes(sessionId: String, notes: String)

    suspend fun deleteSession(sessionId: String)
    suspend fun deleteSessionImages(sessionId: String)
    suspend fun deleteAllHistory()
    suspend fun deleteAllImages()

    /** Removes unsaved scans and (optionally) scans older than [autoDeleteDays]. Returns removed count. */
    suspend fun cleanup(unsavedOlderThanMs: Long, autoDeleteDays: Int): Int

    suspend fun getPreference(key: String): String?
    suspend fun setPreference(key: String, value: String)
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
    suspend fun setApiKey(provider: AiProviderType, key: String?)
    fun getApiKey(provider: AiProviderType): String?
}
