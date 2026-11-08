package com.taskmind.ai.ai

import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.Recurrence
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * Fully on-device natural-language engine. No network, no model download.
 * Pure Kotlin (java.time only) so it is unit-tested on the JVM.
 */
object DateTimeParser {

    data class Result(val dateTime: LocalDateTime?, val hasTime: Boolean, val spans: List<IntRange>)

    private val NUMBERS = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10, "a couple of" to 2, "a few" to 3,
    )

    private val DAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY,
    )

    private const val PREP = """(?:(?:by|on|before|until|till|due|for|from|this coming)\s+)?"""
    private const val MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""

    private val rxRelative = Regex("""\b(?:(?:with)?in|after)\s+(\d+|a couple of|a few|an|a|one|two|three|four|five|six|seven|eight|nine|ten)\s+(minutes?|mins?|hours?|hrs?|days?|weeks?|months?)\b""")
    private val rxDayAfter = Regex("""\b$PREP(?:the\s+)?day after tomorrow\b""")
    private val rxSimpleDay = Regex("""\b$PREP(today|tonight|tomorrow|tomorow|tmrw|tmr)\b""")
    private val rxNextWeek = Regex("""\b$PREP(?:the\s+)?next week\b""")
    private val rxNextMonth = Regex("""\b$PREP(?:the\s+)?next month\b""")
    private val rxEndOfWeek = Regex("""\b$PREP(?:the\s+)?(?:end of (?:the |this )?week|eow)\b""")
    private val rxEndOfMonth = Regex("""\b$PREP(?:the\s+)?(?:end of (?:the |this )?month|eom)\b""")
    private val rxWeekend = Regex("""\b$PREP(?:this\s+|the\s+)?weekend\b""")
    private val rxWeekdayFull = Regex("""\b$PREP(?:(next|this|coming)\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
    private val rxWeekdayShort = Regex("""\b(?:(?:by|on|before|until|till|due)\s+|(next|this|coming)\s+)(mon|tues?|wed|thu(?:rs?)?|fri|sat|sun)\b""")
    private val rxNumericDate = Regex("""\b$PREP(\d{1,2})[/-](\d{1,2})(?:[/-](\d{2,4}))?\b""")
    private val rxDayMonth = Regex("""\b$PREP(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$MONTH\.?(?:,?\s+(\d{4}))?\b""")
    private val rxMonthDay = Regex("""\b$PREP$MONTH\.?\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?\b""")

    private val rxOrdinalDay = Regex("""\b(?:by|on|before|till|until|due)\s+(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)\b(?!\s+(?:of\s+)?$MONTH)""")

    private val rxTime12 = Regex("""\b(?:at\s+|by\s+|around\s+|@\s*)?(\d{1,2})(?:[:.](\d{2}))?\s*(a\.?m\.?|p\.?m\.?)(?![a-z])""")
    private val rxTime24 = Regex("""\b(?:at\s+|by\s+|around\s+|@\s*)?([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val rxOclock = Regex("""\b(?:at\s+|by\s+|around\s+)?(\d{1,2})\s*o'?clock\b""")
    private val rxAtHour = Regex("""\b(?:at|@|around)\s+(\d{1,2})\b(?!\s*(?:%|st|nd|rd|th|days?|weeks?|months?|years?|hours?|minutes?|mins?|people|items|[/:.\-]\d))""")
    private val rxNamedTime = Regex("""\b(?:at\s+|by\s+|in the\s+|this\s+|before\s+|around\s+)?(noon|midday|midnight|morning|afternoon|evening|night|eod|end of (?:the )?day|lunch ?time|lunch)\b""")

    fun parse(
        text: String,
        now: LocalDateTime,
        defaultHour: Int = 9,
        defaultMinute: Int = 0,
        dayFirst: Boolean = true,
    ): Result {
        // Lower-case char by char so indices stay aligned with the original text.
        val work = StringBuilder(String(CharArray(text.length) { text[it].lowercaseChar() }))
        val spans = mutableListOf<IntRange>()
        val today = now.toLocalDate()
        var date: LocalDate? = null
        var time: LocalTime? = null
        var tonight = false

        fun consume(m: MatchResult) {
            spans += m.range
            for (i in m.range) work.setCharAt(i, ' ')
        }

        fun take(rx: Regex): MatchResult? {
            val m = rx.find(work.toString()) ?: return null
            consume(m)
            return m
        }

        // 1. Relative offsets ("in 2 hours", "in 3 days")
        val rel = take(rxRelative)
        if (rel != null) {
            val n = (rel.groupValues[1].toIntOrNull() ?: NUMBERS[rel.groupValues[1]] ?: 1).toLong()
            val unit = rel.groupValues[2]
            when {
                unit.startsWith("min") -> return Result(now.plusMinutes(n).withSecond(0).withNano(0), true, spans)
                unit.startsWith("h") -> return Result(now.plusHours(n).withSecond(0).withNano(0), true, spans)
                unit.startsWith("d") -> date = today.plusDays(n)
                unit.startsWith("w") -> date = today.plusWeeks(n)
                unit.startsWith("mo") -> date = today.plusMonths(n)
            }
        }

        // 2. Named days
        if (date == null && take(rxDayAfter) != null) date = today.plusDays(2)
        if (date == null) {
            val m = take(rxSimpleDay)
            if (m != null) {
                val word = m.groupValues[1]
                date = if (word == "today" || word == "tonight") today else today.plusDays(1)
                tonight = word == "tonight"
            }
        }
        if (date == null && take(rxNextWeek) != null) date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        if (date == null && take(rxNextMonth) != null) date = today.plusMonths(1).withDayOfMonth(1)
        if (date == null && take(rxEndOfWeek) != null) date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
        if (date == null && take(rxEndOfMonth) != null) date = today.with(TemporalAdjusters.lastDayOfMonth())
        if (date == null && take(rxWeekend) != null) date = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))
        if (date == null) {
            take(rxWeekdayFull)?.let { m ->
                DAYS[m.groupValues[2]]?.let { date = resolveWeekday(today, it, m.groupValues[1]) }
            }
        }
        if (date == null) {
            take(rxWeekdayShort)?.let { m ->
                DAYS[m.groupValues[2]]?.let { date = resolveWeekday(today, it, m.groupValues[1]) }
            }
        }

        // 3. Explicit dates
        if (date == null) {
            rxNumericDate.find(work.toString())?.let { m ->
                val a = m.groupValues[1].toInt()
                val b = m.groupValues[2].toInt()
                val day = if (dayFirst) a else b
                val month = if (dayFirst) b else a
                val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
                safeDate(year ?: today.year, month, day)?.let { candidate ->
                    consume(m)
                    date = if (year == null && candidate.isBefore(today)) candidate.plusYears(1) else candidate
                }
            }
        }
        if (date == null) {
            rxDayMonth.find(work.toString())?.let { m ->
                val day = m.groupValues[1].toInt()
                val month = monthIndex(m.groupValues[2])
                val year = m.groupValues[3].toIntOrNull()
                safeDate(year ?: today.year, month, day)?.let { candidate ->
                    consume(m)
                    date = if (year == null && candidate.isBefore(today)) candidate.plusYears(1) else candidate
                }
            }
        }
        if (date == null) {
            rxMonthDay.find(work.toString())?.let { m ->
                val month = monthIndex(m.groupValues[1])
                val day = m.groupValues[2].toInt()
                val year = m.groupValues[3].toIntOrNull()
                safeDate(year ?: today.year, month, day)?.let { candidate ->
                    consume(m)
                    date = if (year == null && candidate.isBefore(today)) candidate.plusYears(1) else candidate
                }
            }
        }

        if (date == null) {
            rxOrdinalDay.find(work.toString())?.let { m ->
                val day = m.groupValues[1].toInt()
                var candidate = safeDate(today.year, today.monthValue, day)
                if (candidate != null && candidate.isBefore(today)) candidate = safeDate(today.plusMonths(1).year, today.plusMonths(1).monthValue, day)
                if (candidate != null) {
                    consume(m)
                    date = candidate
                }
            }
        }

        // 4. Times
        rxTime12.find(work.toString())?.let { m ->
            var h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toIntOrNull() ?: 0
            val pm = m.groupValues[3].startsWith("p")
            if (h in 1..12 && min in 0..59) {
                if (pm && h < 12) h += 12
                if (!pm && h == 12) h = 0
                time = LocalTime.of(h, min)
                consume(m)
            }
        }
        if (time == null) {
            rxTime24.find(work.toString())?.let { m ->
                time = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                consume(m)
            }
        }
        if (time == null) {
            rxOclock.find(work.toString())?.let { m ->
                val h = m.groupValues[1].toInt()
                if (h in 0..23) {
                    time = LocalTime.of(guessHour(h), 0)
                    consume(m)
                }
            }
        }
        if (time == null) {
            rxAtHour.find(work.toString())?.let { m ->
                val h = m.groupValues[1].toInt()
                if (h in 0..23) {
                    time = LocalTime.of(guessHour(h), 0)
                    consume(m)
                }
            }
        }
        if (time == null) {
            rxNamedTime.find(work.toString())?.let { m ->
                val key = m.groupValues[1]
                time = when {
                    key == "noon" || key == "midday" -> LocalTime.NOON
                    key.startsWith("lunch") -> LocalTime.of(13, 0)
                    key == "midnight" -> LocalTime.of(23, 59)
                    key == "morning" -> LocalTime.of(9, 0)
                    key == "afternoon" -> LocalTime.of(14, 0)
                    key == "evening" -> LocalTime.of(18, 0)
                    key == "night" -> LocalTime.of(21, 0)
                    else -> LocalTime.of(18, 0) // eod / end of day
                }
                consume(m)
            }
        }

        if (tonight && time == null) time = LocalTime.of(20, 0)

        val d = date
        val t = time
        return when {
            d == null && t == null -> Result(null, false, spans)
            d == null && t != null -> {
                var dt = today.atTime(t)
                if (dt.isBefore(now)) dt = dt.plusDays(1)
                Result(dt, true, spans)
            }
            d != null && t != null -> Result(d.atTime(t), true, spans)
            else -> Result(d!!.atTime(defaultHour, defaultMinute), false, spans)
        }
    }

    /** "next Wednesday" means the Wednesday of next week; plain "Wednesday" means the coming one. */
    fun resolveWeekday(today: LocalDate, dow: DayOfWeek, modifier: String): LocalDate {
        if (modifier == "this") return today.with(TemporalAdjusters.nextOrSame(dow))
        var d = today.with(TemporalAdjusters.next(dow))
        if (modifier == "next") {
            val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
            if (!d.isAfter(endOfWeek)) d = d.plusWeeks(1)
        }
        return d
    }

    /** Bare hours 1–7 almost always mean the afternoon in task phrasing ("call at 4"). */
    private fun guessHour(h: Int): Int = if (h in 1..7) h + 12 else h

    private fun monthIndex(s: String): Int = when (s.take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; else -> 12
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        runCatching { LocalDate.of(y, m, d) }.getOrNull()
}

object TaskTextParser {

    private val CATEGORY_KEYWORDS: Map<String, Set<String>> = mapOf(
        "Finance" to setOf(
            "pay", "bill", "bills", "electricity", "rent", "emi", "loan", "bank", "tax", "taxes", "insurance",
            "salary", "transfer", "recharge", "invoice", "credit", "premium", "fees", "fee", "gst",
        ),
        "Health" to setOf(
            "doctor", "dentist", "hospital", "clinic", "medicine", "medicines", "medication", "pills", "gym",
            "workout", "health", "checkup", "check-up", "vaccine", "vaccination", "pharmacy", "physio", "yoga",
        ),
        "Family" to setOf(
            "mom", "mother", "maa", "dad", "father", "papa", "wife", "husband", "son", "daughter", "baby",
            "kid", "kids", "family", "parents", "sister", "brother", "grandma", "grandpa", "birthday", "anniversary",
        ),
        "Work" to setOf(
            "work", "office", "client", "clients", "customer", "meeting", "manager", "boss", "report", "document",
            "doc", "docs", "presentation", "deck", "project", "deploy", "deployment", "release", "customization",
            "customisation", "qad", "erp", "proposal", "deliverable", "code", "bug", "review", "team", "colleague",
            "standup", "deadline", "submit", "sprint", "ticket", "jira", "timesheet", "appraisal", "interview",
            "email", "mail", "spec", "requirements", "demo", "go-live", "uat", "patch", "vendor", "supplier", "contract",
            "checklist", "test", "scripts", "release", "server",
        ),
        "Shopping" to setOf(
            "buy", "purchase", "order", "grocery", "groceries", "shop", "shopping", "amazon", "flipkart", "pickup",
        ),
    )
    private val CATEGORY_TIEBREAK = listOf("Finance", "Health", "Family", "Work", "Shopping")

    private val rxTag = Regex("""(?:^|\s)#([\p{L}\d][\p{L}\d_-]*)""")
    private val rxLow = Regex("""\b(low priority|low-priority|whenever|sometime|someday|if (?:i|you) (?:get|have) (?:the )?time|no rush|not urgent)\b""", RegexOption.IGNORE_CASE)
    private val rxUrgent = Regex("""\b(urgent(?:ly)?|asap|immediately|right away|critical|top priority|emergency)\b""", RegexOption.IGNORE_CASE)
    private val rxHigh = Regex("""\b(important|high priority|high-priority|priority|crucial|client|boss|manager|ceo|deadline)\b""", RegexOption.IGNORE_CASE)
    private val rxStripPriority = Regex("""[,\s]*\b(urgent(?:ly)?|asap|high priority|high-priority|low priority|low-priority|top priority|no rush|not urgent|it'?s important|this is important)\b[,!]*""", RegexOption.IGNORE_CASE)

    private val rxEveryWeekdayName = Regex("""\b(?:every|each)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)s?\b""", RegexOption.IGNORE_CASE)
    private val rxWeekdays = Regex("""\b(?:(?:every|each)\s+(?:weekday|working day|work day)|on weekdays|weekdays)\b""", RegexOption.IGNORE_CASE)
    private val rxDaily = Regex("""\b(?:every\s*day|each day|daily|every morning|every evening|every night)\b""", RegexOption.IGNORE_CASE)
    private val rxWeekly = Regex("""\b(?:(?:every|each)\s+week|weekly)\b""", RegexOption.IGNORE_CASE)
    private val rxMonthly = Regex("""\b(?:(?:every|each)\s+month|monthly)\b""", RegexOption.IGNORE_CASE)

    private val rxAssignedRole = Regex("""\b(?:my\s+)?(manager|boss|client|team lead|lead|wife|husband|mom|dad)\s+(?:asked|wants|told|said|needs|requested)\b""", RegexOption.IGNORE_CASE)
    private val rxAssignedName = Regex("""\b([A-Z][a-z]+(?:\s[A-Z][a-z]+)?)\s+(?:asked|wants|told|needs|requested)\s+me\b""")

    private val rxVocative = Regex("""^\s*(?:hi|hey|hello|ok|okay|so)?[,\s]*([A-Z][a-zA-Z]+)\s*,\s+""")
    private val rxLead = Regex(
        """^\s*(?:(?:hey|ok|okay|so|and|also|then|plus|please|kindly|pls|plz|just|yeah|yes|right)[,\s]+)*""" +
            """(?:(?:can|could|would|will) you\s+(?:please\s+)?(?:also\s+)?""" +
            """|(?:my\s+)?[\p{L}]+(?:\s[\p{L}]+)?\s+(?:asked|wants|told|needs|requested) me to\s+""" +
            """|remind me (?:to|that|about)\s+|remind me\s+""" +
            """|add (?:a |an |new )?(?:task|todo|to-do|reminder)(?: to| for| that|:)?\s+""" +
            """|create (?:a |an |new )?(?:task|todo|reminder)(?: to| for|:)?\s+""" +
            """|new task:?\s+|task:\s*|todo:\s*""" +
            """|set (?:a )?reminder (?:to|for)\s+""" +
            """|i (?:need|have|want|got|gotta) to\s+|i must\s+|i should\s+|we need to\s+|need to\s+|have to\s+|must\s+""" +
            """|don'?t forget (?:to\s+)?|do not forget (?:to\s+)?|remember to\s+|make sure (?:to|you)\s+""" +
            """|you (?:need|have|will need) to\s+|you should\s+|you must\s+|(?:i )?need you to\s+|i want you to\s+""" +
            """|let'?s\s+|let us\s+)""",
        RegexOption.IGNORE_CASE,
    )
    private val rxFillerStart = Regex("""^\s*(?:(?:also|and|so|then|plus|please|kindly|ok|okay|just)[,\s]+)+""", RegexOption.IGNORE_CASE)
    private val rxDangling = Regex("""(?:[\s,]+(?:by|on|at|before|until|till|due|for|from|in|the|this|next|coming|and|or|around|latest|at the latest|of))+[\s,.;:!-]*$""", RegexOption.IGNORE_CASE)

    fun parse(
        input: String,
        now: LocalDateTime,
        defaultHour: Int = 9,
        defaultMinute: Int = 0,
        dayFirst: Boolean = true,
    ): ParsedTask {
        var t = input.trim().replace(Regex("""\s+"""), " ")

        val tags = rxTag.findAll(t).map { it.groupValues[1].lowercase() }.distinct().toList()
        t = rxTag.replace(t, " ")

        var recurrence = Recurrence.NONE
        rxEveryWeekdayName.find(t)?.let { m ->
            recurrence = Recurrence.WEEKLY
            t = t.replaceRange(m.range, "on ${m.groupValues[1]}")
        }
        if (recurrence == Recurrence.NONE) {
            listOf(rxWeekdays to Recurrence.WEEKDAYS, rxDaily to Recurrence.DAILY, rxWeekly to Recurrence.WEEKLY, rxMonthly to Recurrence.MONTHLY)
                .firstOrNull { it.first.containsMatchIn(t) }
                ?.let { (rx, r) ->
                    recurrence = r
                    t = rx.replace(t, " ")
                }
        }

        val priorityFromWords = when {
            rxLow.containsMatchIn(t) -> Priority.LOW
            rxUrgent.containsMatchIn(t) -> Priority.URGENT
            rxHigh.containsMatchIn(t) || t.contains("!!") -> Priority.HIGH
            else -> null
        }
        t = rxStripPriority.replace(t, " ").replace("!", " ")

        val assignedBy = rxAssignedName.find(t)?.groupValues?.get(1)
            ?: rxAssignedRole.find(t)?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }

        val dt = DateTimeParser.parse(t, now, defaultHour, defaultMinute, dayFirst)
        t = removeSpans(t, dt.spans)

        val title = cleanTitle(t).ifBlank { cleanTitle(input).ifBlank { input.trim() } }
        val category = detectCategory(input)

        var priority = priorityFromWords ?: Priority.MEDIUM
        val due = dt.dateTime
        if (priorityFromWords == null && due != null &&
            isSoon(now, due, dt.hasTime, days = 1) &&
            (category == "Work" || category == "Finance")
        ) {
            priority = Priority.HIGH
        }

        return ParsedTask(
            title = title,
            dueAt = due,
            hasTime = dt.hasTime,
            priority = priority,
            category = category,
            tags = tags,
            recurrence = recurrence,
            assignedBy = assignedBy,
        )
    }

    /** Due within [days] calendar days (date-only) or within that many 24h windows (timed), and not in the past. */
    fun isSoon(now: LocalDateTime, due: LocalDateTime, hasTime: Boolean, days: Long): Boolean =
        if (hasTime) ChronoUnit.MINUTES.between(now, due) in 0..(days * 24 * 60 + 12 * 60)
        else ChronoUnit.DAYS.between(now.toLocalDate(), due.toLocalDate()) in 0..days

    fun cleanTitle(raw: String): String {
        var t = raw.replace(Regex("""\s+"""), " ").trim()
        // Strip a leading name used as an address ("Abhijit, please ...")
        val voc = rxVocative.find(t)
        if (voc != null && voc.range.first == 0) {
            t = t.substring(voc.range.last + 1)
        }
        repeat(3) {
            val before = t
            t = rxLead.replaceFirst(t, "")
            t = rxFillerStart.replaceFirst(t, "")
            if (t == before) return@repeat
        }
        t = t.replace(Regex("""\s+([,.;:!?])"""), "$1")
        repeat(3) { t = rxDangling.replace(t, "") }
        t = t.trim().trim(',', '.', ';', ':', '-', '!', '?', ' ')
        t = t.replace(Regex("""\s{2,}"""), " ")
        return t.replaceFirstChar { it.uppercaseChar() }
    }

    fun detectCategory(text: String): String? {
        val words = Regex("""[\p{L}\d'-]+""").findAll(text.lowercase()).map { it.value }.toList()
        val scores = CATEGORY_KEYWORDS.mapValues { (_, keys) -> words.count { it in keys } }
        val best = scores.maxOf { it.value }
        if (best == 0) return null
        return CATEGORY_TIEBREAK.first { (scores[it] ?: 0) == best }
    }

    fun removeSpans(text: String, spans: List<IntRange>): String {
        if (spans.isEmpty()) return text
        val sb = StringBuilder(text)
        spans.sortedByDescending { it.first }.forEach { r ->
            if (r.first >= 0 && r.last < sb.length) sb.replace(r.first, r.last + 1, " ")
        }
        return sb.toString().replace(Regex("""\s+"""), " ").trim()
    }
}

object TranscriptExtractor {

    private val VERBS = setOf(
        "complete", "finish", "send", "share", "call", "email", "mail", "prepare", "submit", "review", "update",
        "check", "fix", "book", "schedule", "arrange", "confirm", "pay", "buy", "bring", "deliver", "upload",
        "create", "write", "draft", "follow", "remind", "set", "organize", "organise", "plan", "collect", "pick",
        "drop", "meet", "ask", "tell", "inform", "contact", "reply", "respond", "forward", "sign", "approve",
        "test", "deploy", "install", "configure", "order", "cancel", "renew", "file", "print", "scan", "visit",
        "attend", "join", "start", "read", "finalize", "finalise", "ping", "text", "message", "notify", "get",
        "make", "do", "look", "talk", "discuss", "raise", "close", "resolve", "migrate", "setup", "share",
    )

    private val NAME_STOPWORDS = setOf(
        "I", "I'll", "I'm", "The", "Please", "Also", "And", "But", "So", "Then", "Okay", "Ok", "OK", "Sure", "Yes",
        "No", "Hi", "Hello", "Hey", "Thanks", "Thank", "Can", "Could", "Will", "Would", "Should", "Let", "Let's",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Today", "Tomorrow",
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
        "November", "December", "We", "You", "They", "He", "She", "It", "This", "That", "My", "Our", "Your",
        "Just", "Right", "Good", "Great", "Fine", "Sorry", "Maybe", "Don't", "Do", "Is", "Are", "Am",
        "AM", "PM", "EOD", "ASAP", "Speaker", "Caller", "Me",
    ) + VERBS.map { v -> v.replaceFirstChar { it.uppercase() } }

    private val rxSpeaker = Regex("""^\s*([A-Za-z][A-Za-z .'-]{0,30}):\s+(.+)$""")
    private val rxSentence = Regex("""(?<=[.!?;])\s+|\n+""")
    private val rxClauseSplit = Regex("""(?:,\s*|\s+)(?:and\s+)?(?:also|and then|plus)\s+(?=\S)""", RegexOption.IGNORE_CASE)
    private val rxAndVerb: Regex = Regex(
        """\s+and\s+(?=(?:please\s+)?(?:""" + VERBS.joinToString("|") + """)\b)""",
        RegexOption.IGNORE_CASE,
    )

    private val rxCommit = Regex("""\b(i will|i'll|i shall|i'm going to|i am going to|i promise|i can do|i will be|we will|we'll|let me|will do|i commit)\b""", RegexOption.IGNORE_CASE)
    private val rxCommitPrefix = Regex("""^.*?\b(?:i will|i'll|i shall|i'm going to|i am going to|i promise(?: to| that i'll| that i will)?|i can do|i will be|we will|we'll|let me|i commit to)\s+(?:definitely\s+|surely\s+|also\s+)?""", RegexOption.IGNORE_CASE)
    private val rxRequest = Regex("""\b(please|kindly|can you|could you|would you|will you|you need to|you have to|you should|you must|make sure|don't forget|do not forget|remember to|need you to|want you to|i need you|ensure)\b""", RegexOption.IGNORE_CASE)
    private val rxFollow = Regex("""\b(follow up|follow-up|followup|get back to|circle back|check back|call (?:you |me |him |her |them )?back|revert)\b""", RegexOption.IGNORE_CASE)
    private val rxMeeting = Regex("""\b(meeting|meet|catch up|catch-up|sync|demo|appointment|interview|conference|call on|discussion|session)\b""", RegexOption.IGNORE_CASE)
    private val rxDeadline = Regex("""\b(deadline|due|by end of|eod|latest by|no later than)\b""", RegexOption.IGNORE_CASE)
    private val rxEmailAction = Regex("""\b(e-?mail|mail)\b""", RegexOption.IGNORE_CASE)
    private val rxRemind = Regex("""\b(remind|reminder)\b""", RegexOption.IGNORE_CASE)
    private val rxINeed = Regex("""^(?:[A-Z][a-z]+,\s*)?(?:and\s+|also\s+)?(?:i|we)\s+(?:need|want|would like|'d like)\s+(?!to\b)(?!you\b)(?!some\s+time)""", RegexOption.IGNORE_CASE)
    private val rxPromise = Regex("""\bpromise""", RegexOption.IGNORE_CASE)
    private val rxDeliverable = Regex("""\b(document|doc|report|presentation|deck|proposal|file|spreadsheet|sheet|invoice|quote|quotation|design|build|release|patch|estimate|summary|minutes|draft)\b""", RegexOption.IGNORE_CASE)
    private val rxPhone = Regex("""(?<![\d+])(\+?\d[\d -]{8,15}\d)(?!\d)""")
    private val rxEmailAddr = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val rxName = Regex("""\b([A-Z][a-z]{2,})(?:\s+([A-Z][a-z]{2,}))?\b""")

    fun extract(
        transcript: String,
        now: LocalDateTime,
        contactName: String? = null,
        defaultHour: Int = 9,
        defaultMinute: Int = 0,
        dayFirst: Boolean = true,
        userName: String? = null,
    ): ExtractionResult {
        val items = mutableListOf<ExtractedItem>()
        val seenTitles = mutableSetOf<String>()
        val names = linkedSetOf<String>()
        val phones = linkedSetOf<String>()
        val emails = linkedSetOf<String>()
        val defaultCategory = TaskTextParser.detectCategory(transcript) ?: "Work"
        val other = contactName?.takeIf { it.isNotBlank() }

        for (rawLine in transcript.lines()) {
            if (rawLine.isBlank()) continue
            var speaker: String? = null
            var line = rawLine
            val sm = rxSpeaker.find(rawLine)
            if (sm != null && sm.groupValues[1].trim().split(" ").size <= 3) {
                speaker = sm.groupValues[1].trim()
                line = sm.groupValues[2]
            }
            val spk = speaker
            if (spk != null && spk.lowercase() !in setOf("me", "i", "you", "speaker", "speaker 1", "speaker 2", "caller")) {
                names.add(spk)
            }

            val speakerIsMe = speaker?.lowercase() in setOf("me", "i", "myself") ||
                (userName != null && speaker?.equals(userName, ignoreCase = true) == true)
            // (clause, inheritsCommitment): "I'll update the scripts and email you" → both are commitments.
            val clauses = mutableListOf<Pair<String, Boolean>>()
            line.split(rxSentence).flatMap { it.split(rxClauseSplit) }.forEach { part ->
                val pieces = part.split(rxAndVerb).map { it.trim() }.filter { it.length > 2 }
                val firstIsCommit = pieces.firstOrNull()?.let { rxCommit.containsMatchIn(it.lowercase()) } == true
                pieces.forEachIndexed { i, piece -> clauses += piece to (i > 0 && firstIsCommit) }
            }

            for ((clause, inherited) in clauses) {
                rxPhone.findAll(clause).forEach { phones += it.groupValues[1].replace(Regex("""[\s-]"""), "") }
                rxEmailAddr.findAll(clause).forEach { emails += it.value }
                collectNames(clause, names)

                val lc = clause.lowercase()
                val dt = DateTimeParser.parse(clause, now, defaultHour, defaultMinute, dayFirst)
                val isCommit = inherited || rxCommit.containsMatchIn(lc)
                val stripped = TaskTextParser.cleanTitle(clause)
                val firstWord = stripped.substringBefore(' ').lowercase().trim(',', '.')
                val needMatch = rxINeed.find(clause)
                val isRequest = !isCommit && (rxRequest.containsMatchIn(lc) || firstWord in VERBS || needMatch != null)
                // Someone else promising something to the user = something to follow up on, not a user task.
                val othersPromise = isCommit && speaker != null && !speakerIsMe
                // The user asking someone else = not the user's task.
                if (isRequest && speakerIsMe) continue
                val isFollow = rxFollow.containsMatchIn(lc)
                val isMeeting = rxMeeting.containsMatchIn(lc) && dt.dateTime != null
                val isDeadline = rxDeadline.containsMatchIn(lc) && dt.dateTime != null
                val isEmail = rxEmailAction.containsMatchIn(lc)

                val type: ActionType = when {
                    othersPromise -> ActionType.FOLLOW_UP
                    isEmail && (isRequest || isCommit) -> ActionType.EMAIL
                    isFollow && (isRequest || isCommit) -> ActionType.FOLLOW_UP
                    isCommit -> if (rxPromise.containsMatchIn(lc)) ActionType.PROMISE else ActionType.COMMITMENT
                    isMeeting -> ActionType.MEETING
                    isRequest && rxRemind.containsMatchIn(lc) -> ActionType.REMINDER
                    isRequest -> ActionType.TASK
                    isDeadline -> ActionType.DEADLINE
                    else -> null
                } ?: continue

                val source = when {
                    isCommit -> rxCommitPrefix.replaceFirst(clause, "")
                    needMatch != null -> clause.replaceRange(needMatch.range, "Provide ")
                    else -> clause
                }
                val parsed = TaskTextParser.parse(source, now, defaultHour, defaultMinute, dayFirst)
                var title = if (othersPromise) {
                    val who = speaker ?: other ?: "them"
                    "Follow up: $who to " + parsed.title.replaceFirstChar { it.lowercaseChar() }
                        .replace(Regex("""\b(?i:you)\b"""), "me").replace(Regex("""\b(?i:your)\b"""), "my")
                } else {
                    personalize(parsed.title, other ?: speaker.takeUnless { speakerIsMe }, isCommit)
                }
                if (type == ActionType.MEETING && !title.lowercase().contains("meet")) title = "Meeting: $title"
                if (title.length < 3) continue
                val key = title.lowercase()
                if (!seenTitles.add(key)) continue

                var priority = parsed.priority
                val due = parsed.dueAt ?: dt.dateTime
                val dueHasTime = if (parsed.dueAt != null) parsed.hasTime else dt.hasTime
                if (priority == Priority.MEDIUM && due != null && (isRequest || isCommit) && !othersPromise &&
                    TaskTextParser.isSoon(now, due, dueHasTime, days = 3)
                ) priority = Priority.HIGH

                val details = buildList {
                    add("\"${clause.trim().take(240)}\"")
                    if (rxDeliverable.containsMatchIn(lc)) add("Deliverable")
                    if (isCommit && !othersPromise && other != null) add("You promised this to $other")
                }.joinToString(" · ")

                items += ExtractedItem(
                    type = type,
                    title = title,
                    detail = details,
                    dueAt = due,
                    hasTime = dueHasTime,
                    priority = priority,
                    category = parsed.category ?: defaultCategory,
                    assignedBy = if (isRequest || othersPromise) (speaker ?: other) else null,
                )
            }
        }

        emails.forEach { addr ->
            if (items.none { it.type == ActionType.EMAIL && it.detail.contains(addr) }) {
                items += ExtractedItem(ActionType.EMAIL, "Email follow-up: $addr", "Email address mentioned in the conversation", category = defaultCategory)
            }
        }
        phones.forEach { p -> items += ExtractedItem(ActionType.PHONE, p, "Phone number mentioned in the conversation") }
        names.take(8).forEach { n -> items += ExtractedItem(ActionType.NAME, n, "Mentioned in the conversation") }

        return ExtractionResult(summary = summarize(items, other), items = items)
    }

    /** From the user's point of view: "send me the file" (said by Rahul) → "Send Rahul the file". */
    private fun personalize(title: String, other: String?, isCommit: Boolean): String {
        val who = other ?: "them"
        var t = " $title "
        t = if (isCommit) {
            t.replace(Regex("""\b(?i:you)\b"""), who).replace(Regex("""\b(?i:your)\b"""), "$who's")
        } else {
            t.replace(Regex("""\b(?i:me)\b"""), who).replace(Regex("""\b(?i:my)\b"""), "$who's")
        }
        return t.trim().replaceFirstChar { it.uppercaseChar() }
    }

    private fun collectNames(clause: String, names: MutableSet<String>) {
        rxName.findAll(clause).forEach { m ->
            val first = m.groupValues[1]
            if (first in NAME_STOPWORDS) return@forEach
            val isSentenceStart = clause.substring(0, m.range.first).isBlank()
            val followedByComma = clause.getOrNull(m.range.last + 1) == ','
            if (isSentenceStart && !followedByComma) return@forEach
            val second = m.groupValues[2].takeIf { it.isNotEmpty() && it !in NAME_STOPWORDS }
            names += if (second != null) "$first $second" else first
        }
    }

    fun summarize(items: List<ExtractedItem>, contactName: String?): String {
        val actionable = items.filter { it.type.actionable && !it.title.startsWith("Email follow-up:") }
        val tasks = actionable.count { it.type in setOf(ActionType.TASK, ActionType.DELIVERABLE, ActionType.REMINDER, ActionType.EMAIL) }
        val commits = actionable.count { it.type == ActionType.COMMITMENT || it.type == ActionType.PROMISE }
        val follows = actionable.count { it.type == ActionType.FOLLOW_UP }
        val meetings = actionable.count { it.type == ActionType.MEETING }
        val who = contactName?.let { "with $it" } ?: ""
        if (actionable.isEmpty()) {
            return "No clear tasks or commitments were detected in this conversation $who. Review the transcript and add anything that was missed.".replace("  ", " ")
        }
        val counts = buildList {
            if (tasks > 0) add("$tasks task${if (tasks > 1) "s" else ""} for you")
            if (commits > 0) add("$commits commitment${if (commits > 1) "s" else ""} you made")
            if (follows > 0) add("$follows follow-up${if (follows > 1) "s" else ""}")
            if (meetings > 0) add("$meetings meeting${if (meetings > 1) "s" else ""}")
        }.joinToString(", ")
        val key = actionable.take(3).joinToString("; ") { it.title }
        return "Conversation $who: $counts. Key items: $key.".replace("  ", " ").replace("Conversation :", "Conversation:")
    }
}
