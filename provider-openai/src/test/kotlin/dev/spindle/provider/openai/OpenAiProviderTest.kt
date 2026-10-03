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
import kotlin.test.assertNull
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
    fun `models enriches context price and capabilities from a rich payload`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[{" +
                    "\"id\":\"gpt-4o\"," +
                    "\"name\":\"GPT-4o\"," +
                    "\"context_length\":200000," +
                    "\"max_output_tokens\":16384," +
                    "\"pricing\":{\"prompt\":\"0.0000025\",\"completion\":\"0.00001\"}," +
                    "\"capabilities\":{\"tools\":true,\"vision\":true,\"reasoning\":true}" +
                    "}]}",
            ),
        )

        val m = provider().models().single { it.id == "gpt-4o" }
        assertEquals("GPT-4o", m.label)
        assertEquals(200_000, m.contextWindow)
        assertEquals(16_384, m.maxOutputTokens)
        assertEquals(2.5, m.inputCostPerM, 1e-9)
        assertEquals(10.0, m.outputCostPerM, 1e-9)
        assertTrue(m.supportsTools)
        assertTrue(m.supportsReasoning)
        assertTrue(m.supportsVision)
    }

    @Test
    fun `models treats pricing as per-token and cost as per-million`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[" +
                    "{\"id\":\"cheap\",\"pricing\":{\"prompt\":\"0.0000035\",\"completion\":\"0.000014\"}}," +
                    "{\"id\":\"free\",\"pricing\":{\"prompt\":\"0\",\"completion\":\"0\"}}," +
                    "{\"id\":\"dev\",\"cost\":{\"input\":0.05,\"output\":0.5,\"cache_read\":0.01}}," +
                    "{\"id\":\"absurd\",\"pricing\":{\"prompt\":\"1000\",\"completion\":\"1\"}}" +
                    "]}",
            ),
        )

        val models = provider().models()

        // OpenRouter `pricing` is USD per token: 3.5e-6/token -> 3.5/M.
        val cheap = models.single { it.id == "cheap" }
        assertEquals(3.5, cheap.inputCostPerM, 1e-9)
        assertEquals(14.0, cheap.outputCostPerM, 1e-9)

        // "0" is a real free price, not a missing value.
        val free = models.single { it.id == "free" }
        assertEquals(0.0, free.inputCostPerM, 1e-9)
        assertEquals(0.0, free.outputCostPerM, 1e-9)

        // models.dev `cost` is already USD per 1M: a cheap 0.05 must NOT scale.
        val dev = models.single { it.id == "dev" }
        assertEquals(0.05, dev.inputCostPerM, 1e-9)
        assertEquals(0.5, dev.outputCostPerM, 1e-9)
        assertEquals(0.01, dev.cacheReadCostPerM, 1e-9)

        // An implausible per-token number is dropped, never reinterpreted as per-1M.
        val absurd = models.single { it.id == "absurd" }
        assertEquals(0.0, absurd.inputCostPerM, 1e-9)
        assertEquals(0.0, absurd.outputCostPerM, 1e-9)
    }

    @Test
    fun `models ignores out-of-range and non-finite metadata, keeping embedded defaults`() = runTest {
        val defaults = listOf(
            ModelInfo(
                providerId = "openai", id = "m", label = "Embedded", contextWindow = 200_000,
                maxOutputTokens = 4_096, inputCostPerM = 1.0, outputCostPerM = 2.0,
                supportsTools = true, supportsReasoning = true, supportsVision = true,
            ),
        )
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[{" +
                    "\"id\":\"m\",\"name\":\" \"," +
                    "\"context_length\":200000000," +
                    "\"max_output_tokens\":2000000," +
                    "\"pricing\":{\"prompt\":\"20000\",\"completion\":\"-1\"}" +
                    "}]}",
            ),
        )

        val m = provider(defaults = defaults).models().single { it.id == "m" }
        assertEquals("Embedded", m.label)        // blank name ignored
        assertEquals(200_000, m.contextWindow)   // > 100M ignored
        assertEquals(4_096, m.maxOutputTokens)   // > 1M ignored
        assertEquals(1.0, m.inputCostPerM, 1e-9) // implausibly large per-token price ignored
        assertEquals(2.0, m.outputCostPerM, 1e-9) // negative price ignored
        assertTrue(m.supportsReasoning)
        assertTrue(m.supportsVision)
    }

    @Test
    fun `models rejects negative and non-finite numbers without throwing`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[{" +
                    "\"id\":\"m\"," +
                    "\"context_length\":-1," +
                    "\"max_output_tokens\":\"Infinity\"," +
                    "\"cost\":{\"input\":-3,\"output\":\"NaN\"}," +
                    "\"pricing\":{\"prompt\":\"-0.5\"}" +
                    "}]}",
            ),
        )

        val m = provider().models().single { it.id == "m" }
        assertEquals(128_000, m.contextWindow)
        assertEquals(8_192, m.maxOutputTokens)
        assertEquals(0.0, m.inputCostPerM, 1e-9)
        assertEquals(0.0, m.outputCostPerM, 1e-9)
    }

    @Test
    fun `models skips blank ids and uses the last duplicate id`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[" +
                    "{\"id\":\"\",\"name\":\"blank\"}," +
                    "{\"id\":\"   \"}," +
                    "{\"name\":\"no id\"}," +
                    "null," +
                    "{\"id\":\"dup\",\"context_length\":100000}," +
                    "{\"id\":\"dup\",\"context_length\":250000}" +
                    "]}",
            ),
        )

        val models = provider().models()
        assertEquals(listOf("dup"), models.map { it.id })
        assertEquals(250_000, models.single().contextWindow)
    }

    @Test
    fun `models reads models-dev modalities for vision and limit for context`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[" +
                    "{\"id\":\"vision\",\"limit\":{\"context\":400000,\"output\":128000}," +
                    "\"modalities\":{\"input\":[\"text\",\"image\"]},\"tool_call\":true,\"reasoning\":true}," +
                    "{\"id\":\"text-only\",\"modalities\":{\"input\":[\"text\"]}}" +
                    "]}",
            ),
        )

        val models = provider().models()
        val vision = models.single { it.id == "vision" }
        assertEquals(400_000, vision.contextWindow)
        assertEquals(128_000, vision.maxOutputTokens)
        assertTrue(vision.supportsVision)
        assertTrue(vision.supportsTools)
        assertTrue(vision.supportsReasoning)

        assertTrue(!models.single { it.id == "text-only" }.supportsVision)
    }

    @Test
    fun `models accepts metadata as numeric strings, numbers or scientific notation`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[{" +
                    "\"id\":\"str\"," +
                    "\"context_length\":\"300000\"," +
                    "\"max_output_tokens\":\"64000\"," +
                    "\"pricing\":{\"prompt\":\"1.5e-6\",\"completion\":2e-6}" +
                    "}]}",
            ),
        )

        val m = provider().models().single { it.id == "str" }
        assertEquals(300_000, m.contextWindow)
        assertEquals(64_000, m.maxOutputTokens)
        assertEquals(1.5, m.inputCostPerM, 1e-9)
        assertEquals(2.0, m.outputCostPerM, 1e-9)
    }

    @Test
    fun `models returns the embedded defaults for missing or non-array data`() = runTest {
        val fallback = listOf(ModelInfo(providerId = "openai", id = "fallback"))
        for (body in listOf(
            "{\"models\":[]}",
            "{\"data\":null}",
            "{\"data\":{}}",
            "{\"data\":\"nope\"}",
            "[]",
            "not json",
            "{\"data\":[]}",
        )) {
            server.enqueue(MockResponse().setBody(body))
            assertEquals(fallback, provider(defaults = fallback).models(), "body=$body")
        }
    }

    @Test
    fun `models fetch sends no session header and falls back when it fails`() = runTest {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"gpt-4o\"}]}"))
        assertEquals(listOf("gpt-4o"), provider().models().map { it.id })

        val recorded = server.takeRequest()
        assertEquals("Bearer test-key", recorded.getHeader("Authorization"))
        assertNull(recorded.getHeader("x-opencode-session"))
        assertEquals("/models", recorded.path)

        server.enqueue(MockResponse().setResponseCode(500))
        val fallback = listOf(ModelInfo(providerId = "openai", id = "fallback"))
        assertEquals(fallback, provider(defaults = fallback).models())
    }

    @Test
    fun `models tolerates minimal and unknown payload shapes without crashing`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[" +
                    "{\"id\":\"weird\"}," +
                    "{\"nope\":1}," +
                    "{\"id\":\"broken\",\"context_length\":-5,\"pricing\":{\"prompt\":\"abc\"}}," +
                    "{\"id\":\"modality\",\"architecture\":{\"input_modalities\":[\"text\",\"image\"]}}" +
                    "]}",
            ),
        )

        val models = provider().models()
        assertEquals(listOf("weird", "broken", "modality"), models.map { it.id })

        val weird = models.single { it.id == "weird" }
        assertEquals(128_000, weird.contextWindow)
        assertEquals(0.0, weird.inputCostPerM)
        assertEquals(0.0, weird.outputCostPerM)
        assertTrue(!weird.supportsVision)

        val broken = models.single { it.id == "broken" }
        assertEquals(128_000, broken.contextWindow)
        assertEquals(0.0, broken.inputCostPerM)
        assertTrue(models.single { it.id == "modality" }.supportsVision)
    }

    @Test
    fun `models treats a payload without data as a fallback without throwing`() = runTest {
        server.enqueue(MockResponse().setBody("{\"models\":[]}"))
        val fallback = listOf(ModelInfo(providerId = "openai", id = "fallback"))
        assertEquals(fallback, provider(defaults = fallback).models())
    }

    @Test
    fun `models merges live over defaults by id and keeps default-only ids`() = runTest {
        val defaults = listOf(
            ModelInfo(
                providerId = "openai", id = "gpt-4o", contextWindow = 128_000,
                inputCostPerM = 5.0, outputCostPerM = 15.0,
            ),
            ModelInfo(providerId = "openai", id = "o3", contextWindow = 200_000, supportsReasoning = true),
        )
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":[{\"id\":\"gpt-4o\",\"context_length\":256000," +
                    "\"pricing\":{\"prompt\":\"0.000001\"}}]}",
            ),
        )

        val models = provider(defaults = defaults).models()
        val gpt = models.single { it.id == "gpt-4o" }
        assertEquals(256_000, gpt.contextWindow)
        assertEquals(1.0, gpt.inputCostPerM, 1e-9)
        assertEquals(15.0, gpt.outputCostPerM, 1e-9)
        assertTrue(!gpt.supportsReasoning)
        assertTrue(models.any { it.id == "o3" }, "default-only ids must survive the merge")
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
