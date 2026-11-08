package com.taskmind.ai.ai

import com.taskmind.ai.ai.JsonText.array
import com.taskmind.ai.ai.JsonText.bool
import com.taskmind.ai.ai.JsonText.str
import com.taskmind.ai.data.db.ActionType
import com.taskmind.ai.data.db.CategoryDao
import com.taskmind.ai.data.db.Priority
import com.taskmind.ai.data.db.Recurrence
import com.taskmind.ai.data.prefs.AiProvider
import com.taskmind.ai.data.prefs.AppSettings
import com.taskmind.ai.data.prefs.SettingsRepository
import com.taskmind.ai.util.TimeUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The kinds of data a cloud provider may receive. Each has its own explicit consent switch. */
enum class CloudData { TASK_TEXT, TRANSCRIPT, AUDIO }

@Singleton
class AiEngine @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val openAi: OpenAiClient,
    private val anthropic: AnthropicClient,
    private val categoryDao: CategoryDao,
) {
    data class CloudHandle(val client: LlmClient, val apiKey: String, val model: String)

    /** Returns a cloud client only if a provider is selected, a key is stored and the user consented for [kind]. */
    suspend fun cloudFor(kind: CloudData, s: AppSettings? = null): CloudHandle? {
        val settings = s ?: settingsRepo.current()
        val consent = when (kind) {
            CloudData.TASK_TEXT -> settings.cloudTasksConsent
            CloudData.TRANSCRIPT -> settings.cloudTranscriptsConsent
            CloudData.AUDIO -> settings.cloudAudioConsent
        }
        if (!consent) return null
        return when (settings.aiProvider) {
            AiProvider.LOCAL -> null
            AiProvider.OPENAI -> settingsRepo.apiKey(AiProvider.OPENAI)?.let { CloudHandle(openAi, it, settings.openAiModel) }
            AiProvider.ANTHROPIC -> settingsRepo.apiKey(AiProvider.ANTHROPIC)?.let { CloudHandle(anthropic, it, settings.anthropicModel) }
        }
    }

    private fun nowLine(now: LocalDateTime): String {
        val dow = now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "Current local date-time: ${now.format(TimeUtil.isoMinute)} ($dow). Time zone: ${TimeUtil.zone.id}."
    }

    private suspend fun categoryNames(): List<String> =
        categoryDao.getAll().map { it.name }.ifEmpty { listOf("Work", "Personal", "Health", "Finance", "Family", "Shopping") }

    // ---------------------------------------------------------------- Task parsing

    suspend fun parseTask(text: String): Outcome<ParsedTask> {
        val s = settingsRepo.current()
        val now = TimeUtil.now()
        val local = TaskTextParser.parse(text, now, s.defaultReminderHour, s.defaultReminderMinute, s.dayFirstDates)
        val cloud = cloudFor(CloudData.TASK_TEXT, s) ?: return Outcome(local, "On-device")
        return try {
            val system = """
                You turn a person's natural-language instruction into exactly one to-do item.
                ${nowLine(now)}
                Reply with only a JSON object:
                {"title": string (short imperative, no date or time words),
                 "notes": string,
                 "due": "YYYY-MM-DDTHH:mm" or null,
                 "hasTime": boolean (false if only a day was given; then use ${"%02d:%02d".format(s.defaultReminderHour, s.defaultReminderMinute)} as the time),
                 "priority": "LOW"|"MEDIUM"|"HIGH"|"URGENT",
                 "category": one of ${categoryNames().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
                 "tags": [string],
                 "recurrence": "NONE"|"DAILY"|"WEEKDAYS"|"WEEKLY"|"MONTHLY",
                 "assignedBy": string or null}
                Resolve relative dates ("tomorrow", "next Wednesday", "by Monday") against the current date.
            """.trimIndent()
            val reply = cloud.client.complete(cloud.apiKey, cloud.model, system, listOf(ChatMessage(ChatMessage.USER, text)), 600, jsonMode = true)
            val o = JsonText.extractObject(reply)
            val parsed = ParsedTask(
                title = o.str("title") ?: local.title,
                notes = o.str("notes").orEmpty(),
                dueAt = parseDateTime(o.str("due"), s) ?: local.dueAt,
                hasTime = if (o.str("due") != null) (o.bool("hasTime") ?: true) else local.hasTime,
                priority = Priority.parse(o.str("priority")) ?: local.priority,
                category = o.str("category") ?: local.category,
                tags = o.array("tags").mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }.ifEmpty { local.tags },
                recurrence = o.str("recurrence")?.let { Recurrence.parse(it) } ?: local.recurrence,
                assignedBy = o.str("assignedBy") ?: local.assignedBy,
            )
            Outcome(parsed, cloud.client.displayName)
        } catch (e: Exception) {
            Outcome(local, "On-device", "Cloud AI unavailable — used on-device parsing. ${e.message.orEmpty()}".trim())
        }
    }

    // ---------------------------------------------------------------- Conversation understanding

    suspend fun extract(transcript: String, contactName: String?, userName: String?): Outcome<ExtractionResult> {
        val s = settingsRepo.current()
        val now = TimeUtil.now()
        val local = TranscriptExtractor.extract(transcript, now, contactName, s.defaultReminderHour, s.defaultReminderMinute, s.dayFirstDates, userName?.takeIf { it.isNotBlank() })
        val cloud = cloudFor(CloudData.TRANSCRIPT, s) ?: return Outcome(local, "On-device")
        return try {
            val types = ActionType.entries.joinToString("|") { it.name }
            val system = """
                You are a meticulous personal secretary. You read a transcript of a conversation that the user
                ${if (!userName.isNullOrBlank()) "($userName) " else ""}took part in${if (!contactName.isNullOrBlank()) " with $contactName" else ""}.
                Find everything the USER must not forget: tasks assigned to the user, commitments and promises the
                user made, deadlines, meeting dates, follow-ups, email follow-ups, deliverables, reminders,
                important people's names and phone numbers that matter.
                ${nowLine(now)}
                Resolve relative dates against the current date. Write titles as short imperative tasks from the
                user's point of view (e.g. "Send updated document to client"). Do not invent anything that was not said.
                Reply with only JSON:
                {"summary": "2-4 sentence summary of the conversation",
                 "actions": [{"type": "$types",
                              "title": string, "detail": string (quote or context),
                              "due": "YYYY-MM-DDTHH:mm" or null, "hasTime": boolean,
                              "priority": "LOW"|"MEDIUM"|"HIGH"|"URGENT",
                              "category": one of ${categoryNames().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }},
                              "assignedBy": name of the person who asked, or null}]}
                Use type NAME for people and PHONE for phone numbers (title = the name or number).
            """.trimIndent()
            val reply = cloud.client.complete(
                cloud.apiKey, cloud.model, system,
                listOf(ChatMessage(ChatMessage.USER, "TRANSCRIPT:\n${transcript.take(60_000)}")),
                2500, jsonMode = true,
            )
            val o = JsonText.extractObject(reply)
            val items = o.array("actions").mapNotNull { el ->
                val a = el as? JsonObject ?: return@mapNotNull null
                val title = a.str("title") ?: return@mapNotNull null
                ExtractedItem(
                    type = ActionType.parse(a.str("type")) ?: ActionType.TASK,
                    title = title,
                    detail = a.str("detail").orEmpty(),
                    dueAt = parseDateTime(a.str("due"), s),
                    hasTime = a.bool("hasTime") ?: false,
                    priority = Priority.parse(a.str("priority")) ?: Priority.MEDIUM,
                    category = a.str("category"),
                    assignedBy = a.str("assignedBy"),
                )
            }
            Outcome(ExtractionResult(o.str("summary") ?: local.summary, items), cloud.client.displayName)
        } catch (e: Exception) {
            Outcome(local, "On-device", "Cloud AI unavailable — used on-device analysis. ${e.message.orEmpty()}".trim())
        }
    }

    // ---------------------------------------------------------------- Transcription (OpenAI only)

    suspend fun canTranscribeAudio(): Boolean {
        val s = settingsRepo.current()
        return s.cloudAudioConsent && settingsRepo.apiKey(AiProvider.OPENAI) != null
    }

    suspend fun transcribe(file: File, mime: String): String {
        val s = settingsRepo.current()
        if (!s.cloudAudioConsent) throw AiException("Audio upload is turned off in Settings → Privacy.")
        val key = settingsRepo.apiKey(AiProvider.OPENAI)
            ?: throw AiException("Add an OpenAI API key to transcribe recordings (Claude does not transcribe audio).")
        if (file.length() > 25L * 1024 * 1024) throw AiException("Recording is larger than 25 MB, the OpenAI upload limit.")
        return openAi.transcribe(key, s.transcriptionModel, file, mime, s.speechLanguage)
    }

    // ---------------------------------------------------------------- Chat

    suspend fun chat(system: String, messages: List<ChatMessage>, kind: CloudData): Outcome<String>? {
        val cloud = cloudFor(kind) ?: return null
        val reply = cloud.client.complete(cloud.apiKey, cloud.model, system, messages, 1200)
        return Outcome(reply.trim(), cloud.client.displayName)
    }

    suspend fun testConnection(provider: AiProvider): String {
        val s = settingsRepo.current()
        val (client, model) = when (provider) {
            AiProvider.OPENAI -> openAi to s.openAiModel
            AiProvider.ANTHROPIC -> anthropic to s.anthropicModel
            AiProvider.LOCAL -> return "On-device engine is always available."
        }
        val key = settingsRepo.apiKey(provider) ?: throw AiException("No API key saved for ${provider.label}.")
        val reply = client.complete(key, model, "Reply with the single word OK.", listOf(ChatMessage(ChatMessage.USER, "ping")), 10)
        return "Connected to ${provider.label} ($model): ${reply.take(40)}"
    }

    private fun parseDateTime(s: String?, settings: AppSettings): LocalDateTime? {
        if (s.isNullOrBlank()) return null
        return runCatching { LocalDateTime.parse(s.take(16), TimeUtil.isoMinute) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(s).atZoneSameInstant(TimeUtil.zone).toLocalDateTime() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(s) }.getOrNull()
            ?: runCatching { LocalDate.parse(s.take(10)).atTime(settings.defaultReminderHour, settings.defaultReminderMinute) }.getOrNull()
    }
}
