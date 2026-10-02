package dev.spindle.provider.openai

import dev.spindle.core.agent.AgentConfig
import dev.spindle.core.agent.Wire
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.provider.WireImage
import dev.spindle.core.provider.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
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

    private fun frame(vararg lines: String): String =
        lines.joinToString("") { "data: $it\n\n" }

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
                ProviderEvent.ToolCallEnd(0),
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
        assertTrue(body.contains("\"content\":null"), body)
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

    @Test
    fun `mid-stream disconnect emits one failure and does not throw`() = runTest {
        val body = buildString {
            repeat(50) {
                append("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n")
            }
        }
        server.enqueue(
            MockResponse()
                .setChunkedBody(body, 16)
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )

        val events = provider().stream(request()).toList()

        val terminals = events.filter { it is ProviderEvent.Finished || it is ProviderEvent.Failure }
        assertEquals(1, terminals.size, "exactly one terminal event")
        assertTrue(terminals.single() is ProviderEvent.Failure)
    }

    @Test
    fun `cancelling the collector aborts a stalled stream promptly`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n")
                .setBodyDelay(3, TimeUnit.SECONDS),
        )

        val job = launch(Dispatchers.IO) {
            provider().stream(request()).collect { }
        }
        delay(250)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)
    }

    @Test
    fun `no finish reason plus done yields exactly one terminal`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                frame("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}") +
                    "data: [DONE]\n\n",
            ),
        )

        val events = provider().stream(request()).toList()

        assertEquals(
            listOf(
                ProviderEvent.TextDelta("hi"),
                ProviderEvent.Finished(FinishReason.UNKNOWN),
            ),
            events,
        )
    }

    @Test
    fun `split and repeated tool metadata yields a single start`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                frame(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"get_weather\"}}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"name\":\"get_weather\"}}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                ) + "data: [DONE]\n\n",
            ),
        )

        val events = provider().stream(request()).toList()

        val starts = events.filterIsInstance<ProviderEvent.ToolCallStart>()
        assertEquals(listOf(ProviderEvent.ToolCallStart(0, "call_1", "get_weather")), starts)
        assertEquals(listOf(0), events.filterIsInstance<ProviderEvent.ToolCallEnd>().map { it.index })
        assertEquals("{}", events.filterIsInstance<ProviderEvent.ToolCallArgsDelta>().joinToString("") { it.argsDelta })
    }

    @Test
    fun `tool call end emitted for every started index before finished`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                frame(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
                        "{\"index\":0,\"id\":\"call_0\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}," +
                        "{\"index\":1,\"id\":\"call_1\",\"function\":{\"name\":\"b\",\"arguments\":\"{}\"}}" +
                        "]}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
                ) + "data: [DONE]\n\n",
            ),
        )

        val events = provider().stream(request()).toList()

        assertEquals(listOf(0, 1), events.filterIsInstance<ProviderEvent.ToolCallEnd>().map { it.index })
        val finished = events.indexOfFirst { it is ProviderEvent.Finished }
        assertTrue(finished >= 0, "Finished missing")
        val lastEnd = events.indexOfLast { it is ProviderEvent.ToolCallEnd }
        assertTrue(lastEnd in 0 until finished, "ToolCallEnd must precede Finished")
    }

    @Test
    fun `wire reasoning effort is sent without explicit thinking`() = runTest {
        server.enqueue(MockResponse().setBody("data: [DONE]\n\n"))

        val req = Wire.request(
            model = "deepseek-v4-pro",
            system = "You are helpful.",
            messages = listOf(WireMessage(role = "user", text = "hi")),
            tools = emptyList(),
            agent = AgentConfig(reasoningEffort = "high"),
            sessionHint = null,
        )
        provider().stream(req).toList()

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""), body)
        assertTrue(!body.contains("\"thinking\""), "thinking should stay off: $body")
    }
}
