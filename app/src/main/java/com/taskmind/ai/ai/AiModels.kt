package com.taskmind.ai.ai

import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.Recurrence
import java.time.LocalDateTime

data class ParsedTask(
    val title: String,
    val notes: String = "",
    val dueAt: LocalDateTime? = null,
    val hasTime: Boolean = false,
    val priority: Priority = Priority.MEDIUM,
    val category: String? = null,
    val tags: List<String> = emptyList(),
    val recurrence: Recurrence = Recurrence.NONE,
    val assignedBy: String? = null,
)

data class ExtractedItem(
    val type: ActionType,
    val title: String,
    val detail: String = "",
    val dueAt: LocalDateTime? = null,
    val hasTime: Boolean = false,
    val priority: Priority = Priority.MEDIUM,
    val category: String? = null,
    val assignedBy: String? = null,
)

data class ExtractionResult(
    val summary: String,
    val items: List<ExtractedItem>,
)

data class ChatMessage(val role: String, val content: String) {
    companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

class AiException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Which engine actually produced a result — always shown to the user. */
data class Outcome<T>(val value: T, val engine: String, val note: String? = null)
