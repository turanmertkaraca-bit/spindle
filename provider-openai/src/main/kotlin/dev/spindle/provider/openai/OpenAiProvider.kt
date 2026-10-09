package dev.spindle.provider.openai

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Usage
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderErrors
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

// Sanity bounds for live `/models` metadata. Anything outside is ignored so a
// malformed or future payload cannot poison a resolved model's capabilities.
private const val MAX_CONTEXT_WINDOW = 100_000_000
private const val MAX_OUTPUT_TOKENS = 1_000_000
private const val MAX_PRICE_PER_M = 10_000.0

// Pricing units are decided by schema key, never guessed from magnitude:
//   * OpenRouter `pricing.*`  — USD per token, always scaled by 1e6.
//   * models.dev `cost.*`     — USD per 1M tokens, used as-is.
// A price that is implausible for its declared unit is dropped, so the embedded
// snapshot wins rather than a wrong number. This is deliberate: a magnitude
// threshold (e.g. "< 1.0 means per-token") misreads a legitimate cheap
// per-1M price such as 0.05 when it arrives under `cost`, and would silently
// reinterpret a per-token value >= 1 as if it were per-1M.
private const val PER_TOKEN_SCALE = 1_000_000.0

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

/**
 * Live entries replace embedded ones by id; embedded-only ids are preserved so a
 * default model always resolves even when the live listing omits it.
 */
private fun mergeById(embedded: List<ModelInfo>, live: List<ModelInfo>): List<ModelInfo> {
    val merged = LinkedHashMap<String, ModelInfo>(embedded.size + live.size)
    for (m in embedded) merged[m.id] = m
    for (m in live) merged[m.id] = m
    return merged.values.toList()
}

/**
 * Overlay whichever capability/price/context fields the endpoint actually
 * provides onto [base], leaving every omitted field untouched. Unknown keys and
 * unexpected shapes are ignored rather than throwing.
 */
private fun enrichModel(base: ModelInfo, o: JsonObject): ModelInfo {
    val caps = o["capabilities"] as? JsonObject
    val pricing = o["pricing"] as? JsonObject
    val cost = o["cost"] as? JsonObject
    val top = o["top_provider"] as? JsonObject
    val limit = o["limit"] as? JsonObject

    val context = o["context_length"].boundedInt(1, MAX_CONTEXT_WINDOW)
        ?: o["context_window"].boundedInt(1, MAX_CONTEXT_WINDOW)
        ?: top?.get("context_length").boundedInt(1, MAX_CONTEXT_WINDOW)
        ?: limit?.get("context").boundedInt(1, MAX_CONTEXT_WINDOW)
    val maxOutput = o["max_output_tokens"].boundedInt(1, MAX_OUTPUT_TOKENS)
        ?: top?.get("max_completion_tokens").boundedInt(1, MAX_OUTPUT_TOKENS)
        ?: limit?.get("output").boundedInt(1, MAX_OUTPUT_TOKENS)

    return base.copy(
        label = o.str("name")?.takeIf { it.isNotBlank() } ?: base.label,
        contextWindow = context ?: base.contextWindow,
        maxOutputTokens = maxOutput ?: base.maxOutputTokens,
        supportsTools = caps?.get("tools").bool() ?: o["tool_call"].bool() ?: base.supportsTools,
        supportsReasoning = caps?.get("reasoning").bool() ?: o["reasoning"].bool() ?: base.supportsReasoning,
        supportsVision = caps?.get("vision").bool() ?: inputHasImage(o) ?: base.supportsVision,
        inputCostPerM = perTokenPrice(pricing?.get("prompt"))
            ?: perMillionPrice(cost?.get("input")) ?: base.inputCostPerM,
        outputCostPerM = perTokenPrice(pricing?.get("completion"))
            ?: perMillionPrice(cost?.get("output")) ?: base.outputCostPerM,
        cacheReadCostPerM = perTokenPrice(pricing?.get("input_cache_read") ?: pricing?.get("cache_read"))
            ?: perMillionPrice(cost?.get("cache_read")) ?: base.cacheReadCostPerM,
        cacheWriteCostPerM = perTokenPrice(pricing?.get("input_cache_write") ?: pricing?.get("cache_write"))
            ?: perMillionPrice(cost?.get("cache_write")) ?: base.cacheWriteCostPerM,
    )
}

