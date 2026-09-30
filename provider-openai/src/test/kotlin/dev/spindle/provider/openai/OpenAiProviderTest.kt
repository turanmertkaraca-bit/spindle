package dev.spindle.provider.openai

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.provider.WireImage
import dev.spindle.core.provider.WireMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiProviderTest {

    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun provider(defaults: List<ModelInfo> = emptyList()) =
        OpenAiProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            defaultModels = defaults,
        )

    private fun request(sessionHint: String? = null) = ChatRequest(
        model = "gpt-4o",
        system = "You are helpful.",
        messages = listOf(
            WireMessage(role = "user", text = "hi"),
            WireMessage(
                role = "assistant",
                text = null,
                toolCalls = listOf(ToolCall("call_prev", "lookup", "{\"a\":1}")),
            ),
            WireMessage(role = "tool", text = "{\"ok\":true}", toolCallId = "call_prev", toolName = "lookup"),
        ),
        tools = listOf(ToolSpec("get_weather", "weather", "{\"type\":\"object\",\"properties\":{}}")),
        temperature = 0.2,
        maxTokens = 100,
        reasoningEffort = "medium",
        thinking = true,
        sessionHint = sessionHint,
    )

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.use { it.readBytes().decodeToString() }

    @Test
    fun `stream reassembles split sse frames into exact event sequence`() = runTest {
        server.enqueue(MockResponse().setChunkedBody(fixture("openai_stream.sse"), 7))

        val events = provider().stream(request()).toList()

        assertEquals(
            listOf(
                ProviderEvent.TextDelta("Hello"),
                ProviderEvent.ReasoningDelta("think"),
                ProviderEvent.TextDelta(", world"),
                ProviderEvent.ToolCallStart(0, "call_1", "get_weather"),
                ProviderEvent.ToolCallArgsDelta(0, "{\"ci"),
                ProviderEvent.ToolCallArgsDelta(0, "ty\":\"SF\"}"),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
                ProviderEvent.UsageEvent(
                    Usage(inputTokens = 10, outputTokens = 5, reasoningTokens = 3, cacheReadTokens = 2),
                ),
            ),
            events,
        )

        val args = events.filterIsInstance<ProviderEvent.ToolCallArgsDelta>()
            .joinToString("") { it.argsDelta }
        assertEquals("{\"city\":\"SF\"}", args)
    }

    @Test
    fun `stream sends auth session and streaming body then maps 500 to failure`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(500).setBody("{\"error\":\"boom\"}"),
        )

        val events = provider().stream(request(sessionHint = "sess-42")).toList()

        val failure = events.single() as ProviderEvent.Failure
        assertTrue(failure.message.contains("500"), failure.message)
        assertTrue(failure.message.contains("boom"), failure.message)

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertEquals("sess-42", recorded.getHeader("x-opencode-session"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("\"stream\":true"), body)
        assertTrue(body.contains("\"include_usage\":true"), body)
        assertTrue(body.contains("\"tool_choice\":\"auto\""), body)
        assertTrue(body.contains("\"type\":\"enabled\""), body)
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""), body)
        assertTrue(body.contains("\"tool_call_id\":\"call_prev\""), body)
    }

    @Test
    fun `models maps the listing and defaults to fallback on failure`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[{\"id\":\"gpt-4o\"},{\"id\":\"deepseek-r1\"},{\"id\":\"deepseek-v4\"}]}",
            ),
        )
        val models = provider().models()
        assertEquals(listOf("gpt-4o", "deepseek-r1", "deepseek-v4"), models.map { it.id })
        assertTrue(models.first { it.id == "deepseek-r1" }.supportsReasoning)
        assertTrue(models.first { it.id == "deepseek-v4" }.supportsReasoning)
        assertTrue(!models.first { it.id == "gpt-4o" }.supportsReasoning)

        server.enqueue(MockResponse().setResponseCode(500))
        val fallback = listOf(ModelInfo(providerId = "openai", id = "fallback"))
        assertEquals(fallback, provider(defaults = fallback).models())
    }

    @Test
    fun `sends image content array for user messages with images`() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))

        val req = request().copy(
            messages = listOf(
                WireMessage(
                    role = "user",
                    text = "what is this?",
                    images = listOf(WireImage("image/png", "QUJD")),
                ),
            ),
        )
        provider().stream(req).toList()

        val messages = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray
        val content = messages.first { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }
            .jsonObject["content"]!!.jsonArray

        val text = content[0].jsonObject
        assertEquals("text", text["type"]!!.jsonPrimitive.content)
        assertEquals("what is this?", text["text"]!!.jsonPrimitive.content)

        val image = content[1].jsonObject
        assertEquals("image_url", image["type"]!!.jsonPrimitive.content)
        assertEquals(
            "data:image/png;base64,QUJD",
            image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `keeps plain string content when there are no images`() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))

        provider().stream(request()).toList()

        val messages = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray
        val user = messages.first { it.jsonObject["role"]!!.jsonPrimitive.content == "user" }.jsonObject

        assertTrue(user["content"] is JsonPrimitive, "no-image content must stay a plain string")
        assertEquals("hi", user["content"]!!.jsonPrimitive.content)
    }
}
