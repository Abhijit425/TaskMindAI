package com.abhijit.airealityscanner.data.local.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(
    tableName = "scan_sessions",
    indices = [Index("categoryId"), Index("updatedAt")],
)
data class ScanSessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val mode: String,
    val categoryId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavourite: Boolean = false,
    val isSaved: Boolean = true,
    val notes: String = "",
    val providerName: String = "",
)

@Entity(
    tableName = "scanned_images",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ScannedImageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val filePath: String,
    val width: Int,
    val height: Int,
    val position: Int,
    val source: String,
    val createdAt: Long,
)

@Entity(
    tableName = "scan_results",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ScanResultEntity(
    @PrimaryKey val sessionId: String,
    val subject: String,
    val confidence: String,
    val confidenceNote: String,
    val summary: String,
    val sectionsJson: String,
    val keyFactsJson: String,
    val applicationsJson: String,
    val safetyJson: String,
    val verificationNote: String,
    val extractedText: String?,
    val rawText: String,
    val providerName: String,
    val createdAt: Long,
)

@Entity(
    tableName = "conversation_messages",
    foreignKeys = [
        ForeignKey(
            entity = ScanSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class ConversationMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "scan_categories")
data class ScanCategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String,
    val sortOrder: Int,
)

/** Small key/value table for app-generated preferences such as cached Discover facts. */
@Entity(tableName = "user_preferences")
data class UserPreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
)

data class SessionWithDetails(
    @Embedded val session: ScanSessionEntity,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val images: List<ScannedImageEntity>,
    @Relation(parentColumn = "id", entityColumn = "sessionId")
    val result: ScanResultEntity?,
)
