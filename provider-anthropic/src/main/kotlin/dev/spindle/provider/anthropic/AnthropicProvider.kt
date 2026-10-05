package dev.spindle.provider.anthropic

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.provider.WireMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaType()

/**
 * Hard cap on a single SSE line. A frame's JSON must be parsed as a whole, so
 * one line is inherently held in memory; the cap stops a malformed or hostile
 * stream (a line that never terminates) from growing the adapter without bound
 * over a long turn. 16 MiB comfortably fits the largest realistic tool argument.
 */
internal const val MAX_SSE_LINE_BYTES: Long = 16L * 1024 * 1024

/**
 * Like [BufferedSource.readUtf8Line] but bounded: throws [IOException] if a line
 * exceeds [maxBytes] before its terminator is seen, while still returning the
 * final unterminated line at EOF (and null once the stream is exhausted).
 */
internal fun BufferedSource.readBoundedUtf8Line(maxBytes: Long = MAX_SSE_LINE_BYTES): String? {
    while (true) {
        val newline = indexOf('\n'.code.toByte())
        if (newline != -1L) {
            if (newline > maxBytes) throw IOException("SSE line exceeds $maxBytes bytes")
            return readUtf8Line()
        }
        // No newline buffered: if the buffered bytes already exceed the cap the
        // current (unterminated) line is over the limit — fail before reading more.
        if (buffer.size > maxBytes) throw IOException("SSE line exceeds $maxBytes bytes")
        // Force an actual read by asking for one byte beyond what is buffered:
        // request(1) would report success forever while bytes sit unconsumed and
        // contain no newline. A false result here means a true end-of-stream.
        if (!request(buffer.size + 1)) {
            if (buffer.size == 0L) return null
            return readUtf8()
        }
    }
}

/** Sensible built-in catalogue; Anthropic has no public `/models` listing here. */
val ANTHROPIC_DEFAULT_MODELS: List<ModelInfo> = listOf(
    ModelInfo(
        providerId = "anthropic",
        id = "claude-sonnet-4-20250514",
        label = "Claude Sonnet 4",
        contextWindow = 200_000,
        maxOutputTokens = 64_000,
        supportsTools = true,
        supportsReasoning = true,
    ),
    ModelInfo(
        providerId = "anthropic",
        id = "claude-opus-4-20250514",
        label = "Claude Opus 4",
        contextWindow = 200_000,
        maxOutputTokens = 32_000,
        supportsTools = true,
        supportsReasoning = true,
    ),
    ModelInfo(
        providerId = "anthropic",
        id = "claude-3-7-sonnet-20250219",
        label = "Claude 3.7 Sonnet",
        contextWindow = 200_000,
        maxOutputTokens = 64_000,
        supportsTools = true,
        supportsReasoning = true,
    ),
    ModelInfo(
        providerId = "anthropic",
        id = "claude-3-5-haiku-20241022",
        label = "Claude 3.5 Haiku",
        contextWindow = 200_000,
        maxOutputTokens = 8_192,
        supportsTools = true,
    ),
)

/**
 * Streaming adapter for Anthropic's `/v1/messages` endpoint.
 *
 * The constructor performs no IO.
 */
