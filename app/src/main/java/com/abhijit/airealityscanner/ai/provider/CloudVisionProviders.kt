package com.abhijit.airealityscanner.ai.provider

import com.abhijit.airealityscanner.ai.AiException
import com.abhijit.airealityscanner.ai.model.VisionMessage
import com.abhijit.airealityscanner.ai.model.VisionRequest
import com.abhijit.airealityscanner.ai.model.VisionRole
import com.abhijit.airealityscanner.ai.remote.AnthropicApi
import com.abhijit.airealityscanner.ai.remote.AnthropicContent
import com.abhijit.airealityscanner.ai.remote.AnthropicImageSource
import com.abhijit.airealityscanner.ai.remote.AnthropicMessage
import com.abhijit.airealityscanner.ai.remote.AnthropicRequest
import com.abhijit.airealityscanner.ai.remote.OpenAiApi
import com.abhijit.airealityscanner.ai.remote.OpenAiMessage
import com.abhijit.airealityscanner.ai.remote.OpenAiRequest
import com.abhijit.airealityscanner.domain.model.AiProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Abstraction over cloud vision models so providers can be swapped without touching the app. */
interface CloudVisionProvider {
    val type: AiProviderType
    suspend fun complete(request: VisionRequest, apiKey: String, model: String, baseUrl: String): String
}

/** Normalises a conversation for APIs that require strictly alternating user/assistant turns. */
object ConversationBuilder {
    fun normalize(messages: List<VisionMessage>): List<VisionMessage> {
        val out = mutableListOf<VisionMessage>()
        messages
            .filter { it.text.isNotBlank() || it.imagesBase64Jpeg.isNotEmpty() }
            .forEach { msg ->
                val last = out.lastOrNull()
                if (last != null && last.role == msg.role) {
                    out[out.lastIndex] = last.copy(
                        text = listOf(last.text, msg.text).filter { it.isNotBlank() }.joinToString("\n\n"),
                        imagesBase64Jpeg = last.imagesBase64Jpeg + msg.imagesBase64Jpeg,
                    )
                } else {
                    out += msg
                }
            }
        if (out.firstOrNull()?.role == VisionRole.ASSISTANT) out.add(0, VisionMessage(VisionRole.USER, "Continue."))
        if (out.isEmpty() || out.last().role == VisionRole.ASSISTANT) out += VisionMessage(VisionRole.USER, "Continue.")
        return out
    }
}

internal object HttpErrors {
    fun map(e: Throwable, provider: String, json: Json): Throwable = when (e) {
        is CancellationException -> e
        is AiException -> e
        is HttpException -> {
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
            val detail = runCatching {
                val root = json.parseToJsonElement(body).jsonObject
                val err = root["error"]
                when (err) {
                    is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
                    is JsonPrimitive -> err.contentOrNull
                    else -> null
                }
            }.getOrNull() ?: body.take(200)
            AiException.Http(e.code(), provider, detail)
        }
        is IOException -> AiException.Network(e.message)
        is SerializationException -> AiException.InvalidResponse("Unexpected response from $provider.")
        else -> AiException.InvalidResponse(e.message ?: "Unknown error from $provider.")
    }
}

@Singleton
class AnthropicVisionProvider @Inject constructor(
    private val api: AnthropicApi,
    private val json: Json,
) : CloudVisionProvider {
    override val type = AiProviderType.ANTHROPIC

    override suspend fun complete(request: VisionRequest, apiKey: String, model: String, baseUrl: String): String {
        val messages = ConversationBuilder.normalize(request.messages).map { m ->
            AnthropicMessage(
                role = if (m.role == VisionRole.USER) "user" else "assistant",
                content = buildList {
                    m.imagesBase64Jpeg.forEach {
                        add(AnthropicContent(type = "image", source = AnthropicImageSource(data = it)))
                    }
                    if (m.text.isNotBlank()) add(AnthropicContent(type = "text", text = m.text))
                },
            )
        }
        val body = AnthropicRequest(model = model, maxTokens = request.maxTokens, system = request.systemPrompt, messages = messages)
        val response = try {
            api.createMessage(baseUrl.trimEnd('/') + "/v1/messages", apiKey, API_VERSION, body)
        } catch (e: Throwable) {
            throw HttpErrors.map(e, "Anthropic", json)
        }
        val text = response.content.filter { it.type == "text" }.mapNotNull { it.text }.joinToString("\n").trim()
        if (text.isEmpty()) throw AiException.InvalidResponse("Claude returned an empty response (${response.stopReason ?: "no reason"}).")
        return text
    }

    companion object {
        const val API_VERSION = "2023-06-01"
        const val BASE_URL = "https://api.anthropic.com/"
    }
}

@Singleton
class OpenAiVisionProvider @Inject constructor(
    private val api: OpenAiApi,
    private val json: Json,
) : CloudVisionProvider {
    override val type = AiProviderType.OPENAI

    override suspend fun complete(request: VisionRequest, apiKey: String, model: String, baseUrl: String): String {
        val messages = buildList {
            add(OpenAiMessage("system", JsonPrimitive(request.systemPrompt)))
            ConversationBuilder.normalize(request.messages).forEach { m ->
                val role = if (m.role == VisionRole.USER) "user" else "assistant"
                val content = if (m.imagesBase64Jpeg.isEmpty()) {
                    JsonPrimitive(m.text)
                } else {
                    buildJsonArray {
                        m.imagesBase64Jpeg.forEach { b64 ->
                            addJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:image/jpeg;base64,$b64")
                                    put("detail", "auto")
                                }
                            }
                        }
                        if (m.text.isNotBlank()) addJsonObject {
                            put("type", "text")
                            put("text", m.text)
                        }
                    }
                }
                add(OpenAiMessage(role, content))
            }
        }
        val url = baseUrl.trimEnd('/').let { if (it.endsWith("/v1")) "$it/chat/completions" else "$it/v1/chat/completions" }
        val response = try {
            api.chatCompletion(url, "Bearer $apiKey", OpenAiRequest(model, messages, request.maxTokens))
        } catch (e: Throwable) {
            throw HttpErrors.map(e, "OpenAI", json)
        }
        val message = response.choices.firstOrNull()?.message
        message?.refusal?.takeIf { it.isNotBlank() }?.let { throw AiException.InvalidResponse("The model declined: $it") }
        val text = message?.content?.trim().orEmpty()
        if (text.isEmpty()) throw AiException.InvalidResponse("OpenAI returned an empty response.")
        return text
    }
}
