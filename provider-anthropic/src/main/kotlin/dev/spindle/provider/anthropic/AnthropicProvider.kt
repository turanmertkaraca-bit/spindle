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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

private val JSON_MEDIA: MediaType = "application/json; charset=utf-8".toMediaType()

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

    override fun stream(request: ChatRequest): Flow<ProviderEvent> = flow {
        val payload = buildPayload(request)
        val httpReq = Request.Builder()
            .url("$root/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", version)
            .header("content-type", "application/json")
            .header("accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        var sawFinish = false
        client.newCall(httpReq).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = runCatching { resp.body?.string() }.getOrNull()
                emit(ProviderEvent.Failure("Anthropic HTTP ${resp.code}: ${body ?: ""}"))
                return@use
            }
            val source = resp.body?.source()
            if (source == null) {
                emit(ProviderEvent.Failure("Anthropic: empty response body"))
                return@use
            }
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (line.isEmpty() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val frame = line.substring(5).trim()
                if (frame.isEmpty()) continue
                val rootObj = runCatching { json.parseToJsonElement(frame).jsonObject }.getOrNull()
                    ?: continue
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
                            emit(
                                ProviderEvent.ToolCallStart(
                                    index,
                                    block.str("id") ?: "",
                                    block.str("name") ?: "",
                                ),
                            )
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
                            emit(ProviderEvent.Finished(mapStop(stop)))
                            sawFinish = true
                        }
                    }
                    "message_stop" -> {
                        if (!sawFinish) {
                            emit(ProviderEvent.Finished(FinishReason.UNKNOWN))
                            sawFinish = true
                        }
                    }
                    "error" -> {
                        val err = rootObj["error"] as? JsonObject
                        emit(ProviderEvent.Failure(err?.str("message") ?: frame))
                    }
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun buildPayload(request: ChatRequest): JsonObject = buildJsonObject {
        put("model", request.model)
        if (request.system.isNotBlank()) put("system", request.system)
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
     * requires `tool_result` blocks to precede everything else.
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
                else -> push(
                    "user",
                    listOf(buildJsonObject {
                        put("type", "text")
                        put("text", m.text ?: "")
                    }),
                )
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

private fun parseJsonOrEmpty(raw: String): JsonElement =
    runCatching { json.parseToJsonElement(raw) }.getOrElse { JsonObject(emptyMap()) }

private fun mapStop(reason: String): FinishReason = when (reason) {
    "tool_use" -> FinishReason.TOOL_CALLS
    "end_turn" -> FinishReason.STOP
    "max_tokens" -> FinishReason.LENGTH
    "stop_sequence" -> FinishReason.STOP
    else -> FinishReason.UNKNOWN
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.intOrNull