/** OpenRouter-style USD-per-token price, unconditionally scaled to USD per 1M. */
private fun perTokenPrice(el: JsonElement?): Double? {
    val v = el.num() ?: return null
    if (!v.isFinite() || v < 0.0) return null
    return (v * PER_TOKEN_SCALE).takeIf { it.isFinite() && it <= MAX_PRICE_PER_M }
}

/** models.dev-style USD-per-1M price, accepted as-is. */
private fun perMillionPrice(el: JsonElement?): Double? {
    val v = el.num() ?: return null
    if (!v.isFinite() || v < 0.0 || v > MAX_PRICE_PER_M) return null
    return v
}

private fun inputHasImage(o: JsonObject): Boolean? {
    val arch = o["architecture"] as? JsonObject
    if (arch != null) {
        val modalities = arch["input_modalities"] as? JsonArray
        if (modalities != null) {
            return modalities.any { (it as? JsonPrimitive)?.content?.contains("image") == true }
        }
        arch.str("modality")?.let { return it.contains("image") }
    }
    // models.dev advertises vision as `modalities.input` containing "image".
    val inputs = (o["modalities"] as? JsonObject)?.get("input") as? JsonArray ?: return null
    return inputs.any { (it as? JsonPrimitive)?.content?.contains("image") == true }
}

private fun JsonElement?.boundedInt(min: Int, max: Int): Int? {
    val v = num()?.toInt() ?: return null
    return v.takeIf { it in min..max }
}

