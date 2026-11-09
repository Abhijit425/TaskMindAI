package com.abhijit.airealityscanner.ai

import android.graphics.Bitmap
import android.util.LruCache
import com.abhijit.airealityscanner.ai.model.VisionMessage
import com.abhijit.airealityscanner.ai.model.VisionRequest
import com.abhijit.airealityscanner.ai.model.VisionRole
import com.abhijit.airealityscanner.ai.ondevice.OnDeviceAnalyzer
import com.abhijit.airealityscanner.ai.parse.AnalysisParser
import com.abhijit.airealityscanner.ai.prompt.PromptFactory
import com.abhijit.airealityscanner.ai.provider.AnthropicVisionProvider
import com.abhijit.airealityscanner.ai.provider.OpenAiVisionProvider
import com.abhijit.airealityscanner.core.network.NetworkMonitor
import com.abhijit.airealityscanner.core.util.ImageUtils
import com.abhijit.airealityscanner.domain.model.AiProviderType
import com.abhijit.airealityscanner.domain.model.AppSettings
import com.abhijit.airealityscanner.domain.model.ChatMessage
import com.abhijit.airealityscanner.domain.model.ChatRole
import com.abhijit.airealityscanner.domain.model.ScanAnalysis
import com.abhijit.airealityscanner.domain.model.ScanMode
import com.abhijit.airealityscanner.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single entry point for all AI work. Chooses the configured provider, enforces consent,
 * API-key and connectivity checks, resizes images before upload and caches repeat requests.
 */
