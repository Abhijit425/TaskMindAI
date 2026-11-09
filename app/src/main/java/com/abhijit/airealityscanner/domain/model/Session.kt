package com.abhijit.airealityscanner.domain.model

data class ScanImage(
    val id: String,
    val path: String,
    val width: Int,
    val height: Int,
    val source: String,
)

data class ScanSession(
    val id: String,
    val title: String,
    val mode: ScanMode,
    val categoryId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isFavourite: Boolean,
    val isSaved: Boolean,
    val notes: String,
    val providerName: String,
    val images: List<ScanImage>,
    val analysis: ScanAnalysis?,
) {
    val displayTitle: String
        get() = analysis?.subject?.takeIf { it.isNotBlank() } ?: title
    val thumbnailPath: String?
        get() = images.firstOrNull()?.path
}

enum class ChatRole { USER, ASSISTANT, SYSTEM }

data class ChatMessage(
    val id: Long,
    val sessionId: String,
    val role: ChatRole,
    val text: String,
    val createdAt: Long,
)

data class ScanCategory(
    val id: String,
    val name: String,
    val colorHex: String,
)

/** A decoded image that has been persisted to private app storage. */
data class StoredImage(
    val path: String,
    val width: Int,
    val height: Int,
    val source: String,
)
