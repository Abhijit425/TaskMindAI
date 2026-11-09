package com.abhijit.airealityscanner

import com.abhijit.airealityscanner.ai.AiException
import com.abhijit.airealityscanner.ai.model.VisionMessage
import com.abhijit.airealityscanner.ai.model.VisionRequest
import com.abhijit.airealityscanner.ai.model.VisionRole
import com.abhijit.airealityscanner.ai.provider.AnthropicVisionProvider
import com.abhijit.airealityscanner.ai.provider.OpenAiVisionProvider
import com.abhijit.airealityscanner.ai.remote.AnthropicApi
import com.abhijit.airealityscanner.ai.remote.OpenAiApi
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.create

/** Verifies the real HTTP request format and error handling against a local mock server. */
class CloudProviderTest {

    private lateinit var server: MockWebServer
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val request = VisionRequest(
        systemPrompt = "SYSTEM",
        messages = listOf(VisionMessage(VisionRole.USER, "What is this?", listOf("QUJD"))),
        maxTokens = 100,
    )

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun retrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(server.url("/"))
        .client(OkHttpClient())
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    @Test
    fun anthropicSendsImageAndParsesText() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"id":"msg_1","type":"message","content":[{"type":"text","text":"A bicycle."}],"stop_reason":"end_turn"}""",
            ),
        )
        val provider = AnthropicVisionProvider(retrofit().create<AnthropicApi>(), json)
        val answer = provider.complete(request, "test-key", "claude-test", server.url("/").toString())
        assertEquals("A bicycle.", answer)

        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"system\":\"SYSTEM\""))
        assertTrue(body.contains("\"media_type\":\"image/jpeg\""))
        assertTrue(body.contains("\"data\":\"QUJD\""))
        assertTrue(body.contains("\"max_tokens\":100"))
    }

    @Test
    fun anthropicMapsUnauthorizedToFriendlyError() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setBody(
                """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}""",
            ),
        )
        val provider = AnthropicVisionProvider(retrofit().create<AnthropicApi>(), json)
        try {
            provider.complete(request, "bad", "claude-test", server.url("/").toString())
            fail("Expected an exception")
        } catch (e: AiException.Http) {
            assertEquals(401, e.code)
            assertTrue(e.message!!.contains("invalid x-api-key"))
        }
    }

    @Test
    fun openAiSendsDataUrlImage() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"choices":[{"index":0,"message":{"role":"assistant","content":"A cat."},"finish_reason":"stop"}]}""",
            ),
        )
        val provider = OpenAiVisionProvider(retrofit().create<OpenAiApi>(), json)
        val answer = provider.complete(request, "sk-test", "gpt-test", server.url("/").toString())
        assertEquals("A cat.", answer)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("data:image/jpeg;base64,QUJD"))
        assertTrue(body.contains("\"role\":\"system\""))
    }

    @Test
    fun serverErrorIsMapped() = runTest {
        server.enqueue(MockResponse().setResponseCode(529).setBody("""{"error":{"message":"Overloaded"}}"""))
        val provider = AnthropicVisionProvider(retrofit().create<AnthropicApi>(), json)
        try {
            provider.complete(request, "k", "m", server.url("/").toString())
            fail("Expected an exception")
        } catch (e: AiException.Http) {
            assertEquals(529, e.code)
            assertTrue(e.message!!.contains("temporarily unavailable"))
        }
    }
}
