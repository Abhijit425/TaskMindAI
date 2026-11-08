package com.taskmind.ai.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.taskmind.ai.data.db.CaptureMode
import com.taskmind.ai.security.KeystoreCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "taskmind_settings")

enum class ThemeMode(val label: String) { SYSTEM("System default"), LIGHT("Light"), DARK("Dark") }

enum class AiProvider(val label: String, val description: String) {
    LOCAL("On-device", "Private rule-based engine. Nothing leaves your phone."),
    OPENAI("OpenAI", "GPT models via your own API key. Also enables cloud transcription of recordings."),
    ANTHROPIC("Anthropic Claude", "Claude models via your own API key."),
}

data class SpeechLanguage(val tag: String, val label: String)

val SPEECH_LANGUAGES = listOf(
    SpeechLanguage("en-IN", "English (India)"),
    SpeechLanguage("en-US", "English (US)"),
    SpeechLanguage("en-GB", "English (UK)"),
    SpeechLanguage("hi-IN", "Hindi"),
    SpeechLanguage("bn-IN", "Bengali"),
)

data class AppSettings(
    val onboardingDone: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,

    val aiProvider: AiProvider = AiProvider.LOCAL,
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    val transcriptionModel: String = DEFAULT_TRANSCRIPTION_MODEL,
    val hasOpenAiKey: Boolean = false,
    val hasAnthropicKey: Boolean = false,
    /** Separate, explicit consents for each kind of data that may be sent to the cloud provider. */
    val cloudTasksConsent: Boolean = false,
    val cloudTranscriptsConsent: Boolean = false,
    val cloudAudioConsent: Boolean = false,

    val defaultReminderHour: Int = 9,
    val defaultReminderMinute: Int = 0,
    val reminderLeadMinutes: Int = 15,
    val dayFirstDates: Boolean = true,

    val callPromptsEnabled: Boolean = false,
    val callConsentAcknowledged: Boolean = false,
    val captureMode: CaptureMode = CaptureMode.LIVE_TRANSCRIPTION,
    val keepAudioAfterTranscription: Boolean = true,
    val lookupContactNames: Boolean = false,

    val deadlineReminders: Boolean = true,
    val overdueAlerts: Boolean = true,
    val morningBriefing: Boolean = true,
    val morningHour: Int = 8,
    val morningMinute: Int = 0,
    val eveningSummary: Boolean = true,
    val eveningHour: Int = 20,
    val eveningMinute: Int = 0,
    val followUpReminders: Boolean = true,
    val postCallReview: Boolean = true,

    val speechLanguage: String = "en-IN",
    val speakAssistantReplies: Boolean = false,
) {
    val activeCloudReady: Boolean
        get() = when (aiProvider) {
            AiProvider.LOCAL -> false
            AiProvider.OPENAI -> hasOpenAiKey
            AiProvider.ANTHROPIC -> hasAnthropicKey
        }

    companion object {
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-5"
        const val DEFAULT_TRANSCRIPTION_MODEL = "whisper-1"
    }
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crypto: KeystoreCrypto,
) {
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val theme = stringPreferencesKey("theme_mode")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val provider = stringPreferencesKey("ai_provider")
        val openAiModel = stringPreferencesKey("openai_model")
        val anthropicModel = stringPreferencesKey("anthropic_model")
        val transcriptionModel = stringPreferencesKey("transcription_model")
        val openAiKey = stringPreferencesKey("openai_key_enc")
        val anthropicKey = stringPreferencesKey("anthropic_key_enc")
        val consentTasks = booleanPreferencesKey("consent_cloud_tasks")
        val consentTranscripts = booleanPreferencesKey("consent_cloud_transcripts")
        val consentAudio = booleanPreferencesKey("consent_cloud_audio")
        val remHour = intPreferencesKey("reminder_hour")
        val remMinute = intPreferencesKey("reminder_minute")
        val lead = intPreferencesKey("reminder_lead")
        val dayFirst = booleanPreferencesKey("day_first")
        val callPrompts = booleanPreferencesKey("call_prompts")
        val callConsent = booleanPreferencesKey("call_consent_ack")
        val captureMode = stringPreferencesKey("capture_mode")
        val keepAudio = booleanPreferencesKey("keep_audio")
        val lookupContacts = booleanPreferencesKey("lookup_contacts")
        val deadline = booleanPreferencesKey("n_deadline")
        val overdue = booleanPreferencesKey("n_overdue")
        val morning = booleanPreferencesKey("n_morning")
        val morningH = intPreferencesKey("n_morning_h")
        val morningM = intPreferencesKey("n_morning_m")
        val evening = booleanPreferencesKey("n_evening")
        val eveningH = intPreferencesKey("n_evening_h")
        val eveningM = intPreferencesKey("n_evening_m")
        val followUp = booleanPreferencesKey("n_followup")
        val postCall = booleanPreferencesKey("n_postcall")
        val speechLang = stringPreferencesKey("speech_lang")
        val tts = booleanPreferencesKey("tts")
        val notifiedOverdue = stringPreferencesKey("notified_overdue_ids")
        val briefingText = stringPreferencesKey("briefing_text")
        val briefingDay = stringPreferencesKey("briefing_day")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            onboardingDone = p[K.onboarding] ?: false,
            themeMode = p[K.theme]?.let { v -> ThemeMode.entries.firstOrNull { it.name == v } } ?: d.themeMode,
            dynamicColor = p[K.dynamic] ?: d.dynamicColor,
            aiProvider = p[K.provider]?.let { v -> AiProvider.entries.firstOrNull { it.name == v } } ?: d.aiProvider,
            openAiModel = p[K.openAiModel]?.takeIf { it.isNotBlank() } ?: d.openAiModel,
            anthropicModel = p[K.anthropicModel]?.takeIf { it.isNotBlank() } ?: d.anthropicModel,
            transcriptionModel = p[K.transcriptionModel]?.takeIf { it.isNotBlank() } ?: d.transcriptionModel,
            hasOpenAiKey = !p[K.openAiKey].isNullOrBlank(),
            hasAnthropicKey = !p[K.anthropicKey].isNullOrBlank(),
            cloudTasksConsent = p[K.consentTasks] ?: false,
            cloudTranscriptsConsent = p[K.consentTranscripts] ?: false,
            cloudAudioConsent = p[K.consentAudio] ?: false,
            defaultReminderHour = p[K.remHour] ?: d.defaultReminderHour,
            defaultReminderMinute = p[K.remMinute] ?: d.defaultReminderMinute,
            reminderLeadMinutes = p[K.lead] ?: d.reminderLeadMinutes,
            dayFirstDates = p[K.dayFirst] ?: d.dayFirstDates,
            callPromptsEnabled = p[K.callPrompts] ?: false,
            callConsentAcknowledged = p[K.callConsent] ?: false,
            captureMode = p[K.captureMode]?.let { v -> CaptureMode.entries.firstOrNull { it.name == v } } ?: d.captureMode,
            keepAudioAfterTranscription = p[K.keepAudio] ?: d.keepAudioAfterTranscription,
            lookupContactNames = p[K.lookupContacts] ?: false,
            deadlineReminders = p[K.deadline] ?: true,
            overdueAlerts = p[K.overdue] ?: true,
            morningBriefing = p[K.morning] ?: true,
            morningHour = p[K.morningH] ?: d.morningHour,
            morningMinute = p[K.morningM] ?: d.morningMinute,
            eveningSummary = p[K.evening] ?: true,
            eveningHour = p[K.eveningH] ?: d.eveningHour,
            eveningMinute = p[K.eveningM] ?: d.eveningMinute,
            followUpReminders = p[K.followUp] ?: true,
            postCallReview = p[K.postCall] ?: true,
            speechLanguage = p[K.speechLang] ?: d.speechLanguage,
            speakAssistantReplies = p[K.tts] ?: false,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setOnboardingDone() = edit { it[K.onboarding] = true }
    suspend fun setTheme(mode: ThemeMode) = edit { it[K.theme] = mode.name }
    suspend fun setDynamicColor(v: Boolean) = edit { it[K.dynamic] = v }
    suspend fun setProvider(p: AiProvider) = edit { it[K.provider] = p.name }
    suspend fun setOpenAiModel(v: String) = edit { it[K.openAiModel] = v.trim() }
    suspend fun setAnthropicModel(v: String) = edit { it[K.anthropicModel] = v.trim() }
    suspend fun setTranscriptionModel(v: String) = edit { it[K.transcriptionModel] = v.trim() }
    suspend fun setCloudTasksConsent(v: Boolean) = edit { it[K.consentTasks] = v }
    suspend fun setCloudTranscriptsConsent(v: Boolean) = edit { it[K.consentTranscripts] = v }
    suspend fun setCloudAudioConsent(v: Boolean) = edit { it[K.consentAudio] = v }
    suspend fun setDefaultReminderTime(h: Int, m: Int) = edit { it[K.remHour] = h; it[K.remMinute] = m }
    suspend fun setReminderLead(min: Int) = edit { it[K.lead] = min }
    suspend fun setDayFirst(v: Boolean) = edit { it[K.dayFirst] = v }
    suspend fun setCallPrompts(v: Boolean) = edit { it[K.callPrompts] = v }
    suspend fun setCallConsentAcknowledged(v: Boolean) = edit { it[K.callConsent] = v }
    suspend fun setCaptureMode(m: CaptureMode) = edit { it[K.captureMode] = m.name }
    suspend fun setKeepAudio(v: Boolean) = edit { it[K.keepAudio] = v }
    suspend fun setLookupContacts(v: Boolean) = edit { it[K.lookupContacts] = v }
    suspend fun setDeadlineReminders(v: Boolean) = edit { it[K.deadline] = v }
    suspend fun setOverdueAlerts(v: Boolean) = edit { it[K.overdue] = v }
    suspend fun setMorningBriefing(v: Boolean) = edit { it[K.morning] = v }
    suspend fun setMorningTime(h: Int, m: Int) = edit { it[K.morningH] = h; it[K.morningM] = m }
    suspend fun setEveningSummary(v: Boolean) = edit { it[K.evening] = v }
    suspend fun setEveningTime(h: Int, m: Int) = edit { it[K.eveningH] = h; it[K.eveningM] = m }
    suspend fun setFollowUpReminders(v: Boolean) = edit { it[K.followUp] = v }
    suspend fun setPostCallReview(v: Boolean) = edit { it[K.postCall] = v }
    suspend fun setSpeechLanguage(tag: String) = edit { it[K.speechLang] = tag }
    suspend fun setSpeakReplies(v: Boolean) = edit { it[K.tts] = v }

    // ---- Encrypted credentials ----

    suspend fun saveApiKey(provider: AiProvider, key: String) {
        val enc = crypto.encryptToBase64(key.trim())
        edit { prefs ->
            if (provider == AiProvider.OPENAI) prefs[K.openAiKey] = enc
            if (provider == AiProvider.ANTHROPIC) prefs[K.anthropicKey] = enc
        }
    }

    suspend fun clearApiKey(provider: AiProvider) {
        edit { prefs ->
            if (provider == AiProvider.OPENAI) prefs.remove(K.openAiKey)
            if (provider == AiProvider.ANTHROPIC) prefs.remove(K.anthropicKey)
        }
    }

    suspend fun apiKey(provider: AiProvider): String? {
        val prefs = context.dataStore.data.first()
        val enc = when (provider) {
            AiProvider.OPENAI -> prefs[K.openAiKey]
            AiProvider.ANTHROPIC -> prefs[K.anthropicKey]
            AiProvider.LOCAL -> null
        } ?: return null
        return crypto.decryptFromBase64(enc)?.takeIf { it.isNotBlank() }
    }

    // ---- Small bits of app state ----

    suspend fun notifiedOverdueIds(): Set<Long> =
        context.dataStore.data.first()[K.notifiedOverdue]
            ?.split(',')?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    suspend fun setNotifiedOverdueIds(ids: Set<Long>) = edit { it[K.notifiedOverdue] = ids.joinToString(",") }

    suspend fun cachedBriefing(day: String): String? {
        val p = context.dataStore.data.first()
        return if (p[K.briefingDay] == day) p[K.briefingText] else null
    }

    suspend fun cacheBriefing(day: String, text: String) = edit { it[K.briefingDay] = day; it[K.briefingText] = text }

    suspend fun clearPrivateState() = edit {
        it.remove(K.notifiedOverdue)
        it.remove(K.briefingText)
        it.remove(K.briefingDay)
    }
}
