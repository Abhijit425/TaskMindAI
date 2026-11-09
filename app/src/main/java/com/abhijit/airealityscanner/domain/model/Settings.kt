package com.abhijit.airealityscanner.domain.model

import java.util.Locale

enum class AiProviderType(val displayName: String, val isCloud: Boolean, val description: String) {
    ANTHROPIC(
        "Anthropic Claude", true,
        "Best detail and reasoning. Images are sent to Anthropic's API using your own API key.",
    ),
    OPENAI(
        "OpenAI", true,
        "Vision-capable GPT models (or any OpenAI-compatible endpoint) using your own API key.",
    ),
    ON_DEVICE(
        "On-device (offline)", false,
        "Google ML Kit on your phone. Nothing is uploaded. Recognises broad categories, text and barcodes only.",
    );

    companion object {
        fun parse(value: String?): AiProviderType = entries.firstOrNull { it.name == value } ?: ANTHROPIC
    }
}

enum class AppLanguage(
    val code: String,
    val displayName: String,
    val englishName: String,
    val speechTag: String,
) {
    ENGLISH("en", "English", "English", "en-IN"),
    HINDI("hi", "हिन्दी", "Hindi", "hi-IN"),
    BENGALI("bn", "বাংলা", "Bengali", "bn-IN");

    val locale: Locale get() = Locale.forLanguageTag(speechTag)

    companion object {
        fun parse(value: String?): AppLanguage = entries.firstOrNull { it.name == value } ?: ENGLISH
    }
}

enum class ThemeMode(val label: String) {
    SYSTEM("System"), LIGHT("Light"), DARK("Dark");

    companion object {
        fun parse(value: String?): ThemeMode = entries.firstOrNull { it.name == value } ?: SYSTEM
    }
}

data class AppSettings(
    val provider: AiProviderType = AiProviderType.ANTHROPIC,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val openAiBaseUrl: String = DEFAULT_OPENAI_BASE_URL,
    val hasAnthropicKey: Boolean = false,
    val hasOpenAiKey: Boolean = false,
    val cloudConsentGiven: Boolean = false,
    val voiceEnabled: Boolean = false,
    val language: AppLanguage = AppLanguage.ENGLISH,
    val autoSpeak: Boolean = false,
    val continuousConversation: Boolean = false,
    val speechRate: Float = 1.0f,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val liveCloudEnabled: Boolean = true,
    val liveIntervalSeconds: Int = 6,
    val sceneChangeThreshold: Float = 0.10f,
    val maxImageDimension: Int = 1280,
    val jpegQuality: Int = 82,
    val autoSaveScans: Boolean = true,
    val autoDeleteDays: Int = 0,
    val lastMode: ScanMode = ScanMode.GENERAL,
) {
    val hasKeyForProvider: Boolean
        get() = when (provider) {
            AiProviderType.ANTHROPIC -> hasAnthropicKey
            AiProviderType.OPENAI -> hasOpenAiKey
            AiProviderType.ON_DEVICE -> true
        }

    val activeModelLabel: String
        get() = when (provider) {
            AiProviderType.ANTHROPIC -> "Claude · $anthropicModel"
            AiProviderType.OPENAI -> "OpenAI · $openAiModel"
            AiProviderType.ON_DEVICE -> "On-device ML Kit"
        }

    companion object {
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-5-5"
        const val DEFAULT_OPENAI_MODEL = "gpt-4.1"
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/"
        val ANTHROPIC_MODEL_PRESETS = listOf("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5")
        val OPENAI_MODEL_PRESETS = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4o")
    }
}
