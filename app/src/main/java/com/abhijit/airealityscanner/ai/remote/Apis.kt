package com.abhijit.airealityscanner.ai.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Url

// ---------------- Anthropic Messages API ----------------

interface AnthropicApi {
    @POST
    suspend fun createMessage(
        @Url url: String,
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String,
        @Body body: AnthropicRequest,
    ): AnthropicResponse
}

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<AnthropicMessage>,
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: List<AnthropicContent>,
)

@Serializable
data class AnthropicContent(
    val type: String,
    val text: String? = null,
    val source: AnthropicImageSource? = null,
)

@Serializable
data class AnthropicImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String = "image/jpeg",
    val data: String,
)

@Serializable
data class AnthropicResponse(
    val content: List<AnthropicResponseBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class AnthropicResponseBlock(
    val type: String,
    val text: String? = null,
)

// ---------------- OpenAI Chat Completions API ----------------

interface OpenAiApi {
    @POST
    suspend fun chatCompletion(
        @Url url: String,
        @Header("Authorization") authorization: String,
        @Body body: OpenAiRequest,
    ): OpenAiResponse
}

@Serializable
data class OpenAiRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int,
)

@Serializable
data class OpenAiMessage(
    val role: String,
    val content: JsonElement,
)

@Serializable
data class OpenAiResponse(
    val choices: List<OpenAiChoice> = emptyList(),
)

@Serializable
data class OpenAiChoice(
    val message: OpenAiResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class OpenAiResponseMessage(
    val content: String? = null,
    val refusal: String? = null,
)
