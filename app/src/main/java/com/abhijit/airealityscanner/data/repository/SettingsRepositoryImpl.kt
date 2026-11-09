package com.abhijit.airealityscanner.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.abhijit.airealityscanner.core.security.SecureKeyStore
import com.abhijit.airealityscanner.domain.model.AiProviderType
import com.abhijit.airealityscanner.domain.model.AppLanguage
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.model.ThemeMode
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: SecureKeyStore,
) : SettingsRepository {

    private object Keys {
        val PROVIDER = stringPreferencesKey("provider")
        val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val OPENAI_BASE_URL = stringPreferencesKey("openai_base_url")
        val HAS_ANTHROPIC_KEY = booleanPreferencesKey("has_anthropic_key")
        val HAS_OPENAI_KEY = booleanPreferencesKey("has_openai_key")
        val CLOUD_CONSENT = booleanPreferencesKey("cloud_consent")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val LANGUAGE = stringPreferencesKey("language")
        val AUTO_SPEAK = booleanPreferencesKey("auto_speak")
        val CONTINUOUS = booleanPreferencesKey("continuous_conversation")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val THEME = stringPreferencesKey("theme")
        val LIVE_CLOUD = booleanPreferencesKey("live_cloud")
        val LIVE_INTERVAL = intPreferencesKey("live_interval")
        val SCENE_THRESHOLD = floatPreferencesKey("scene_threshold")
        val MAX_DIM = intPreferencesKey("max_dim")
        val JPEG_QUALITY = intPreferencesKey("jpeg_quality")
        val AUTO_SAVE = booleanPreferencesKey("auto_save")
        val AUTO_DELETE_DAYS = intPreferencesKey("auto_delete_days")
        val LAST_MODE = stringPreferencesKey("last_mode")
    }

    override val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val updated = transform(prefs.toSettings())
            prefs.write(updated)
        }
    }

    override suspend fun setApiKey(provider: AiProviderType, key: String?) {
        withContext(Dispatchers.IO) { keyStore.put(provider.name, key?.trim()) }
        val present = !key.isNullOrBlank()
        context.settingsDataStore.edit { prefs ->
            when (provider) {
                AiProviderType.ANTHROPIC -> prefs[Keys.HAS_ANTHROPIC_KEY] = present
                AiProviderType.OPENAI -> prefs[Keys.HAS_OPENAI_KEY] = present
                AiProviderType.ON_DEVICE -> Unit
            }
        }
    }

    override fun getApiKey(provider: AiProviderType): String? =
        keyStore.get(provider.name)?.takeIf { it.isNotBlank() }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            provider = AiProviderType.parse(this[Keys.PROVIDER]),
            anthropicModel = this[Keys.ANTHROPIC_MODEL] ?: d.anthropicModel,
            openAiModel = this[Keys.OPENAI_MODEL] ?: d.openAiModel,
            openAiBaseUrl = this[Keys.OPENAI_BASE_URL] ?: d.openAiBaseUrl,
            hasAnthropicKey = this[Keys.HAS_ANTHROPIC_KEY] ?: false,
            hasOpenAiKey = this[Keys.HAS_OPENAI_KEY] ?: false,
            cloudConsentGiven = this[Keys.CLOUD_CONSENT] ?: false,
            voiceEnabled = this[Keys.VOICE_ENABLED] ?: false,
            language = AppLanguage.parse(this[Keys.LANGUAGE]),
            autoSpeak = this[Keys.AUTO_SPEAK] ?: d.autoSpeak,
            continuousConversation = this[Keys.CONTINUOUS] ?: d.continuousConversation,
            speechRate = this[Keys.SPEECH_RATE] ?: d.speechRate,
            themeMode = ThemeMode.parse(this[Keys.THEME]),
            liveCloudEnabled = this[Keys.LIVE_CLOUD] ?: d.liveCloudEnabled,
            liveIntervalSeconds = this[Keys.LIVE_INTERVAL] ?: d.liveIntervalSeconds,
            sceneChangeThreshold = this[Keys.SCENE_THRESHOLD] ?: d.sceneChangeThreshold,
            maxImageDimension = this[Keys.MAX_DIM] ?: d.maxImageDimension,
            jpegQuality = this[Keys.JPEG_QUALITY] ?: d.jpegQuality,
            autoSaveScans = this[Keys.AUTO_SAVE] ?: d.autoSaveScans,
            autoDeleteDays = this[Keys.AUTO_DELETE_DAYS] ?: d.autoDeleteDays,
            lastMode = ScanMode.fromId(this[Keys.LAST_MODE]),
        )
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.PROVIDER] = s.provider.name
        this[Keys.ANTHROPIC_MODEL] = s.anthropicModel.trim().ifBlank { AppSettings.DEFAULT_ANTHROPIC_MODEL }
        this[Keys.OPENAI_MODEL] = s.openAiModel.trim().ifBlank { AppSettings.DEFAULT_OPENAI_MODEL }
        this[Keys.OPENAI_BASE_URL] = s.openAiBaseUrl.trim().ifBlank { AppSettings.DEFAULT_OPENAI_BASE_URL }
        this[Keys.CLOUD_CONSENT] = s.cloudConsentGiven
        this[Keys.VOICE_ENABLED] = s.voiceEnabled
        this[Keys.LANGUAGE] = s.language.name
        this[Keys.AUTO_SPEAK] = s.autoSpeak
        this[Keys.CONTINUOUS] = s.continuousConversation
        this[Keys.SPEECH_RATE] = s.speechRate
        this[Keys.THEME] = s.themeMode.name
        this[Keys.LIVE_CLOUD] = s.liveCloudEnabled
        this[Keys.LIVE_INTERVAL] = s.liveIntervalSeconds.coerceIn(2, 60)
        this[Keys.SCENE_THRESHOLD] = s.sceneChangeThreshold.coerceIn(0.02f, 0.5f)
        this[Keys.MAX_DIM] = s.maxImageDimension.coerceIn(512, 2048)
        this[Keys.JPEG_QUALITY] = s.jpegQuality.coerceIn(50, 95)
        this[Keys.AUTO_SAVE] = s.autoSaveScans
        this[Keys.AUTO_DELETE_DAYS] = s.autoDeleteDays.coerceAtLeast(0)
        this[Keys.LAST_MODE] = s.lastMode.id
        // hasAnthropicKey / hasOpenAiKey are only written by setApiKey().
    }
}