private fun JsonElement?.num(): Double? =
    (this as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonElement?.bool(): Boolean? =
    (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

/**
 * Streaming adapter for OpenAI-compatible `/chat/completions` endpoints.
 *
 * The constructor performs no IO; the underlying [OkHttpClient] is created lazily
 * on the first request so an instance is safe to construct at configuration time.
 */
class OpenAiProvider(
    baseUrl: String,
    private val apiKey: String,
    override val id: String = "openai",
    private val extraHeaders: Map<String, String> = emptyMap(),
    private val userAgent: String = "spindle/0.1",
    private val defaultModels: List<ModelInfo> = emptyList(),
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

    override suspend fun models(): List<ModelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$root/models")
                .header("Authorization", "Bearer $apiKey")
                .header("User-Agent", userAgent)
                .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching defaultModels
                val text = resp.body?.string() ?: return@runCatching defaultModels
                val data = runCatching {
                    json.parseToJsonElement(text).jsonObject["data"] as? JsonArray
                }.getOrNull() ?: return@runCatching defaultModels
                val defaultsById = defaultModels.associateBy { it.id }
                val mapped = data.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val mid = o.str("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    // Embed-build the entry from its fallback (if any) so fields the
                    // endpoint does not advertise keep their embedded value.
                    val base = defaultsById[mid] ?: ModelInfo(
                        providerId = id,
                        id = mid,
                        label = mid,
                        supportsReasoning = supportsReasoning(mid),
                    )
                    enrichModel(base, o)
                }
                if (mapped.isEmpty()) defaultModels else mergeById(defaultModels, mapped)
            }
        }.getOrElse { defaultModels }
    }

    override fun stream(request: ChatRequest): Flow<ProviderEvent> = channelFlow {
        val payload = buildPayload(request)
        val httpReq = Request.Builder()
            .url("$root/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .header("User-Agent", userAgent)
            .apply { extraHeaders.forEach { (k, v) -> header(k, v) } }
            .apply { request.sessionHint?.let { header("x-opencode-session", it) } }
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        val call = client.newCall(httpReq)
        val state = OpenAiStreamState()
        val terminated = AtomicBoolean(false)
        val terminal: (ProviderEvent) -> Unit = { event ->
            if (terminated.compareAndSet(false, true)) trySend(event)
        }
        val emitEvent: (ProviderEvent) -> Unit = { event -> trySend(event) }
        val emitFinish: (FinishReason) -> Unit = { terminal(ProviderEvent.Finished(it)) }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                terminal(ProviderEvent.Failure("OpenAI stream I/O: ${e.message}", e))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val body = runCatching { resp.body?.string() }.getOrNull()
                        terminal(
                            ProviderEvent.Failure(
                                "OpenAI HTTP ${resp.code}: ${body ?: ""}",
                                statusCode = resp.code,
                                retryAfterMs = parseRetryAfter(resp.header("retry-after"))
                                    ?: parseRetryAfter(resp.header("retry-after-ms"), isMillis = true),
                                contextOverflow = ProviderErrors.isContextOverflow(body ?: "", resp.code),
                            ),
                        )
                        return@use
                    }
                    val source = resp.body?.source()
                    if (source == null) {
                        terminal(ProviderEvent.Failure("OpenAI: empty response body"))
                        return@use
                    }
                    try {
                        val data = StringBuilder()
                        var done = false
                        var failed = false
                        while (!done && !failed) {
                            val line = source.readBoundedUtf8Line(maxSseLineBytes) ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    val frame = data.toString()
                                    data.setLength(0)
                                    when {
                                        frame == "[DONE]" -> done = true
                                        state.handle(frame, emitEvent, emitFinish, terminal) -> failed = true
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
                        if (!done && !failed && data.isNotEmpty()) {
                            val frame = data.toString()
                            if (frame == "[DONE]") done = true
                            else if (state.handle(frame, emitEvent, emitFinish, terminal)) failed = true
                        }
                        if (!failed && !state.sawFinish) {
                            state.closeToolCalls(emitEvent)
                            // An explicit `[DONE]` sentinel means the provider ended
                            // the stream but omitted finish_reason, so UNKNOWN is a
                            // legitimate terminal. Reaching EOF with neither `[DONE]`
                            // nor a finish_reason is a truncated turn: surface it as a
                            // Failure so the loop retries instead of silently
                            // persisting a partial response.
                            if (done) emitFinish(FinishReason.UNKNOWN)
                            else terminal(ProviderEvent.Failure("OpenAI: stream ended before completion (unexpected EOF)"))
                        }
                    } catch (e: IOException) {
                        terminal(ProviderEvent.Failure("OpenAI stream I/O: ${e.message}", e))
                    }
                }
                } catch (e: Throwable) {
                    terminal(ProviderEvent.Failure("OpenAI stream error: ${e.message}", e))
                } finally {
                    close()
                }
            }
        })
        awaitClose { call.cancel() }
    }.buffer(Channel.UNLIMITED).flowOn(Dispatchers.IO)

    private fun supportsReasoning(modelId: String): Boolean {
        val m = modelId.lowercase()
        return m.contains("reason") || m.contains("r1") || m.contains("v4")
    }

    private fun buildPayload(request: ChatRequest): JsonObject = buildJsonObject {
        put("stream", true)
        put("model", request.model)
        put("messages", buildMessages(request))
        if (request.tools.isNotEmpty()) {
            put("tools", buildTools(request.tools))
            put("tool_choice", "auto")
        }
        request.temperature?.let { put("temperature", it) }
        request.maxTokens?.let { put("max_tokens", it) }
        put("stream_options", buildJsonObject { put("include_usage", true) })
        request.reasoningEffort?.let { put("reasoning_effort", it) }
        if (request.thinking == true) {
            put("thinking", buildJsonObject { put("type", "enabled") })
        }
        // Opt-in structured output. When `schemaJson` is malformed JSON we omit
        // the whole key rather than emit `schema: null` (which providers reject
        // anyway); the request stays valid and no exception escapes the adapter.
        request.responseFormat?.let { rf ->
            val schema = runCatching { json.parseToJsonElement(rf.schemaJson) }.getOrNull()
            if (schema != null) {
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put(
                            "json_schema",
                            buildJsonObject {
                                put("name", rf.name)
                                put("schema", schema)
                                put("strict", rf.strict)
                            },
                        )
                    },
                )
            }
        }
    }

    private fun buildMessages(request: ChatRequest): JsonArray = buildJsonArray {
        if (request.system.isNotBlank()) {
            add(buildJsonObject {
                put("role", "system")
                put("content", request.system)
            })
        }
        for (m in request.messages) {
            when (m.role) {
                "system" -> add(buildJsonObject {
                    put("role", "system")
                    put("content", m.text ?: "")
                })
                "user" -> add(buildJsonObject {
                    put("role", "user")
                    if (m.images.isEmpty()) {
                        put("content", m.text ?: "")
                    } else {
                        put("content", buildJsonArray {
                            m.text?.takeIf { it.isNotEmpty() }?.let {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", it)
                                })
                            }
                            m.images.forEach { img ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:${img.mime};base64,${img.base64}")
                                    })
                                })
                            }
                        })
                    }
                })
                "assistant" -> add(buildJsonObject {
                    put("role", "assistant")
                    val text = m.text
                    if (text != null) put("content", text) else put("content", JsonNull)
                    if (m.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            m.toolCalls.forEach { c ->
                                add(buildJsonObject {
                                    put("id", c.id)
                                    put("type", "function")
                                    put("function", buildJsonObject {
                                        put("name", c.name)
                                        put("arguments", c.argumentsJson)
                                    })
                                })
                            }
                        })
                    }
                })
                "tool" -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId ?: "")
                    put("content", m.text ?: "")
                })
            }
        }
    }

    private fun buildTools(tools: List<ToolSpec>): JsonArray = buildJsonArray {
        tools.forEach { t ->
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("parameters", json.parseToJsonElement(t.parametersJson))
                })
            })
        }
    }
}

