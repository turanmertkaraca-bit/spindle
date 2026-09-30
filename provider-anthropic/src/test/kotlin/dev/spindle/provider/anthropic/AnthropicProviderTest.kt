package dev.spindle.provider.anthropic

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.provider.WireImage
import dev.spindle.core.provider.WireMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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

class AnthropicProviderTest {

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

    private fun provider() = AnthropicProvider(
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKey = "test-key",
    )

    private fun request() = ChatRequest(
        model = "claude-sonnet-4-20250514",
        system = "You are helpful.",
        messages = listOf(WireMessage(role = "user", text = "hi")),
        tools = listOf(ToolSpec("search", "search the web", "{\"type\":\"object\"}")),
        temperature = 0.1,
        maxTokens = 256,
    )

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")!!.use { it.readBytes().decodeToString() }

    @Test
    fun `stream reassembles split anthropic sse frames into exact event sequence`() = runTest {
        server.enqueue(MockResponse().setChunkedBody(fixture("anthropic_stream.sse"), 9))

        val events = provider().stream(request()).toList()

        assertEquals(
            listOf(
                ProviderEvent.UsageEvent(Usage(inputTokens = 12, cacheReadTokens = 4)),
                ProviderEvent.TextDelta("Hi "),
                ProviderEvent.TextDelta("there"),
                ProviderEvent.ToolCallStart(1, "toolu_1", "search"),
                ProviderEvent.ToolCallArgsDelta(1, "{\"q\":"),
                ProviderEvent.ToolCallArgsDelta(1, "\"cats\"}"),
                ProviderEvent.UsageEvent(Usage(outputTokens = 7)),
                ProviderEvent.Finished(FinishReason.TOOL_CALLS),
            ),
            events,
        )

        val args = events.filterIsInstance<ProviderEvent.ToolCallArgsDelta>()
            .joinToString("") { it.argsDelta }
        assertEquals("{\"q\":\"cats\"}", args)

        val recorded = server.takeRequest()
        assertEquals("test-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertTrue(body.containsKey("tools"), "tools missing")
        assertEquals(256, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        assertTrue(body["stream"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `stream maps 500 to failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"nope\"}"))

        val failure = provider().stream(request()).toList().single() as ProviderEvent.Failure
        assertTrue(failure.message.contains("500"), failure.message)
        assertTrue(failure.message.contains("nope"), failure.message)
    }

    @Test
    fun `buildMessages merges same-role turns and puts tool_result first`() = runTest {
        server.enqueue(MockResponse().setChunkedBody("data: {\"type\":\"message_stop\"}\n\n", 5))

        val req = request().copy(
            messages = listOf(
                WireMessage(
                    role = "assistant",
                    text = null,
                    toolCalls = listOf(ToolCall("toolu_prev", "lookup", "{\"q\":1}")),
                ),
                WireMessage(role = "tool", text = "result-1", toolCallId = "toolu_prev"),
                WireMessage(role = "tool", text = "result-2", toolCallId = "toolu_prev"),
                WireMessage(role = "user", text = "continue"),
            ),
        )
        provider().stream(req).toList()

        val recorded = server.takeRequest()
        val messages = Json.parseToJsonElement(recorded.body.readUtf8())
            .jsonObject["messages"]!!.jsonArray
        assertEquals(2, messages.size)

        val assistant = messages[0].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val toolUse = assistant["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", toolUse["type"]!!.jsonPrimitive.content)
        assertEquals("toolu_prev", toolUse["id"]!!.jsonPrimitive.content)

        val user = messages[1].jsonObject
        assertEquals("user", user["role"]!!.jsonPrimitive.content)
        val blocks = user["content"]!!.jsonArray
        assertEquals(3, blocks.size)
        assertEquals("tool_result", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tool_result", blocks[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("text", blocks[2].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("continue", blocks[2].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sends base64 image block for user messages with images`() = runTest {
        server.enqueue(MockResponse().setChunkedBody("data: {\"type\":\"message_stop\"}\n\n", 5))

        val req = request().copy(
            messages = listOf(
                WireMessage(
                    role = "user",
                    text = "what is this?",
                    images = listOf(WireImage("image/jpeg", "QUJD")),
                ),
            ),
        )
        provider().stream(req).toList()

        val blocks = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("what is this?", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)

        val image = blocks[1].jsonObject
        assertEquals("image", image["type"]!!.jsonPrimitive.content)
        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", source["media_type"]!!.jsonPrimitive.content)
        assertEquals("QUJD", source["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `keeps single text block when there are no images`() = runTest {
        server.enqueue(MockResponse().setChunkedBody("data: {\"type\":\"message_stop\"}\n\n", 5))

        provider().stream(request()).toList()

        val blocks = Json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray[0].jsonObject["content"]!!.jsonArray

        assertEquals(1, blocks.size)
        assertEquals("text", blocks[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hi", blocks[0].jsonObject["text"]!!.jsonPrimitive.content)
    }
}
