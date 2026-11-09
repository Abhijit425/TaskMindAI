package com.abhijit.airealityscanner.data.mapper

import com.abhijit.airealityscanner.data.local.db.ConversationMessageEntity
import com.abhijit.airealityscanner.data.local.db.ScanCategoryEntity
import com.abhijit.airealityscanner.data.local.db.ScanResultEntity
import com.abhijit.airealityscanner.data.local.db.SessionWithDetails
import com.abhijit.airealityscanner.domain.model.AnalysisSection
import com.abhijit.airealityscanner.domain.model.ChatMessage
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.Confidence
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import com.abhijit.airealityscanner.domain.model.ScanCategory
import com.abhijit.airealityscanner.domain.model.ScanImage
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ScanSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Serializable
private data class SectionJson(val title: String, val items: List<String>)

private val mapperJson = Json { ignoreUnknownKeys = true }
private val stringList = ListSerializer(String.serializer())
private val sectionList = ListSerializer(SectionJson.serializer())

private fun decodeList(value: String): List<String> =
    runCatching { mapperJson.decodeFromString(stringList, value) }.getOrDefault(emptyList())

private fun encodeList(value: List<String>): String = mapperJson.encodeToString(stringList, value)

fun ScanResultEntity.toDomain(): ScanAnalysis = ScanAnalysis(
    subject = subject,
    confidence = Confidence.parse(confidence),
    confidenceNote = confidenceNote,
    summary = summary,
    sections = runCatching { mapperJson.decodeFromString(sectionList, sectionsJson) }
        .getOrDefault(emptyList())
        .map { AnalysisSection(it.title, it.items) },
    keyFacts = decodeList(keyFactsJson),
    applications = decodeList(applicationsJson),
    safety = decodeList(safetyJson),
    verificationNote = verificationNote,
    extractedText = extractedText,
    rawText = rawText,
    providerName = providerName,
    createdAt = createdAt,
)

fun ScanAnalysis.toEntity(sessionId: String): ScanResultEntity = ScanResultEntity(
    sessionId = sessionId,
    subject = subject,
    confidence = confidence.name.lowercase(),
    confidenceNote = confidenceNote,
    summary = summary,
    sectionsJson = mapperJson.encodeToString(sectionList, sections.map { SectionJson(it.title, it.items) }),
    keyFactsJson = encodeList(keyFacts),
    applicationsJson = encodeList(applications),
    safetyJson = encodeList(safety),
    verificationNote = verificationNote,
    extractedText = extractedText,
    rawText = rawText,
    providerName = providerName,
    createdAt = createdAt,
)

fun SessionWithDetails.toDomain(): ScanSession = ScanSession(
    id = session.id,
    title = session.title,
    mode = ScanMode.fromId(session.mode),
    categoryId = session.categoryId,
    createdAt = session.createdAt,
    updatedAt = session.updatedAt,
    isFavourite = session.isFavourite,
    isSaved = session.isSaved,
    notes = session.notes,
    providerName = session.providerName,
    images = images.sortedBy { it.position }.map { ScanImage(it.id, it.filePath, it.width, it.height, it.source) },
    analysis = result?.toDomain(),
)

fun ConversationMessageEntity.toDomain(): ChatMessage = ChatMessage(
    id = id,
    sessionId = sessionId,
    role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.SYSTEM),
    text = text,
    createdAt = createdAt,
)

fun ScanCategoryEntity.toDomain(): ScanCategory = ScanCategory(id, name, colorHex)