/**
 * Per-stream SSE state. Tool-call metadata arrives split across frames, so we
 * accumulate id/name per index and emit exactly one [ProviderEvent.ToolCallStart]
 * once the name is known, buffering any argument fragments that precede it.
 */
private class OpenAiStreamState {
    private val ids = HashMap<Int, String>()
    private val names = HashMap<Int, String>()
    private val bufferedArgs = HashMap<Int, StringBuilder>()
    private val started = sortedSetOf<Int>()
    private val ended = HashSet<Int>()

    /**
     * Some routes stream `reasoning` as incremental fragments, others as a
     * cumulative snapshot of everything so far. Only the previous raw value is
     * needed to tell the two apart — tracking that (instead of concatenating the
     * whole reasoning block) keeps adapter memory bounded across a long turn.
     */
    private var lastReasoning = ""

    /**
     * Last usage snapshot emitted for this stream. OpenAI-compatible providers
     * send usage as a complete snapshot (usually one final frame), but some
     * gateways repeat the same frame per chunk; emitting only when it changes
     * stops the agent loop (which sums [ProviderEvent.UsageEvent]s) from
     * double-counting the same prompt.
     */
    private var lastUsage: Usage? = null

    var sawFinish = false
        private set

    fun handle(
        frame: String,
        emit: (ProviderEvent) -> Unit,
        finish: (FinishReason) -> Unit,
        fail: (ProviderEvent.Failure) -> Unit,
    ): Boolean {
        val root = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return false

        // In-band error frames (OpenRouter/OpenAI gateways and the Responses API)
        // arrive as a normal 200 SSE frame; without this they were silently ignored
        // and the turn looked like a clean EOF.
        parseError(root)?.let { failure ->
            closeToolCalls(emit)
            fail(failure)
            return true
        }

        val choices = root["choices"] as? JsonArray
        if (choices != null && choices.isNotEmpty()) {
            val choice = choices[0] as? JsonObject
            if (choice != null) {
                val delta = choice["delta"] as? JsonObject
                if (delta != null) {
                    delta.str("content")?.takeIf { it.isNotEmpty() }
                        ?.let { emit(ProviderEvent.TextDelta(it)) }
                    val reasoning = delta.str("reasoning_content")
                        ?: delta.str("reasoning")
                        ?: delta.str("thinking")
                    if (!reasoning.isNullOrEmpty()) emitReasoning(reasoning, emit)
                    (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                        (el as? JsonObject)?.let { handleToolCall(it, emit) }
                    }
                }
                choice["finish_reason"].primitiveString()?.let {
                    closeToolCalls(emit)
                    sawFinish = true
                    finish(mapFinish(it))
                }
            }
        }

        (root["usage"] as? JsonObject)?.let {
            val parsed = parseUsage(it)
            if (parsed != lastUsage) {
                lastUsage = parsed
                emit(ProviderEvent.UsageEvent(parsed))
            }
        }
        return false
    }

    fun closeToolCalls(emit: (ProviderEvent) -> Unit) {
        for (index in started) {
            if (ended.add(index)) emit(ProviderEvent.ToolCallEnd(index))
        }
    }

    private fun handleToolCall(tc: JsonObject, emit: (ProviderEvent) -> Unit) {
        val index = tc.int("index") ?: 0
        tc.str("id")?.takeIf { it.isNotEmpty() }?.let { ids[index] = it }
        val fn = tc["function"] as? JsonObject
        fn?.str("name")?.takeIf { it.isNotEmpty() }?.let { names[index] = it }
        val args = fn?.str("arguments")

        val name = names[index]
        if (name == null) {
            if (!args.isNullOrEmpty()) bufferedArgs.getOrPut(index) { StringBuilder() }.append(args)
            return
        }
        if (started.add(index)) {
            emit(ProviderEvent.ToolCallStart(index, ids[index] ?: "", name))
            bufferedArgs.remove(index)?.takeIf { it.isNotEmpty() }
                ?.let { emit(ProviderEvent.ToolCallArgsDelta(index, it.toString())) }
        }
        if (!args.isNullOrEmpty()) emit(ProviderEvent.ToolCallArgsDelta(index, args))
    }