class AnthropicProvider(
    baseUrl: String,
    private val apiKey: String,
    override val id: String = "anthropic",
    private val version: String = "2023-06-01",
    private val defaultModels: List<ModelInfo> = ANTHROPIC_DEFAULT_MODELS,
    private val userAgent: String? = null,
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** Upper bound on a single SSE line; guards long/hostile streams. */
    private val maxSseLineBytes: Long = MAX_SSE_LINE_BYTES,
) : Provider {

    private val root: String = baseUrl.trimEnd('/')

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    override suspend fun models(): List<ModelInfo> = defaultModels.map { it.copy(providerId = id) }

    override fun stream(request: ChatRequest): Flow<ProviderEvent> = channelFlow {
        val payload = buildPayload(request)
        val httpReq = Request.Builder()
            .url("$root/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", version)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .apply { userAgent?.let { header("User-Agent", it) } }
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .apply { request.sessionHint?.let { header("x-opencode-session", it) } }
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(httpReq)
        val state = AnthropicStreamState()
        val terminated = AtomicBoolean(false)
        val terminal: (ProviderEvent) -> Unit = { event ->
            if (terminated.compareAndSet(false, true)) trySend(event)
        }
        val emitEvent: (ProviderEvent) -> Unit = { event -> trySend(event) }
        val emitFinish: (FinishReason) -> Unit = { terminal(ProviderEvent.Finished(it)) }
        val fail: (String) -> Unit = { message -> terminal(ProviderEvent.Failure(message)) }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                terminal(ProviderEvent.Failure("Anthropic stream I/O: ${e.message}", e))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = runCatching { resp.body?.string() }.getOrNull()
                        terminal(ProviderEvent.Failure("Anthropic HTTP ${resp.code}: ${body ?: ""}"))
                        return@use
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        terminal(ProviderEvent.Failure("Anthropic: empty response body"))
                        return@use
                    }
                    try {
                        val data = StringBuilder()
                        var failed = false
                        while (true) {
                            val line = source.readBoundedUtf8Line(maxSseLineBytes) ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    val frame = data.toString()
                                    data.setLength(0)
                                    if (state.handle(frame, emitEvent, emitFinish, fail)) {
                                        failed = true
                                        break
                                    }
                                }
                                continue
                            }
                            if (line.startsWith(":") || !line.startsWith("data:")) continue
                            val payloadLine = line.substring(5)
                                .let { if (it.startsWith(" ")) it.substring(1) else it }
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(payloadLine)
                        }
                        if (!failed && data.isNotEmpty()) {
                            if (state.handle(data.toString(), emitEvent, emitFinish, fail)) failed = true
                        }
                        if (!failed && !state.sawFinish) {
                            state.closeToolCalls(emitEvent)
                            // A stream ends with message_stop (or a stop_reason on
                            // message_delta). Reaching EOF with neither means the
                            // connection died mid-turn: surface a Failure so the loop
                            // retries instead of persisting a truncated response.
                            terminal(ProviderEvent.Failure("Anthropic: stream ended before completion (unexpected EOF)"))
                        }
                    } catch (e: IOException) {
                        terminal(ProviderEvent.Failure("Anthropic stream I/O: ${e.message}", e))
                    }
                }
                } catch (e: Throwable) {
                    terminal(ProviderEvent.Failure("Anthropic stream error: ${e.message}", e))
                } finally {
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun buildPayload(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        val system = buildList {
            if (request.system.isNotBlank()) add(request.system)
            request.messages.forEach { m ->
                val text = m.text
                if (m.role == "system" && text != null && text.isNotEmpty()) add(text)
            }
        }.joinToString("\n")
        if (system.isNotBlank()) put("system", system)
        put("messages", buildMessages(request.messages))
        if (request.tools.isNotEmpty()) put("tools", buildTools(request.tools))
        put("max_tokens", request.maxTokens ?: 4096)
        request.temperature?.let { put("temperature", it) }
        put("stream", true)
    }

    private fun buildTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("name", t.name)
                put("description", t.description)
                put("input_schema", parseJsonOrEmpty(t.parametersJson))
            })
        }
    }

    /**
     * Anthropic demands strict user/assistant alternation, so consecutive same-role
     * wire messages are merged into a single turn. Within a user turn the API also
     * requires `tool_result` blocks to precede everything else. System text is folded
     * into the top-level `system` field, never sent as a user turn.
     */
    private fun buildMessages(messages: List<WireMessage>): JsonArray {
        val turns = ArrayList<Pair<String, MutableList<JsonElement>>>()

        fun push(role: String, blocks: List<JsonElement>) {
            val last = turns.lastOrNull()
            if (last != null && last.first == role) last.second.addAll(blocks)
            else turns.add(role to blocks.toMutableList())
        }

        for (m in messages) {
            when (m.role) {
                "system" -> Unit
                "assistant" -> {
                    val blocks = ArrayList<JsonElement>()
                    m.text?.takeIf { it.isNotEmpty() }?.let {
                        blocks.add(buildJsonObject {
                            put("type", "text")
                            put("text", it)
                        })
                    }
                    m.toolCalls.forEach { c ->
                        blocks.add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", c.id)
                            put("name", c.name)
                            put("input", parseJsonOrEmpty(c.argumentsJson))
                        })
                    }
                    push("assistant", blocks)
                }
                "tool" -> push(
                    "user",
                    listOf(buildJsonObject {
                        put("type", "tool_result")
                        put("tool_use_id", m.toolCallId ?: "")
                        put("content", m.text ?: "")
                    }),
                )
                else -> {
                    val blocks = ArrayList<JsonElement>()
                    m.text?.takeIf { it.isNotEmpty() }?.let {
                        blocks.add(buildJsonObject {
                            put("type", "text")
                            put("text", it)
                        })
                    }
                    m.images.forEach { img ->
                        blocks.add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", img.mime)
                                put("data", img.base64)
                            })
                        })
                    }
                    if (blocks.isNotEmpty()) push("user", blocks)
                }
            }
        }

        return buildJsonArray {
            turns.forEach { (role, blocks) ->
                val ordered = if (role == "user") {
                    blocks.sortedBy { if ((it as? JsonObject)?.str("type") == "tool_result") 0 else 1 }
                } else {
                    blocks
                }
                add(buildJsonObject {
                    put("role", role)
                    put("content", JsonArray(ordered))
                })
            }
        }
    }
}

