package com.abhijit.airealityscanner

import com.abhijit.airealityscanner.ai.parse.AnalysisParser
import com.abhijit.airealityscanner.domain.model.Confidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisParserTest {

    private val validJson = """
        {
          "subject": "Mountain bicycle",
          "confidence": "high",
          "confidence_note": "Knobby tyres and front suspension are clearly visible.",
          "summary": "This appears to be a mountain bicycle.",
          "sections": [
            {"title": "Components", "items": ["Front suspension fork", "Rear derailleur", "  "]},
            {"title": "Empty", "items": []}
          ],
          "key_facts": ["Usually 1x or 2x drivetrains"],
          "applications": ["Trail riding"],
          "safety": ["Wear a helmet"],
          "verification_note": "Brand is not visible.",
          "extracted_text": ""
        }
    """.trimIndent()

    @Test
    fun parsesValidJson() {
        val result = AnalysisParser.parse(validJson, "Claude")
        assertEquals("Mountain bicycle", result.subject)
        assertEquals(Confidence.HIGH, result.confidence)
        assertEquals(1, result.sections.size) // empty section removed
        assertEquals(listOf("Front suspension fork", "Rear derailleur"), result.sections.first().items)
        assertEquals(listOf("Wear a helmet"), result.safety)
        assertNull(result.extractedText)
        assertEquals("Claude", result.providerName)
    }

    @Test
    fun parsesJsonWrappedInMarkdownFences() {
        val raw = "Here you go:\n```json\n$validJson\n```"
        val result = AnalysisParser.parse(raw, "Claude")
        assertEquals("Mountain bicycle", result.subject)
    }

    @Test
    fun unknownConfidenceBecomesUncertain() {
        val raw = """{"subject":"Thing","confidence":"maybe","summary":"Unclear photo"}"""
        assertEquals(Confidence.UNCERTAIN, AnalysisParser.parse(raw, "x").confidence)
    }

    @Test
    fun freeTextFallsBackHonestly() {
        val raw = "I can't identify this object from the blurry photo."
        val result = AnalysisParser.parse(raw, "OpenAI")
        assertEquals(Confidence.UNCERTAIN, result.confidence)
        assertEquals(raw, result.summary)
        assertTrue(result.sections.isEmpty())
    }

    @Test
    fun parsesBulletFacts() {
        val raw = "- Fact one.\n- Fact two.\n\n3) Fact three."
        assertEquals(listOf("Fact one.", "Fact two.", "Fact three."), AnalysisParser.parseBulletLines(raw))
    }
}
