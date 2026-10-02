package dev.spindle.provider.openai

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
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaType()

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
                val mapped = data.mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val mid = o.str("id") ?: return@mapNotNull null
                    ModelInfo(
                        providerId = id,
                        id = mid,
                        label = mid,
                        contextWindow = 128_000,
                        supportsTools = true,
                        supportsReasoning = supportsReasoning(mid),
                    )
                }
                if (mapped.isEmpty()) defaultModels else mapped
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
                        terminal(ProviderEvent.Failure("OpenAI HTTP ${resp.code}: ${body ?: ""}"))
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
                        while (!done) {
                            val line = source.readUtf8Line() ?: break
                            if (line.isEmpty()) {
                                if (data.isNotEmpty()) {
                                    val frame = data.toString()
                                    data.setLength(0)
                                    if (frame == "[DONE]") done = true
                                    else state.handle(frame, emitEvent, emitFinish)
                                }
                                continue
                            }
                            if (line.startsWith(":") || !line.startsWith("data:")) continue
                            val payloadLine = line.substring(5)
                                .let { if (it.startsWith(" ")) it.substring(1) else it }
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(payloadLine)
                        }
                        if (!done && data.isNotEmpty()) {
                            val frame = data.toString()
                            if (frame == "[DONE]") done = true
                            else state.handle(frame, emitEvent, emitFinish)
                        }
                        if (!state.sawFinish) {
                            state.closeToolCalls(emitEvent)
                            emitFinish(FinishReason.UNKNOWN)
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
    private var emittedReasoning = ""

    var sawFinish = false
        private set

    fun handle(frame: String, emit: (ProviderEvent) -> Unit, finish: (FinishReason) -> Unit) {
        val root = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return

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
            emit(ProviderEvent.UsageEvent(parseUsage(it)))
        }
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
            value == emittedReasoning -> Unit
            value.startsWith(emittedReasoning) -> {
                val suffix = value.substring(emittedReasoning.length)
                if (suffix.isNotEmpty()) {
                    emit(ProviderEvent.ReasoningDelta(suffix))
                    emittedReasoning = value
                }
            }
            else -> {
                emit(ProviderEvent.ReasoningDelta(value))
                emittedReasoning += value
            }
        }
    }
}

private fun parseUsage(u: JsonObject): Usage = Usage(
    inputTokens = u.int("prompt_tokens") ?: 0,
    outputTokens = u.int("completion_tokens") ?: 0,
    reasoningTokens = (u["completion_tokens_details"] as? JsonObject)?.int("reasoning_tokens") ?: 0,
    cacheReadTokens = (u["prompt_tokens_details"] as? JsonObject)?.int("cached_tokens") ?: 0,
)

private fun mapFinish(reason: String): FinishReason = when (reason) {
    "tool_calls" -> FinishReason.TOOL_CALLS
    "stop" -> FinishReason.STOP
    "length" -> FinishReason.LENGTH
    "content_filter" -> FinishReason.CONTENT_FILTER
    else -> FinishReason.UNKNOWN
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull

private fun JsonElement?.primitiveString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content