    private fun emitReasoning(value: String, emit: (ProviderEvent) -> Unit) {
        when {
            value.isEmpty() || value == lastReasoning -> Unit
            lastReasoning.isNotEmpty() && value.startsWith(lastReasoning) -> {
                val suffix = value.substring(lastReasoning.length)
                if (suffix.isNotEmpty()) emit(ProviderEvent.ReasoningDelta(suffix))
                lastReasoning = value
            }
            else -> {
                emit(ProviderEvent.ReasoningDelta(value))
                lastReasoning = value
            }
        }
    }
}

private fun parseUsage(u: JsonObject): Usage {
    val prompt = u.int("prompt_tokens") ?: u.int("input_tokens") ?: 0
    val details = u["prompt_tokens_details"] as? JsonObject
    val cachedFromDetails = details?.int("cached_tokens") ?: 0
    val cacheWrite = details?.int("cache_write_tokens") ?: 0
    // DeepSeek reports cache accounting top-level, and its prompt_tokens is
    // already hit + miss. Prefer the explicit miss when present.
    val deepseekHit = u.int("prompt_cache_hit_tokens") ?: 0
    val deepseekMiss = u.int("prompt_cache_miss_tokens")
    val cached = maxOf(cachedFromDetails, deepseekHit)
    // Store inputTokens as the *uncached* (miss) portion so it matches Anthropic
    // (whose input_tokens excludes cache) and [dev.spindle.core.agent.Wire.cost],
    // which bills inputTokens at the full input rate and cacheRead/cacheWrite at
    // their own discounted/premium rates. Storing the inclusive prompt_tokens
    // here would double-charge the cached subset.
    val miss = deepseekMiss ?: (prompt - cached - cacheWrite).coerceAtLeast(0)
    return Usage(
        inputTokens = miss,
        outputTokens = u.int("completion_tokens") ?: 0,
        reasoningTokens = (u["completion_tokens_details"] as? JsonObject)?.int("reasoning_tokens") ?: 0,
        cacheReadTokens = cached,
        cacheWriteTokens = cacheWrite,
        // OpenRouter reports the actual charge for the completion under `cost`
        // (USD). Preserve it so the normalized usage carries the real amount even
        // when the caller has no local pricing for the model.
        costUsd = u["cost"].num() ?: 0.0,
    )
}

private fun mapFinish(reason: String): FinishReason = when (reason) {
    "tool_calls" -> FinishReason.TOOL_CALLS
    "stop" -> FinishReason.STOP
    "length" -> FinishReason.LENGTH
    "content_filter" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.UNKNOWN
}

/**
 * Normalize the in-band error shapes an OpenAI-compatible stream can carry into
 * one structured failure: a top-level `error` object, `{"type":"error"}`, or a
 * Responses-style `response.failed`. Returns null for ordinary data frames.
 */
private fun parseError(root: JsonObject): ProviderEvent.Failure? {
    val errorObj = root["error"] as? JsonObject
    val response = root["response"] as? JsonObject
    val rootType = root.str("type")
    val responseType = response?.str("type")
    val isError = errorObj != null ||
        rootType == "error" ||
        rootType == "response.failed" ||
        responseType == "response.failed" ||
        (response != null && response.str("status") == "failed")
    if (!isError) return null

    val source = errorObj
        ?: (response?.get("error") as? JsonObject)
        ?: response
        ?: root
    val message = source.str("message")
        ?: root.str("message")
        ?: errorObj?.str("type")
        ?: rootType
        ?: "OpenAI stream error"
    val code = source.str("code")
        ?: source.str("type")
        ?: root.str("code")
        ?: rootType
    val retryable = when (code?.lowercase()) {
        "insufficient_quota", "usage_not_included", "invalid_prompt" -> false
        "server_is_overloaded", "server_error" -> true
        else -> null
    }
    return ProviderEvent.Failure(
        message = message,
        retryable = retryable,
        contextOverflow = ProviderErrors.isContextOverflow(message),
    )
}

/**
 * Parse a `Retry-After` value into milliseconds. A bare number is interpreted as
 * seconds unless [isMillis] is set (the `retry-after-ms` header carries ms).
 */
private fun parseRetryAfter(value: String?, isMillis: Boolean = false): Long? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val number = raw.toDoubleOrNull() ?: return null
    if (!number.isFinite() || number < 0.0) return null
    return if (isMillis) number.toLong() else (number * 1000.0).toLong()
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonElement?.primitiveString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
