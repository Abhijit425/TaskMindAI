package com.abhijit.airealityscanner

import com.abhijit.airealityscanner.ai.model.VisionMessage
import com.abhijit.airealityscanner.ai.model.VisionRole
import com.abhijit.airealityscanner.ai.provider.ConversationBuilder
import com.abhijit.airealityscanner.core.util.TextFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationAndTextTest {

    @Test
    fun mergesConsecutiveRolesAndKeepsImages() {
        val result = ConversationBuilder.normalize(
            listOf(
                VisionMessage(VisionRole.USER, "Here is the image", listOf("IMG")),
                VisionMessage(VisionRole.USER, "What is it?"),
                VisionMessage(VisionRole.ASSISTANT, "A bicycle."),
                VisionMessage(VisionRole.ASSISTANT, ""),
                VisionMessage(VisionRole.USER, "How do gears work?"),
            ),
        )
        assertEquals(3, result.size)
        assertEquals(listOf("IMG"), result[0].imagesBase64Jpeg)
        assertEquals("Here is the image\n\nWhat is it?", result[0].text)
        assertEquals(VisionRole.ASSISTANT, result[1].role)
        assertEquals(VisionRole.USER, result.last().role)
    }

    @Test
    fun conversationAlwaysStartsAndEndsWithUser() {
        val result = ConversationBuilder.normalize(listOf(VisionMessage(VisionRole.ASSISTANT, "Hi")))
        assertEquals(VisionRole.USER, result.first().role)
        assertEquals(VisionRole.USER, result.last().role)
    }

    @Test
    fun stripsMarkdownForSpeech() {
        val md = "## Title\n**Bold** and `code`\n- item one\n* item two"
        val plain = TextFormat.stripMarkdown(md)
        assertEquals("Title\nBold and code\n• item one\n• item two", plain)
    }

    @Test
    fun chunksLongTextWithinLimit() {
        val sentence = "This is a sentence about machines. "
        val text = sentence.repeat(200)
        val chunks = TextFormat.chunkForSpeech(text, 500)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.length <= 500 })
        assertEquals(text.trim().replace(" ", ""), chunks.joinToString(" ").replace(" ", ""))
    }

    @Test
    fun shortTextIsSingleChunk() {
        assertEquals(listOf("Hello."), TextFormat.chunkForSpeech("  Hello.  ", 100))
        assertTrue(TextFormat.chunkForSpeech("   ", 100).isEmpty())
    }
}
