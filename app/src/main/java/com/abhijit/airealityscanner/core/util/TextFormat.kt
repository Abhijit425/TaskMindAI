package com.abhijit.airealityscanner.core.util

import java.text.DateFormat
import java.util.Date

object TextFormat {
    /** Removes the most common Markdown so text reads naturally aloud or in plain exports. */
    fun stripMarkdown(text: String): String = text
        .replace(Regex("```[a-zA-Z]*\\n?"), "")
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("(?m)^#{1,6}\\s*"), "")
        .replace(Regex("(?m)^\\s*[-*]\\s+"), "• ")
        .replace(Regex("`([^`]*)`"), "$1")
        .trim()

    /**
     * Splits text into chunks no longer than [maxLength], preferring sentence and line
     * boundaries. TextToSpeech rejects inputs longer than getMaxSpeechInputLength().
     */
    fun chunkForSpeech(text: String, maxLength: Int = 3000): List<String> {
        val clean = text.trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxLength) return listOf(clean)
        val sentences = clean.split(Regex("(?<=[.!?।])\\s+|\\n+")).filter { it.isNotBlank() }
        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (sentence in sentences) {
            var piece = sentence.trim()
            while (piece.length > maxLength) {
                if (current.isNotEmpty()) { chunks += current.toString(); current.clear() }
                chunks += piece.substring(0, maxLength)
                piece = piece.substring(maxLength)
            }
            if (current.length + piece.length + 1 > maxLength) {
                chunks += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(piece)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks
    }

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}
