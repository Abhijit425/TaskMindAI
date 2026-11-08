package com.taskmind.ai

import com.taskmind.ai.ai.DateTimeParser
import com.taskmind.ai.ai.TaskTextParser
import com.taskmind.ai.ai.TranscriptExtractor
import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.Recurrence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LocalNlpTest {
    // Sunday 8 November 2026, 10:00
    private val now = LocalDateTime.of(2026, 11, 8, 10, 0)

    @Test
    fun doctorNextWednesdayAt11() {
        val p = TaskTextParser.parse("Remind me to call the doctor next Wednesday at 11 AM.", now)
        assertEquals("Call the doctor", p.title)
        assertEquals(LocalDateTime.of(2026, 11, 11, 11, 0), p.dueAt)
        assertTrue(p.hasTime)
        assertEquals("Health", p.category)
    }

    @Test
    fun nextWeekdayFromMondayMeansFollowingWeek() {
        val monday = LocalDateTime.of(2026, 11, 9, 9, 0)
        val r = DateTimeParser.parse("next wednesday", monday)
        assertEquals(LocalDate.of(2026, 11, 18), r.dateTime?.toLocalDate())
        val plain = DateTimeParser.parse("on wednesday", monday)
        assertEquals(LocalDate.of(2026, 11, 11), plain.dateTime?.toLocalDate())
    }

    @Test
    fun electricityBillTomorrow() {
        val p = TaskTextParser.parse("Remind me to pay electricity bill tomorrow.", now)
        assertEquals("Pay electricity bill", p.title)
        assertEquals(LocalDate.of(2026, 11, 9), p.dueAt?.toLocalDate())
        assertFalse(p.hasTime)
        assertEquals(9, p.dueAt?.hour)
        assertEquals("Finance", p.category)
        assertEquals(Priority.HIGH, p.priority)
    }

    @Test
    fun managerAt4pm() {
        val p = TaskTextParser.parse("Add a task to call my manager at 4 PM.", now)
        assertEquals("Call my manager", p.title)
        assertEquals(LocalDateTime.of(2026, 11, 8, 16, 0), p.dueAt)
        assertEquals("Work", p.category)
    }

    @Test
    fun timeEarlierThanNowRollsToTomorrow() {
        val r = DateTimeParser.parse("at 9am", now)
        assertEquals(LocalDateTime.of(2026, 11, 9, 9, 0), r.dateTime)
    }

    @Test
    fun relativeAndExplicitDates() {
        assertEquals(now.plusHours(2), DateTimeParser.parse("in 2 hours", now).dateTime)
        assertEquals(LocalDate.of(2026, 11, 11), DateTimeParser.parse("in three days", now).dateTime?.toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 15), DateTimeParser.parse("by 15/12", now).dateTime?.toLocalDate())
        assertEquals(LocalDate.of(2026, 12, 3), DateTimeParser.parse("on 3rd December", now).dateTime?.toLocalDate())
        assertEquals(LocalDate.of(2027, 1, 20), DateTimeParser.parse("Jan 20", now).dateTime?.toLocalDate())
        assertNull(DateTimeParser.parse("buy milk", now).dateTime)
    }

    @Test
    fun recurrenceTagsAndPriority() {
        val p = TaskTextParser.parse("Water the plants every Monday #home urgent", now)
        assertEquals(Recurrence.WEEKLY, p.recurrence)
        assertEquals(LocalDate.of(2026, 11, 9), p.dueAt?.toLocalDate())
        assertTrue(p.tags.contains("home"))
        assertEquals(Priority.URGENT, p.priority)
        assertEquals("Water the plants", p.title)

        val low = TaskTextParser.parse("Read that article sometime, no rush", now)
        assertEquals(Priority.LOW, low.priority)
    }

    @Test
    fun assignedByIsDetected() {
        val p = TaskTextParser.parse("My manager asked me to prepare the sprint report by Friday", now)
        assertEquals("Manager", p.assignedBy)
        assertEquals("Prepare the sprint report", p.title)
        assertEquals(LocalDate.of(2026, 11, 13), p.dueAt?.toLocalDate())
    }

    @Test
    fun specExampleConversation() {
        val transcript = "Abhijit, please complete the QAD customization by Monday. Also send the updated document to the client tomorrow."
        val r = TranscriptExtractor.extract(transcript, now, contactName = "Rahul")
        val tasks = r.items.filter { it.type == ActionType.TASK }
        assertEquals(2, tasks.size)

        val first = tasks[0]
        assertEquals("Complete the QAD customization", first.title)
        assertEquals(LocalDate.of(2026, 11, 9), first.dueAt?.toLocalDate())
        assertEquals(Priority.HIGH, first.priority)
        assertEquals("Work", first.category)
        assertEquals("Rahul", first.assignedBy)

        val second = tasks[1]
        assertEquals("Send the updated document to the client", second.title)
        assertEquals(LocalDate.of(2026, 11, 9), second.dueAt?.toLocalDate())
        assertEquals(Priority.HIGH, second.priority)
        assertEquals("Work", second.category)

        assertTrue(r.summary.contains("2 tasks"))
        assertTrue(r.items.any { it.type == ActionType.NAME && it.title == "Abhijit" })
    }

    @Test
    fun commitmentsFollowUpsAndContactDetails() {
        val transcript = """
            Priya: Can you share the invoice with finance today?
            Me: Sure. I'll call you back tomorrow at 3 pm with the numbers.
            Priya: Great, also let's meet on Thursday at 11 to review the proposal. My number is +91 98300 12345 and email priya@acme.in
        """.trimIndent()
        val r = TranscriptExtractor.extract(transcript, now, contactName = "Priya")
        assertTrue(r.items.any { it.type == ActionType.TASK && it.title.startsWith("Share the invoice") })
        val followUp = r.items.first { it.type == ActionType.FOLLOW_UP }
        assertEquals("Call Priya back with the numbers", followUp.title)
        assertEquals(LocalDateTime.of(2026, 11, 9, 15, 0), followUp.dueAt)
        assertNotNull(r.items.firstOrNull { it.type == ActionType.MEETING })
        assertTrue(r.items.any { it.type == ActionType.PHONE && it.title == "+919830012345" })
        assertTrue(r.items.any { it.type == ActionType.EMAIL && it.title.contains("priya@acme.in") })
    }

    @Test
    fun emptyConversationSaysNothingFound() {
        val r = TranscriptExtractor.extract("Hello. How are you? Fine thanks.", now)
        assertTrue(r.items.none { it.type.actionable })
        assertTrue(r.summary.startsWith("No clear tasks"))
    }

    @Test
    fun othersPromisesBecomeFollowUpsAndMyRequestsAreSkipped() {
        val transcript = "Rahul: I will send you the access details today.\nMe: Please share the VPN link too.\nMe: I'll review the test plan by Friday."
        val r = TranscriptExtractor.extract(transcript, now, contactName = "Rahul")
        val follow = r.items.first { it.type == ActionType.FOLLOW_UP }
        assertEquals("Follow up: Rahul to send me the access details", follow.title)
        assertTrue(r.items.none { it.title.contains("VPN") })
        val mine = r.items.first { it.type == ActionType.COMMITMENT }
        assertEquals("Review the test plan", mine.title)
        assertEquals(LocalDate.of(2026, 11, 13), mine.dueAt?.toLocalDate())
    }

    @Test
    fun ordinalDayOfMonth() {
        val p = TaskTextParser.parse("Submit GST return by 20th", now)
        assertEquals("Submit GST return", p.title)
        assertEquals(LocalDate.of(2026, 11, 20), p.dueAt?.toLocalDate())
        val past = TaskTextParser.parse("Pay rent on the 5th", now)
        assertEquals(LocalDate.of(2026, 12, 5), past.dueAt?.toLocalDate())
    }
}
