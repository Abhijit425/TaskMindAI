package com.taskmind.ai.ai

import com.taskmind.ai.data.db.ActionStatus
import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.AppDatabase
import com.taskmind.ai.data.db.CallSessionWithDetails
import com.taskmind.ai.data.db.ContactReferenceEntity
import com.taskmind.ai.data.db.ExtractedActionEntity
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.TaskEntity
import com.taskmind.ai.data.db.TaskSource
import com.taskmind.ai.data.db.UserSettingsEntity
import com.taskmind.ai.data.db.isOverdue
import com.taskmind.ai.data.db.pendingCount
import com.taskmind.ai.data.db.transcriptText
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.data.repo.ProfileRepository
import com.taskmind.ai.data.repo.TaskRepository
import com.taskmind.ai.util.TimeUtil
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Answers questions about the user's real tasks and authorised conversation data.
 * Uses the configured cloud model when allowed; otherwise a deterministic on-device engine.
 */
@Singleton
class AssistantEngine @Inject constructor(
    private val db: AppDatabase,
    private val ai: AiEngine,
    private val settings: SettingsRepository,
    private val profile: ProfileRepository,
    private val tasks: TaskRepository,
) {
    data class Reply(val text: String, val engine: String, val createdTaskId: Long? = null, val note: String? = null)

    data class Snapshot(
        val nowMs: Long,
        val pending: List<TaskEntity>,
        val overdue: List<TaskEntity>,
        val dueToday: List<TaskEntity>,
        val dueTomorrow: List<TaskEntity>,
        val dueThisWeek: List<TaskEntity>,
        val completedToday: List<TaskEntity>,
        val completedWeek: List<TaskEntity>,
        val sessions: List<CallSessionWithDetails>,
        val categoryNames: Map<Long, String>,
        val contacts: List<ContactReferenceEntity>,
        val profile: UserSettingsEntity,
    )

    suspend fun snapshot(): Snapshot {
        val now = System.currentTimeMillis()
        val today = TimeUtil.today()
        val endOfWeek = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        val pending = db.taskDao().getPendingTopLevel()
        val overdue = pending.filter { it.isOverdue(now) }.sortedBy { it.dueAt }
        fun dueOn(d: LocalDate) = pending.filter { t -> t.dueAt?.let { TimeUtil.toDate(it) == d } == true && !t.isOverdue(now) }
        return Snapshot(
            nowMs = now,
            pending = pending,
            overdue = overdue,
            dueToday = dueOn(today).sortedBy { it.dueAt },
            dueTomorrow = dueOn(today.plusDays(1)).sortedBy { it.dueAt },
            dueThisWeek = pending.filter { t ->
                t.dueAt?.let { val d = TimeUtil.toDate(it); !d.isBefore(today) && !d.isAfter(endOfWeek) } == true
            }.sortedBy { it.dueAt },
            completedToday = db.taskDao().getCompletedBetween(TimeUtil.startOfDay(today), TimeUtil.endOfDay(today)),
            completedWeek = db.taskDao().getCompletedBetween(TimeUtil.startOfDay(today.minusDays(6)), TimeUtil.endOfDay(today)),
            sessions = db.callDao().getRecent(30),
            categoryNames = db.categoryDao().getAll().associate { it.id to it.name },
            contacts = db.contactDao().getAll(),
            profile = profile.get(),
        )
    }

    // ---------------------------------------------------------------- Briefings

    fun localBriefing(s: Snapshot, evening: Boolean = false): String {
        val parts = mutableListOf<String>()
        if (evening) {
            val left = s.dueToday.size + s.overdue.size
            parts += if (left == 0) "Everything due today is done — nice work." else
                "$left task${plural(left)} still open from today" + (if (s.overdue.isNotEmpty()) " (${s.overdue.size} overdue)." else ".")
            if (s.completedToday.isNotEmpty()) parts += "You completed ${s.completedToday.size} today."
            val first = s.dueTomorrow.firstOrNull()
            if (first != null) {
                val at = if (first.hasDueTime && first.dueAt != null) " at ${TimeUtil.formatTime(first.dueAt)}" else ""
                parts.add("Tomorrow starts with “${first.title}”$at.")
            }
        } else {
            val todayCount = s.dueToday.size
            parts += when {
                todayCount == 0 && s.overdue.isEmpty() -> "Nothing is due today."
                todayCount == 0 -> "Nothing new is due today."
                else -> {
                    val high = s.dueToday.count { it.priority.weight >= Priority.HIGH.weight }
                    "You have $todayCount task${plural(todayCount)} due today" + (if (high > 0) ", $high high priority." else ".")
                }
            }
            if (s.overdue.isNotEmpty()) {
                parts.add("${s.overdue.size} overdue — start with “${s.overdue.first().title}”.")
            } else {
                val top = (s.dueToday + s.pending).maxByOrNull { it.priority.weight }
                if (top != null) parts.add("Top priority: “${top.title}”.")
            }
            s.pending.filter { it.dueAt != null && it.dueAt > s.nowMs && !it.isOverdue(s.nowMs) }
                .minByOrNull { it.dueAt ?: Long.MAX_VALUE }
                ?.takeIf { it !in s.dueToday }
                ?.let { parts += "Next deadline: “${it.title}” ${TimeUtil.formatDue(it.dueAt!!, it.hasDueTime).lowercase()}." }
        }
        val review = s.sessions.sumOf { it.pendingCount }
        if (review > 0) parts += "$review item${plural(review)} from your conversations ${if (review == 1) "is" else "are"} waiting for review."
        return parts.joinToString(" ")
    }

    suspend fun dailyBriefing(forceRefresh: Boolean): Outcome<String> {
        val snap = snapshot()
        val local = localBriefing(snap)
        val day = TimeUtil.today().toString()
        if (!forceRefresh) settings.cachedBriefing(day)?.let { return Outcome(it, "Cached") }
        val s = settings.current()
        if (ai.cloudFor(CloudData.TASK_TEXT, s) == null) return Outcome(local, "On-device")
        return try {
            val context = buildContext(snap, includeConversations = s.cloudTranscriptsConsent)
            val out = ai.chat(
                "You are TaskMind, a calm, sharp personal secretary. Write today's briefing for the user in 3–4 short " +
                    "sentences: what matters most today, what is overdue, upcoming deadlines, and any unreviewed conversation " +
                    "commitments. Be specific, use the task titles, no greetings, no markdown.\n\n$context",
                listOf(ChatMessage(ChatMessage.USER, "Write my briefing for today.")),
                CloudData.TASK_TEXT,
            )
            if (out == null) Outcome(local, "On-device") else {
                settings.cacheBriefing(day, out.value)
                out
            }
        } catch (e: Exception) {
            Outcome(local, "On-device", e.message)
        }
    }

    // ---------------------------------------------------------------- Q&A

    private val rxCreate = Regex(
        """^\s*(?:please\s+|hey\s+|ok\s+)?(remind me|add (?:a |an |new )?(?:task|todo|to-do|reminder)|create (?:a |an )?(?:task|reminder)|new task|set (?:a )?reminder|schedule (?:a )?(?:task|reminder|call|meeting))\b""",
        RegexOption.IGNORE_CASE,
    )

    suspend fun answer(question: String, history: List<ChatMessage>): Reply {
        if (rxCreate.containsMatchIn(question)) {
            val parsed = ai.parseTask(question)
            val id = tasks.createFromParsed(parsed.value, TaskSource.ASSISTANT)
            val p = parsed.value
            val due = p.dueAt?.let { " — due ${TimeUtil.formatDue(TimeUtil.toMillis(it), p.hasTime)}" }.orEmpty()
            return Reply("Added “${p.title}”$due · ${p.priority.label} priority · ${p.category ?: "Personal"}.", parsed.engine, id, parsed.note)
        }

        val snap = snapshot()
        val s = settings.current()
        if (ai.cloudFor(CloudData.TASK_TEXT, s) != null) {
            try {
                val context = buildContext(snap, includeConversations = s.cloudTranscriptsConsent)
                val system = """
                    You are TaskMind, the user's personal secretary. Your purpose: make sure they never forget anything
                    they were asked to do or promised to do. Answer ONLY from the data below. If the answer is not in the
                    data, say so plainly. Be concise and practical; use short bullet lists with task titles and due dates.
                    Never claim you sent a message, made a call or took any external action.
                    ${if (!s.cloudTranscriptsConsent) "Conversation transcripts are NOT shared with you (the user has not allowed it); if asked about calls, explain that cloud access to transcripts is off in Settings." else ""}

                    $context
                """.trimIndent()
                val msgs = history.takeLast(10) + ChatMessage(ChatMessage.USER, question)
                val out = ai.chat(system, msgs, CloudData.TASK_TEXT)
                if (out != null) return Reply(out.value, out.engine)
            } catch (e: Exception) {
                return localAnswer(question, snap).copy(note = "Cloud AI failed (${e.message?.take(120)}). Answered on-device.")
            }
        }
        return localAnswer(question, snap)
    }

    private fun line(t: TaskEntity, snap: Snapshot): String {
        val due = t.dueAt?.let {
            if (t.isOverdue(snap.nowMs)) TimeUtil.overdueBy(it, snap.nowMs) else "due ${TimeUtil.formatDue(it, t.hasDueTime)}"
        }
        val bits = listOfNotNull(due, t.priority.takeIf { it.weight >= Priority.HIGH.weight }?.label, t.assignedBy?.let { "from $it" })
        return "• ${t.title}" + if (bits.isNotEmpty()) " — ${bits.joinToString(" · ")}" else ""
    }

    private fun list(title: String, items: List<TaskEntity>, snap: Snapshot, empty: String, limit: Int = 12): String {
        if (items.isEmpty()) return empty
        val more = if (items.size > limit) "\n…and ${items.size - limit} more." else ""
        return "$title\n" + items.take(limit).joinToString("\n") { line(it, snap) } + more
    }

    private fun byPriority(items: List<TaskEntity>) =
        items.sortedWith(compareByDescending<TaskEntity> { it.priority.weight }.thenBy { it.dueAt ?: Long.MAX_VALUE })

    private fun actionLine(a: ExtractedActionEntity, sess: CallSessionWithDetails): String {
        val due = a.dueAt?.let { " — due ${TimeUtil.formatDue(it, a.hasDueTime)}" }.orEmpty()
        val status = when (a.status) {
            ActionStatus.PENDING -> " (not reviewed yet)"
            ActionStatus.APPROVED -> " ✓ in your tasks"
            ActionStatus.REJECTED -> " (dismissed)"
        }
        val who = sess.session.contactName ?: sess.contact?.name
        return "• ${a.title}$due${who?.let { " · with $it" }.orEmpty()}$status"
    }

    fun localAnswer(question: String, snap: Snapshot): Reply {
        val q = question.lowercase()
        val engine = "On-device"
        val today = TimeUtil.today()

        fun range(): Pair<LocalDate, LocalDate> = when {
            "yesterday" in q -> today.minusDays(1) to today.minusDays(1)
            "today" in q -> today to today
            "last week" in q -> today.minusDays(13) to today.minusDays(7)
            "this week" in q || "week" in q -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
            "month" in q -> today.minusDays(30) to today
            else -> today.minusDays(6) to today
        }

        // Summaries of captured conversations
        if (Regex("""summar|recap""").containsMatchIn(q) && Regex("""call|conversation|meeting|capture|voice""").containsMatchIn(q)) {
            val n = Regex("""\b(\d{1,2})\b""").find(q)?.groupValues?.get(1)?.toIntOrNull()
                ?: mapOf("two" to 2, "three" to 3, "four" to 4, "five" to 5, "ten" to 10).entries.firstOrNull { it.key in q }?.value
                ?: 5
            val sessions = snap.sessions.take(n)
            if (sessions.isEmpty()) return Reply("You haven't captured any conversations yet. Use Call Insights → New capture, or enable call prompts in Settings.", engine)
            val text = sessions.joinToString("\n\n") { sess ->
                val who = sess.session.contactName ?: sess.contact?.name ?: sess.session.title
                val head = "${who} · ${TimeUtil.formatDue(sess.session.startedAt, true)}"
                val body = sess.session.summary ?: sess.transcriptText.take(160).ifBlank { "No transcript yet (${sess.session.status.label.lowercase()})." }
                "$head\n$body"
            }
            return Reply("Your last ${sessions.size} captured conversation${plural(sessions.size)}:\n\n$text", engine)
        }

        // Promises / commitments made in conversations
        if (Regex("""promis|commit|agreed|i said i would|i told .* i would|what did i say""").containsMatchIn(q)) {
            val (from, to) = range()
            val inRange = snap.sessions.filter { val d = TimeUtil.toDate(it.session.startedAt); !d.isBefore(from) && !d.isAfter(to) }
            val items = inRange.flatMap { sess ->
                sess.actions.filter { it.type == ActionType.COMMITMENT || it.type == ActionType.PROMISE || it.type == ActionType.FOLLOW_UP }
                    .map { actionLine(it, sess) }
            }
            val label = if (from == to) TimeUtil.dayLabel(from).lowercase() else "between ${TimeUtil.dayLabel(from)} and ${TimeUtil.dayLabel(to)}"
            if (inRange.isEmpty()) return Reply("No conversations were captured $label.", engine)
            if (items.isEmpty()) return Reply("I found ${inRange.size} conversation${plural(inRange.size)} $label, but no commitments were detected in them.", engine)
            return Reply("Commitments from conversations $label:\n" + items.joinToString("\n"), engine)
        }

        // Tasks assigned by a specific person (manager, client, a name)
        val people = mutableSetOf<String>()
        if (Regex("""manager|boss|supervisor|lead""").containsMatchIn(q)) {
            snap.profile.managerName.takeIf { it.isNotBlank() }?.let { people += it }
            snap.contacts.filter { it.relation?.contains(Regex("manager|boss|lead|supervisor", RegexOption.IGNORE_CASE)) == true }.forEach { people += it.name }
            people += "Manager"; people += "Boss"
        }
        if (Regex("""client|customer""").containsMatchIn(q)) {
            snap.contacts.filter { it.relation?.contains(Regex("client|customer", RegexOption.IGNORE_CASE)) == true }.forEach { people += it.name }
            people += "Client"
        }
        val knownNames = (snap.contacts.map { it.name } + snap.pending.mapNotNull { it.assignedBy }).distinct()
        knownNames.filter { n -> n.length > 2 && Regex("""\b${Regex.escape(n.lowercase())}\b""").containsMatchIn(q) }.forEach { people += it }
        if (people.isNotEmpty() && Regex("""assign|ask|give|want|from|request""").containsMatchIn(q)) {
            val lower = people.map { it.lowercase() }
            val assigned = snap.pending.filter { t -> t.assignedBy?.lowercase()?.let { a -> lower.any { p -> a.contains(p) || p.contains(a) } } == true }
            val fromCalls = snap.sessions.filter { sess ->
                val who = (sess.session.contactName ?: sess.contact?.name)?.lowercase() ?: return@filter false
                val rel = sess.contact?.relation?.lowercase().orEmpty()
                lower.any { who.contains(it) || it.contains(who) || (rel.isNotEmpty() && rel.contains(it)) }
            }.flatMap { sess -> sess.actions.filter { it.type.actionable && it.status == ActionStatus.PENDING }.map { actionLine(it, sess) } }
            val names = people.filter { it != "Manager" && it != "Boss" && it != "Client" }.ifEmpty { people.toList() }
            val sb = StringBuilder()
            sb.append(list("Open tasks from ${names.joinToString(" / ")}:", byPriority(assigned), snap, "No open tasks are recorded as assigned by ${names.joinToString(" / ")}."))
            if (fromCalls.isNotEmpty()) sb.append("\n\nStill to review from your calls with them:\n").append(fromCalls.joinToString("\n"))
            if (snap.profile.managerName.isBlank() && "manager" in q) sb.append("\n\nTip: set your manager's name in Settings → Profile so I can match their requests.")
            return Reply(sb.toString(), engine)
        }

        if ("overdue" in q || "late" in q || "missed" in q) {
            return Reply(list("Overdue (${snap.overdue.size}):", snap.overdue, snap, "Nothing is overdue. 🎉"), engine)
        }

        if (Regex("""forget|forgetting|missing|slipping|overlook|fall through""").containsMatchIn(q)) {
            val sb = StringBuilder()
            if (snap.overdue.isNotEmpty()) sb.append(list("Overdue:", snap.overdue, snap, "", 6)).append("\n\n")
            val soon = snap.pending.filter { t -> t.dueAt?.let { it > snap.nowMs && it - snap.nowMs < 48 * 3600_000L } == true }
            if (soon.isNotEmpty()) sb.append(list("Due in the next 48 hours:", soon.sortedBy { it.dueAt }, snap, "", 6)).append("\n\n")
            val unreviewed = snap.sessions.flatMap { sess -> sess.actions.filter { it.status == ActionStatus.PENDING && it.type.actionable }.map { actionLine(it, sess) } }
            if (unreviewed.isNotEmpty()) sb.append("From conversations, not yet in your tasks:\n").append(unreviewed.take(8).joinToString("\n")).append("\n\n")
            val noDate = snap.pending.filter { it.dueAt == null && it.priority.weight >= Priority.HIGH.weight }
            if (noDate.isNotEmpty()) sb.append(list("High priority with no deadline:", noDate, snap, "", 5))
            val text = sb.toString().trim()
            return Reply(text.ifEmpty { "Nothing looks forgotten: no overdue tasks, nothing due in 48 hours, and all conversations are reviewed." }, engine)
        }

        if ("tomorrow" in q) {
            return Reply(list("Due tomorrow:", snap.dueTomorrow, snap, "Nothing is due tomorrow."), engine)
        }

        if ("week" in q) {
            val items = (snap.overdue + snap.dueThisWeek).distinctBy { it.id }
            return Reply(list("To finish this week (${items.size}):", items, snap, "Nothing is due for the rest of this week."), engine)
        }

        if (Regex("""priorit|important|urgent|critical|focus""").containsMatchIn(q)) {
            val top = byPriority(snap.pending).take(8)
            return Reply(list("Highest priority right now:", top, snap, "You have no pending tasks."), engine)
        }

        if ("today" in q || Regex("""what should i (do|complete|work)|plan my day|my day""").containsMatchIn(q)) {
            val items = byPriority(snap.overdue + snap.dueToday)
            if (items.isEmpty()) {
                val next = byPriority(snap.pending).firstOrNull()
                return Reply("Nothing is due today." + (next?.let { " If you have time, get ahead on “${it.title}”." } ?: ""), engine)
            }
            return Reply(list("For today (${items.size}), in order:", items, snap, "") + "\n\nStart with “${items.first().title}”.", engine)
        }

        if (Regex("""done|completed|finished|accomplish""").containsMatchIn(q)) {
            val items = if ("week" in q) snap.completedWeek else snap.completedToday
            return Reply(list("Completed ${if ("week" in q) "this week" else "today"} (${items.size}):", items, snap, "Nothing completed ${if ("week" in q) "this week" else "today"} yet."), engine)
        }

        if (Regex("""pending|open|tasks|to-?do|show|list|what do i have""").containsMatchIn(q)) {
            return Reply(list("Pending tasks (${snap.pending.size}):", byPriority(snap.pending), snap, "You have no pending tasks."), engine)
        }

        return Reply(
            "I can answer from your tasks and captured conversations. Try:\n" +
                "• What should I complete today?\n• What tasks are overdue?\n• What did I promise during yesterday's calls?\n" +
                "• What tasks did my manager assign?\n• What do I need to finish this week?\n• Which tasks have the highest priority?\n" +
                "• Summarize my last five captured conversations.\n• What am I forgetting?\n• Remind me to … (creates a task)\n\n" +
                "For open-ended questions, connect OpenAI or Claude in Settings → AI provider.",
            engine,
        )
    }

    /** Text context for cloud models. Transcripts are included only with explicit consent. */
    fun buildContext(snap: Snapshot, includeConversations: Boolean): String {
        val sb = StringBuilder()
        sb.append("NOW: ${TimeUtil.formatFull(snap.nowMs)} (${TimeUtil.zone.id})\n")
        if (snap.profile.displayName.isNotBlank()) sb.append("USER: ${snap.profile.displayName}\n")
        if (snap.profile.managerName.isNotBlank()) sb.append("USER'S MANAGER: ${snap.profile.managerName}\n")
        val people = snap.contacts.filter { !it.relation.isNullOrBlank() }
        if (people.isNotEmpty()) sb.append("KNOWN PEOPLE: ").append(people.joinToString("; ") { "${it.name} (${it.relation})" }).append('\n')
        fun t(task: TaskEntity): String {
            val due = task.dueAt?.let { TimeUtil.formatFull(it).let { f -> if (task.hasDueTime) f else f.substringBefore(" ·") } } ?: "no due date"
            val flags = listOfNotNull(
                if (task.isOverdue(snap.nowMs)) "OVERDUE" else null,
                task.priority.name,
                task.categoryId?.let { snap.categoryNames[it] },
                task.assignedBy?.let { "assigned by $it" },
                if (task.source == TaskSource.CALL) "from conversation #${task.callSessionId}" else null,
                task.recurrence.takeIf { it.name != "NONE" }?.label,
            )
            return "- #${task.id} ${task.title} | $due | ${flags.joinToString(", ")}" + if (task.notes.isNotBlank()) " | notes: ${task.notes.take(120).replace('\n', ' ')}" else ""
        }
        sb.append("\nPENDING TASKS (${snap.pending.size}):\n")
        byPriority(snap.pending).take(120).forEach { sb.append(t(it)).append('\n') }
        sb.append("\nCOMPLETED IN LAST 7 DAYS (${snap.completedWeek.size}):\n")
        snap.completedWeek.take(40).forEach { sb.append("- ${it.title} (done ${it.completedAt?.let { c -> TimeUtil.formatFull(c) }})\n") }
        if (includeConversations) {
            sb.append("\nCAPTURED CONVERSATIONS (most recent first):\n")
            var budget = 14_000
            snap.sessions.take(15).forEach { sess ->
                val who = sess.session.contactName ?: sess.contact?.name ?: "unknown"
                val rel = sess.contact?.relation?.let { " ($it)" }.orEmpty()
                val block = buildString {
                    append("## #${sess.session.id} ${sess.session.direction.label} with $who$rel on ${TimeUtil.formatFull(sess.session.startedAt)}")
                    sess.session.durationSec?.let { append(", ${TimeUtil.formatDuration(it)}") }
                    append('\n')
                    sess.session.summary?.let { append("Summary: $it\n") }
                    sess.actions.filter { it.type.actionable }.forEach { a ->
                        append("- [${a.type.name}] ${a.title}")
                        a.dueAt?.let { append(" (due ${TimeUtil.formatFull(it)})") }
                        append(" status=${a.status.name}\n")
                    }
                    val tx = sess.transcriptText
                    if (tx.isNotBlank()) append("Transcript excerpt: ${tx.take(1200).replace('\n', ' ')}\n")
                }
                if (budget - block.length > 0) {
                    sb.append(block)
                    budget -= block.length
                }
            }
        } else {
            sb.append("\n(Conversation transcripts are not shared with the cloud model.)\n")
        }
        return sb.toString()
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
