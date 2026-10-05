package dev.spindle.provider.anthropic

import dev.spindle.core.agent.Retry
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.provider.WireImage
import dev.spindle.core.provider.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    private fun frame(vararg lines: String): String =
        lines.joinToString("") { "data: $it\n\n" }

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
                ProviderEvent.ToolCallEnd(1),
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

    @Test
    fun `system wire messages fold into top-level system and never a user turn`() = runTest {
        server.enqueue(MockResponse().setChunkedBody("data: {\"type\":\"message_stop\"}\n\n", 5))

        val req = request().copy(
            messages = listOf(
                WireMessage(role = "system", text = "extra rules"),
                WireMessage(role = "user", text = "hi"),
            ),
        )
        provider().stream(req).toList()

        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue(body["system"]!!.jsonPrimitive.content.contains("extra rules"), "system text dropped")

        val messages = body["messages"]!!.jsonArray
        assertTrue(messages.none { it.jsonObject["role"]!!.jsonPrimitive.content == "system" })
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tool_use start with inline input emits a single args delta`() = runTest {
        server.enqueue(
            MockResponse().setChunkedBody(
                frame(
                    "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":" +
                        "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"search\",\"input\":{\"q\":\"cats\"}}}",
                    "{\"type\":\"content_block_stop\",\"index\":0}",
                    "{\"type\":\"message_stop\"}",
                ),
                5,
            ),
        )

        val events = provider().stream(request()).toList()

        assertEquals(
            listOf(
                ProviderEvent.ToolCallStart(0, "t1", "search"),
                ProviderEvent.ToolCallArgsDelta(0, "{\"q\":\"cats\"}"),
                ProviderEvent.ToolCallEnd(0),
                ProviderEvent.Finished(FinishReason.UNKNOWN),
            ),
            events,
        )
    }

    @Test
    fun `error event then message_stop yields exactly one terminal`() = runTest {
        server.enqueue(
            MockResponse().setChunkedBody(
                frame(
                    "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}",
                    "{\"type\":\"message_stop\"}",
                ),
                5,
            ),
        )

        val events = provider().stream(request()).toList()

        val terminals = events.filter { it is ProviderEvent.Finished || it is ProviderEvent.Failure }
        assertEquals(1, terminals.size, "exactly one terminal event")
        val failure = terminals.single() as ProviderEvent.Failure
        assertTrue(failure.message.contains("busy"), failure.message)
    }

    @Test
    fun `mid-stream disconnect emits one failure and does not throw`() = runTest {
        val body = buildString {
            repeat(50) {
                append("data: {\"type\":\"content_block_delta\",\"index\":0," +
                    "\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}\n\n")
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
                .setBody("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n")
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
    fun `session hint and user agent are sent as headers`() = runTest {
        val subject = AnthropicProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            userAgent = "spindle/test",
            extraHeaders = mapOf("X-Title" to "spindle"),
        )
        server.enqueue(MockResponse().setChunkedBody("data: {\"type\":\"message_stop\"}\n\n", 5))

        subject.stream(request().copy(sessionHint = "sess-9")).toList()

        val recorded = server.takeRequest()
        assertEquals("sess-9", recorded.getHeader("x-opencode-session"))
        assertEquals("spindle/test", recorded.getHeader("User-Agent"))
        assertEquals("spindle", recorded.getHeader("X-Title"))
    }

    // --- robustness: long turns, resource lifetime, concurrency --------------

    private fun recordedEvents(): List<ProviderEvent> = listOf(
        ProviderEvent.UsageEvent(Usage(inputTokens = 12, cacheReadTokens = 4)),
        ProviderEvent.TextDelta("Hi "),
        ProviderEvent.TextDelta("there"),
        ProviderEvent.ToolCallStart(1, "toolu_1", "search"),
        ProviderEvent.ToolCallArgsDelta(1, "{\"q\":"),
        ProviderEvent.ToolCallArgsDelta(1, "\"cats\"}"),
        ProviderEvent.ToolCallEnd(1),
        ProviderEvent.UsageEvent(Usage(outputTokens = 7)),
        ProviderEvent.Finished(FinishReason.TOOL_CALLS),
    )

    @Test
    fun `stream truncated before message_stop is a retryable failure`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                frame(
                    "{\"type\":\"content_block_delta\",\"index\":0," +
                        "\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}",
                ),
            ),
        )

        val events = provider().stream(request()).toList()

        val terminals = events.filter { it is ProviderEvent.Finished || it is ProviderEvent.Failure }
        assertEquals(1, terminals.size, "exactly one terminal event")
        val failure = terminals.single() as ProviderEvent.Failure
        assertTrue(failure.message.contains("ended before completion"), failure.message)
        assertTrue(Retry.isRetryable(failure.message), "a truncated turn must be retryable: ${failure.message}")
        assertEquals(listOf(ProviderEvent.TextDelta("partial")), events.filterIsInstance<ProviderEvent.TextDelta>())
    }

    @Test
    fun `bounded line reader enforces the cap and preserves eof and crlf semantics`() {
        assertEquals("abc", Buffer().writeUtf8("abc\ndef\n").readBoundedUtf8Line(3))
        assertEquals("abc", Buffer().writeUtf8("abc\r\n").readBoundedUtf8Line())
        assertEquals("hello", Buffer().writeUtf8("hello").readBoundedUtf8Line())
        assertNull(Buffer().writeUtf8("").readBoundedUtf8Line())

        val eof = Buffer().writeUtf8("abc")
        assertEquals("abc", eof.readBoundedUtf8Line())
        assertNull(eof.readBoundedUtf8Line())

        assertFailsWith<IOException> { Buffer().writeUtf8("abcd\n").readBoundedUtf8Line(3) }
        assertFailsWith<IOException> { Buffer().writeUtf8("ééé\n").readBoundedUtf8Line(4) }
    }

    @Test
    fun `oversized sse line fails the turn instead of buffering without bound`() = runTest {
        server.enqueue(MockResponse().setBody("data: " + "x".repeat(600) + "\n\n"))
        val subject = AnthropicProvider(
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            maxSseLineBytes = 256,
        )

        val terminals = subject.stream(request()).toList()
            .filter { it is ProviderEvent.Finished || it is ProviderEvent.Failure }

        assertEquals(1, terminals.size)
        assertTrue((terminals.single() as ProviderEvent.Failure).message.contains("exceeds"), terminals.single().toString())
    }

    @Test
    fun `recorded stream reassembles across every 1 2 3 5 7 byte split`() = runTest {
        for (chunk in listOf(1, 2, 3, 5, 7)) {
            server.enqueue(MockResponse().setChunkedBody(fixture("anthropic_stream.sse"), chunk))
            val events = provider().stream(request()).toList()
            assertEquals(recordedEvents(), events, "chunk=$chunk")
        }
    }

    @Test
    fun `long stream emits every delta once with one terminal and usage once`() = runTest {
        val chunks = 5_000
        val body = buildString {
            repeat(chunks) {
                append(
                    "data: {\"type\":\"content_block_delta\",\"index\":0," +
                        "\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}\n\n",
                )
            }
            append("data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(MockResponse().setChunkedBody(body, 37))

        val events = provider().stream(request()).toList()

        assertEquals("x".repeat(chunks), events.filterIsInstance<ProviderEvent.TextDelta>().joinToString("") { it.text })
        assertEquals(chunks, events.count { it is ProviderEvent.TextDelta }, "no duplicate or missing deltas")
        assertEquals(1, events.count { it is ProviderEvent.Finished || it is ProviderEvent.Failure })
        assertEquals(1, events.count { it is ProviderEvent.UsageEvent }, "usage emitted once")
    }

    @Test
    fun `concurrent streams on one provider keep independent per-stream state`() = runTest {
        val copies = 6
        repeat(copies) { server.enqueue(MockResponse().setChunkedBody(fixture("anthropic_stream.sse"), 9)) }
        val subject = provider()

        val results = withContext(Dispatchers.IO) {
            coroutineScope {
                (1..copies).map { async { subject.stream(request()).toList() } }.awaitAll()
            }
        }

        assertEquals(copies, results.size)
        results.forEachIndexed { i, events ->
            assertEquals(1, events.count { it is ProviderEvent.ToolCallEnd }, "stream $i tool end count")
            assertEquals(recordedEvents(), events, "stream $i must not share state with its peers")
        }
    }

    @Test
    fun `sequential streams close their body and reuse the pooled connection`() = runTest {
        val subject = provider()
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        subject.stream(request()).toList()
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        subject.stream(request()).toList()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(0, first.sequenceNumber)
        assertEquals(1, second.sequenceNumber, "leaked response body would force a fresh connection")
    }

    @Test
    fun `http error closes its body and the connection is reusable`() = runTest {
        val subject = provider()
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"nope\"}"))
        subject.stream(request()).toList()
        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        subject.stream(request()).toList()

        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals(0, first.sequenceNumber)
        assertEquals(1, second.sequenceNumber)
    }

    @Test
    fun `cancelling a stalled stream releases it and a later stream still works`() = runBlocking {
        val subject = provider()
        server.enqueue(
            MockResponse()
                .setBody("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}\n\n")
                .setBodyDelay(3, TimeUnit.SECONDS),
        )
        val job = launch(Dispatchers.IO) { subject.stream(request()).collect { } }
        delay(200)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCancelled)

        server.enqueue(MockResponse().setBody("data: {\"type\":\"message_stop\"}\n\n"))
        val events = withTimeout(5_000) { subject.stream(request()).toList() }
        assertEquals(1, events.count { it is ProviderEvent.Finished || it is ProviderEvent.Failure })
    }
}