private class AnthropicStreamState {
    private val toolUseIndices = linkedSetOf<Int>()
    private val ended = HashSet<Int>()

    var sawFinish = false
        private set

    /** Returns true when the stream must stop because a terminal error was emitted. */
    fun handle(
        frame: String,
        emit: (ProviderEvent) -> Unit,
        finish: (FinishReason) -> Unit,
        fail: (String) -> Unit,
    ): Boolean {
        val rootObj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
            ?: return false
        when (rootObj.str("type")) {
            "message_start" -> {
                val usage = (rootObj["message"] as? JsonObject)?.get("usage") as? JsonObject
                if (usage != null) {
                    emit(
                        ProviderEvent.UsageEvent(
                            Usage(
                                inputTokens = usage.int("input_tokens") ?: 0,
                                cacheReadTokens = usage.int("cache_read_input_tokens") ?: 0,
                                cacheWriteTokens = usage.int("cache_creation_input_tokens") ?: 0,
                            ),
                        ),
                    )
                }
            }
            "content_block_start" -> {
                val index = rootObj.int("index") ?: 0
                val block = rootObj["content_block"] as? JsonObject
                if (block?.str("type") == "tool_use") {
                    toolUseIndices.add(index)
                    emit(
                        ProviderEvent.ToolCallStart(
                            index,
                            block.str("id") ?: "",
                            block.str("name") ?: "",
                        ),
                    )
                    val input = block["input"] as? JsonObject
                    if (input != null && input.isNotEmpty()) {
                        emit(ProviderEvent.ToolCallArgsDelta(index, input.toString()))
                    }
                }
            }
            "content_block_delta" -> {
                val index = rootObj.int("index") ?: 0
                val delta = rootObj["delta"] as? JsonObject
                when (delta?.str("type")) {
                    "text_delta" -> delta.str("text")
                        ?.let { emit(ProviderEvent.TextDelta(it)) }
                    "thinking_delta" -> delta.str("thinking")
                        ?.let { emit(ProviderEvent.ReasoningDelta(it)) }
                    "input_json_delta" -> delta.str("partial_json")
                        ?.let { emit(ProviderEvent.ToolCallArgsDelta(index, it)) }
                }
            }
            "content_block_stop" -> {
                val index = rootObj.int("index") ?: 0
                if (index in toolUseIndices && ended.add(index)) {
                    emit(ProviderEvent.ToolCallEnd(index))
                }
            }
            "message_delta" -> {
                (rootObj["usage"] as? JsonObject)?.let {
                    emit(
                        ProviderEvent.UsageEvent(
                            Usage(outputTokens = it.int("output_tokens") ?: 0),
                        ),
                    )
                }
                val stop = (rootObj["delta"] as? JsonObject)?.str("stop_reason")
                if (stop != null) {
                    // Emit any outstanding ToolCallEnd before the terminal Finished,
                    // even if the provider omitted the matching content_block_stop.
                    closeToolCalls(emit)
                    sawFinish = true
                    finish(mapStop(stop))
                }
            }
            "message_stop" -> {
                if (!sawFinish) {
                    closeToolCalls(emit)
                    sawFinish = true
                    finish(FinishReason.UNKNOWN)
                }
            }
            "error" -> {
                val err = rootObj["error"] as? JsonObject
                fail(err?.str("message") ?: frame)
                return true
            }
        }
        return false
    }

    fun closeToolCalls(emit: (ProviderEvent) -> Unit) {
        for (index in toolUseIndices) {
            if (ended.add(index)) emit(ProviderEvent.ToolCallEnd(index))
        }
    }
}

private fun parseJsonOrEmpty(raw: String): JsonElement =
    runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonObject(emptyMap()) }

private fun mapStop(reason: String): FinishReason = when (reason) {
    "tool_use" -> FinishReason.TOOL_CALLS
    "end_turn" -> FinishReason.STOP
    "max_tokens" -> FinishReason.LENGTH
    "stop_sequence" -> FinishReason.STOP
    "refusal" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.UNKNOWN
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
