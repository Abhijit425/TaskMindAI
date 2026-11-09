package com.abhijit.airealityscanner.domain.model

enum class Confidence(val label: String) {
    HIGH("High confidence"),
    MEDIUM("Medium confidence"),
    LOW("Low confidence"),
    UNCERTAIN("Not confidently identified");

    companion object {
        fun parse(value: String?): Confidence = when (value?.trim()?.lowercase()) {
            "high" -> HIGH
            "medium", "moderate" -> MEDIUM
            "low" -> LOW
            else -> UNCERTAIN
        }
    }
}

data class AnalysisSection(
    val title: String,
    val items: List<String>,
)

/** Structured result of analysing one or more images. */
data class ScanAnalysis(
    val subject: String,
    val confidence: Confidence,
    val confidenceNote: String,
    val summary: String,
    val sections: List<AnalysisSection>,
    val keyFacts: List<String>,
    val applications: List<String>,
    val safety: List<String>,
    val verificationNote: String,
    val extractedText: String?,
    val rawText: String,
    val providerName: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    /** Short text suitable for text-to-speech. */
    fun spokenSummary(): String = buildString {
        append(subject.trim().trimEnd('.'))
        append(". ")
        if (confidence == Confidence.LOW || confidence == Confidence.UNCERTAIN) {
            append(confidenceNote.ifBlank { confidence.label })
            append(". ")
        }
        append(summary)
    }

    /** Full plain-text version used for sharing and export. */
    fun toPlainText(): String = buildString {
        appendLine(subject)
        appendLine("${confidence.label}${if (confidenceNote.isNotBlank()) " — $confidenceNote" else ""}")
        appendLine()
        if (summary.isNotBlank()) appendLine(summary).also { appendLine() }
        sections.forEach { section ->
            appendLine(section.title)
            section.items.forEach { appendLine("• $it") }
            appendLine()
        }
        if (keyFacts.isNotEmpty()) {
            appendLine("Important facts"); keyFacts.forEach { appendLine("• $it") }; appendLine()
        }
        if (applications.isNotEmpty()) {
            appendLine("Practical applications"); applications.forEach { appendLine("• $it") }; appendLine()
        }
        if (safety.isNotEmpty()) {
            appendLine("Safety"); safety.forEach { appendLine("• $it") }; appendLine()
        }
        if (verificationNote.isNotBlank()) appendLine("Verification: $verificationNote")
        if (!extractedText.isNullOrBlank()) {
            appendLine(); appendLine("Extracted text:"); appendLine(extractedText)
        }
    }.trim()
}
