package com.taskmind.ai.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

interface OpenAiApi {
    @POST("v1/chat/completions")
    suspend fun chat(@Header("Authorization") auth: String, @Body body: JsonObject): JsonObject

    @Multipart
    @POST("v1/audio/transcriptions")
    suspend fun transcribe(
        @Header("Authorization") auth: String,
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody?,
    ): JsonObject
}

interface AnthropicApi {
    @POST("v1/messages")
    suspend fun messages(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String,
        @Body body: JsonObject,
    ): JsonObject
}

/** A chat-style large language model reached with the user's own API key. */
interface LlmClient {
    val displayName: String
    suspend fun complete(
        apiKey: String,
        model: String,
        system: String,
        messages: List<ChatMessage>,
        maxTokens: Int = 1500,
        jsonMode: Boolean = false,
    ): String
}

private suspend fun <T> mapErrors(provider: String, block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull().orEmpty()
    val hint = when (e.code()) {
        401, 403 -> "The API key was rejected. Check it in Settings → AI provider."
        404 -> "The model name was not found. Check the model in Settings → AI provider."
        429 -> "Rate limit or quota reached on your $provider account."
        else -> "HTTP ${e.code()}"
    }
    throw AiException("$provider: $hint ${body.take(200)}".trim(), e)
} catch (e: IOException) {
    throw AiException("$provider: network error (${e.message ?: "no connection"})", e)
}

@Singleton
class OpenAiClient @Inject constructor(private val api: OpenAiApi) : LlmClient {
    override val displayName = "OpenAI"

    override suspend fun complete(
        apiKey: String,
        model: String,
        system: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        jsonMode: Boolean,
    ): String = mapErrors(displayName) {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", system) }
                messages.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
            }
        }
        val res = api.chat("Bearer $apiKey", body)
        res["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiException("OpenAI returned an empty response")
    }

    suspend fun transcribe(apiKey: String, model: String, file: File, mime: String, languageTag: String?): String =
        mapErrors(displayName) {
            val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody(mime.toMediaType()))
            val text = "text/plain".toMediaType()
            val lang = languageTag?.substringBefore('-')?.lowercase()?.takeIf { it.length == 2 }
            val res = api.transcribe(
                auth = "Bearer $apiKey",
                file = part,
                model = model.toRequestBody(text),
                language = lang?.toRequestBody(text),
            )
            (res["text"] as? JsonPrimitive)?.contentOrNull ?: throw AiException("OpenAI returned no transcript")
        }
}

@Singleton
class AnthropicClient @Inject constructor(private val api: AnthropicApi) : LlmClient {
    override val displayName = "Claude"

    override suspend fun complete(
        apiKey: String,
        model: String,
        system: String,
        messages: List<ChatMessage>,
        maxTokens: Int,
        jsonMode: Boolean,
    ): String = mapErrors(displayName) {
        // Claude requires the conversation to start with a user turn and alternate roles.
        val cleaned = mutableListOf<ChatMessage>()
        messages.forEach { m ->
            if (cleaned.isEmpty() && m.role != ChatMessage.USER) return@forEach
            if (cleaned.isNotEmpty() && cleaned.last().role == m.role) {
                val merged = m.copy(content = cleaned.last().content + "\n\n" + m.content)
                cleaned.set(cleaned.lastIndex, merged)
            } else {
                cleaned.add(m)
            }
        }
        val sys = if (jsonMode) "$system\n\nRespond with a single valid JSON object only, no prose, no code fences." else system
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("system", sys)
            putJsonArray("messages") {
                cleaned.forEach { m -> addJsonObject { put("role", m.role); put("content", m.content) } }
            }
        }
        val res = api.messages(apiKey, "2023-06-01", body)
        val content = res["content"] as? JsonArray
        content?.mapNotNull { block ->
            val o = block as? JsonObject ?: return@mapNotNull null
            if ((o["type"] as? JsonPrimitive)?.contentOrNull == "text") (o["text"] as? JsonPrimitive)?.contentOrNull else null
        }?.joinToString("")?.takeIf { it.isNotBlank() }
            ?: throw AiException("Claude returned an empty response")
    }
}

object JsonText {
    /** Pull the first JSON object out of a model reply (tolerates code fences or stray prose). */
    fun extractObject(text: String): JsonObject {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) throw AiException("AI reply did not contain JSON")
        return runCatching {
            kotlinx.serialization.json.Json { isLenient = true; ignoreUnknownKeys = true }
                .parseToJsonElement(text.substring(start, end + 1)).jsonObject
        }.getOrElse { throw AiException("AI reply was not valid JSON", it) }
    }

    fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    fun JsonObject.array(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
}
