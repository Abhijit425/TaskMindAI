package com.abhijit.airealityscanner.ai.parse

import com.abhijit.airealityscanner.domain.model.AnalysisSection
import com.abhijit.airealityscanner.domain.model.Confidence
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Parses the model's JSON answer. Falls back to showing the raw text honestly if parsing fails. */
object AnalysisParser {

    @Serializable
    private data class SectionDto(val title: String = "", val items: List<String> = emptyList())

    @Serializable
    private data class AnalysisDto(
        val subject: String = "",
        val confidence: String = "uncertain",
        @SerialName("confidence_note") val confidenceNote: String = "",
        val summary: String = "",
        val sections: List<SectionDto> = emptyList(),
        @SerialName("key_facts") val keyFacts: List<String> = emptyList(),
        val applications: List<String> = emptyList(),
        val safety: List<String> = emptyList(),
        @SerialName("verification_note") val verificationNote: String = "",
        @SerialName("extracted_text") val extractedText: String = "",
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun extractJsonObject(raw: String): String? {
        val cleaned = raw.replace(Regex("```(?:json)?"), "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else null
    }

    fun parse(raw: String, providerName: String): ScanAnalysis {
        val dto = extractJsonObject(raw)?.let { runCatching { json.decodeFromString(AnalysisDto.serializer(), it) }.getOrNull() }
        if (dto == null || (dto.subject.isBlank() && dto.summary.isBlank())) {
            return ScanAnalysis(
                subject = "AI analysis",
                confidence = Confidence.UNCERTAIN,
                confidenceNote = "The AI answered in free text, so no structured confidence rating is available.",
                summary = raw.trim(),
                sections = emptyList(),
                keyFacts = emptyList(),
                applications = emptyList(),
                safety = emptyList(),
                verificationNote = "",
                extractedText = null,
                rawText = raw,
                providerName = providerName,
            )
        }
        return ScanAnalysis(
            subject = dto.subject.ifBlank { "Not confidently identified" }.trim(),
            confidence = Confidence.parse(dto.confidence),
            confidenceNote = dto.confidenceNote.trim(),
            summary = dto.summary.trim(),
            sections = dto.sections
                .map { s -> AnalysisSection(s.title.trim(), s.items.map { it.trim() }.filter { it.isNotEmpty() }) }
                .filter { it.title.isNotEmpty() && it.items.isNotEmpty() },
            keyFacts = dto.keyFacts.clean(),
            applications = dto.applications.clean(),
            safety = dto.safety.clean(),
            verificationNote = dto.verificationNote.trim(),
            extractedText = dto.extractedText.trim().ifBlank { null },
            rawText = raw,
            providerName = providerName,
        )
    }

    private fun List<String>.clean() = map { it.trim() }.filter { it.isNotEmpty() }

    /** Parses "- fact" lines returned by the Discover facts prompt. */
    fun parseBulletLines(raw: String): List<String> = raw.lines()
        .map { it.trim() }
        .filter { it.startsWith("-") || it.startsWith("•") || it.matches(Regex("^\\d+[.)].*")) }
        .map { it.trimStart('-', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { raw.lines().map { it.trim() }.filter { it.isNotEmpty() } }
}