@Singleton
class AiEngine @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val anthropic: AnthropicVisionProvider,
    private val openAi: OpenAiVisionProvider,
    private val onDevice: OnDeviceAnalyzer,
    private val networkMonitor: NetworkMonitor,
) {
    private val cache = LruCache<String, String>(40)

    private val _uploadsInFlight = MutableStateFlow(0)
    /** Number of requests currently uploading to a cloud provider (drives visible indicators). */
    val uploadsInFlight: StateFlow<Int> = _uploadsInFlight.asStateFlow()

    suspend fun settings(): AppSettings = settingsRepository.settings.first()

    suspend fun analyze(mode: ScanMode, bitmaps: List<Bitmap>, ocrHint: String?): ScanAnalysis {
        require(bitmaps.isNotEmpty()) { "No image to analyse" }
        val s = settings()
        if (!s.provider.isCloud) return onDevice.analyze(mode, bitmaps.first(), s.language)
        val images = encode(bitmaps, s.maxImageDimension, s.jpegQuality)
        val key = cacheKey("analyze", mode.id, s.language.code, s.activeModelLabel, *images.toTypedArray())
        val raw = cache.get(key) ?: callCloud(
            s,
            VisionRequest(
                systemPrompt = PromptFactory.analysisSystemPrompt(mode, s.language),
                messages = listOf(VisionMessage(VisionRole.USER, PromptFactory.analysisUserPrompt(mode, ocrHint), images)),
                maxTokens = 2500,
            ),
        ).also { cache.put(key, it) }
        return AnalysisParser.parse(raw, s.activeModelLabel)
    }

    suspend fun followUp(
        mode: ScanMode,
        bitmaps: List<Bitmap>,
        analysis: ScanAnalysis?,
        history: List<ChatMessage>,
        question: String,
    ): String {
        val s = settings()
        if (!s.provider.isCloud) {
            throw AiException.Unsupported(
                "Follow-up questions need a cloud AI provider (Claude or OpenAI). The on-device mode can only label images, read text and scan codes. Choose a provider in Settings.",
            )
        }
        val images = encode(bitmaps, s.maxImageDimension, s.jpegQuality)
        val messages = buildList {
            add(VisionMessage(VisionRole.USER, PromptFactory.conversationContext(mode, images.isNotEmpty()), images))
            if (analysis != null) add(VisionMessage(VisionRole.ASSISTANT, PromptFactory.analysisForContext(analysis)))
            history.filter { it.role != ChatRole.SYSTEM }.takeLast(30).forEach { m ->
                add(VisionMessage(if (m.role == ChatRole.USER) VisionRole.USER else VisionRole.ASSISTANT, m.text))
            }
            add(VisionMessage(VisionRole.USER, question))
        }
        return callCloud(
            s,
            VisionRequest(PromptFactory.followUpSystemPrompt(mode, s.language), messages, 1500),
            requiresImageConsent = images.isNotEmpty(),
        )
    }

    suspend fun compare(a: Bitmap, b: Bitmap, labelA: String, labelB: String): ScanAnalysis {
        val s = settings()
        if (!s.provider.isCloud) throw AiException.Unsupported("Comparing objects needs a cloud AI provider. Choose Claude or OpenAI in Settings.")
        val images = encode(listOf(a, b), s.maxImageDimension.coerceAtMost(1024), s.jpegQuality)
        val raw = callCloud(
            s,
            VisionRequest(
                PromptFactory.compareSystemPrompt(s.language),
                listOf(VisionMessage(VisionRole.USER, PromptFactory.compareUserPrompt(labelA, labelB), images)),
                2500,
            ),
        )
        return AnalysisParser.parse(raw, s.activeModelLabel)
    }

    /** Short scene narration for live mode. Uses a smaller image to save bandwidth and battery. */
    suspend fun describeLive(bitmap: Bitmap): String {
        val s = settings()
        val images = encode(listOf(bitmap), s.maxImageDimension.coerceAtMost(768), s.jpegQuality.coerceAtMost(75))
        return callCloud(
            s,
            VisionRequest(
                PromptFactory.liveSystemPrompt(s.language),
                listOf(VisionMessage(VisionRole.USER, PromptFactory.LIVE_USER_PROMPT, images)),
                220,
            ),
        )
    }

    suspend fun facts(topic: String): List<String> {
        val s = settings()
        if (!s.provider.isCloud) throw AiException.Unsupported("AI-generated facts need a cloud AI provider. Choose Claude or OpenAI in Settings.")
        val raw = callCloud(
            s,
            VisionRequest(
                PromptFactory.factsSystemPrompt(s.language),
                listOf(VisionMessage(VisionRole.USER, PromptFactory.factsPrompt(topic, s.language))),
                900,
            ),
            requiresImageConsent = false,
        )
        return AnalysisParser.parseBulletLines(raw)
    }

    /** Sends a tiny text-only request to confirm the key, model and network work. */
    suspend fun testConnection(): String {
        val s = settings()
        if (!s.provider.isCloud) return "On-device mode needs no connection."
        val reply = callCloud(
            s,
            VisionRequest("You are a connection test.", listOf(VisionMessage(VisionRole.USER, "Reply with the single word OK."))
                , 20),
            requiresImageConsent = false,
        )
        return "Connected to ${s.activeModelLabel}. Reply: ${reply.take(40)}"
    }

    private suspend fun encode(bitmaps: List<Bitmap>, maxDim: Int, quality: Int): List<String> =
        withContext(Dispatchers.Default) {
            bitmaps.map { ImageUtils.toJpegBase64(ImageUtils.scaleDown(it, maxDim), quality) }
        }

    private suspend fun callCloud(s: AppSettings, request: VisionRequest, requiresImageConsent: Boolean = true): String {
        if (requiresImageConsent && !s.cloudConsentGiven) throw AiException.ConsentRequired()
        if (!networkMonitor.isOnline()) throw AiException.Offline()
        _uploadsInFlight.value += 1
        try {
            return withContext(Dispatchers.IO) {
                when (s.provider) {
                    AiProviderType.ANTHROPIC -> {
                        val key = settingsRepository.getApiKey(AiProviderType.ANTHROPIC)
                            ?: throw AiException.MissingApiKey("Anthropic Claude")
                        anthropic.complete(request, key, s.anthropicModel, AnthropicVisionProvider.BASE_URL)
                    }
                    AiProviderType.OPENAI -> {
                        val key = settingsRepository.getApiKey(AiProviderType.OPENAI)
                            ?: throw AiException.MissingApiKey("OpenAI")
                        openAi.complete(request, key, s.openAiModel, s.openAiBaseUrl)
                    }
                    AiProviderType.ON_DEVICE -> throw AiException.Unsupported("This feature needs a cloud AI provider.")
                }
            }
        } finally {
            _uploadsInFlight.value -= 1
        }
    }

    private fun cacheKey(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it.toByteArray()); digest.update(0.toByte()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
