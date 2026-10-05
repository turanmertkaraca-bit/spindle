package dev.spindle.core.provider

import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.ToolCall
import dev.spindle.core.model.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * A model available from a provider. Capability flags drive adapter behaviour
 * and UI affordances, so we never send `tools` to a model that can't take them.
 */
@Serializable
data class ModelInfo(
    val providerId: String,
    val id: String,
    val label: String = id,
    val contextWindow: Int = 128_000,
    val maxOutputTokens: Int = 8_192,
    val supportsTools: Boolean = true,
    val supportsReasoning: Boolean = false,
    val supportsVision: Boolean = false,
    val inputCostPerM: Double = 0.0,
    val outputCostPerM: Double = 0.0,
    val cacheReadCostPerM: Double = 0.0,
    val cacheWriteCostPerM: Double = 0.0,
)

/** An inline image travelling as base64; adapters render it per provider shape. */
data class WireImage(
    val mime: String,
    val base64: String,
)

/** Provider-neutral message handed to an adapter. */
data class WireMessage(
    val role: String, // system | user | assistant | tool
    val text: String? = null,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val images: List<WireImage> = emptyList(),
)

data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema object as a string, e.g. {"type":"object","properties":{...}} */
    val parametersJson: String,
)

/**
 * Opt-in structured output request.
 *
 * Targets the **OpenAI-compatible `chat/completions` surface only** (see
 * `docs/CAPABILITIES.md` §1.1, `docs/SPEC.md` §13). Adapters that lack a
 * structured-output shape (e.g. `:provider-anthropic`) ignore this entirely; it
 * is never translated into a provider-specific equivalent.
 *
 * [schemaJson] is raw JSON Schema text, parsed by the adapter at request time.
 */
@Serializable
data class ResponseFormat(
    /** Value sent as `response_format.json_schema.name`. */
    val name: String,
    /** JSON Schema as text, e.g. `{"type":"object","properties":{...}}`. */
    val schemaJson: String,
    /** `strict` mode flag; defaults on. */
    val strict: Boolean = true,
)

data class ChatRequest(
    val model: String,
    val system: String,
    val messages: List<WireMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** Hint for reasoning models; adapters that don't support it ignore it. */
    val reasoningEffort: String? = null,
    /** Enable explicit thinking mode where the provider supports it (DeepSeek). */
    val thinking: Boolean? = null,
    /** Stable per-conversation id; some gateways (OpenCode Go) require it. */
    val sessionHint: String? = null,
    /**
     * Opt-in structured output. Null (the default) leaves the request body
     * unchanged for every adapter; OpenAI-compatible adapters emit
     * `response_format: {type: json_schema, ...}` only when this is set.
     */
    val responseFormat: ResponseFormat? = null,
)

/** Normalized stream events every adapter must produce. */
sealed interface ProviderEvent {
    data class TextDelta(val text: String) : ProviderEvent
    data class ReasoningDelta(val text: String) : ProviderEvent
    data class ToolCallStart(val index: Int, val id: String, val name: String) : ProviderEvent
    data class ToolCallArgsDelta(val index: Int, val argsDelta: String) : ProviderEvent
    data class ToolCallEnd(val index: Int) : ProviderEvent
    data class UsageEvent(val usage: Usage) : ProviderEvent
    data class Finished(val reason: FinishReason) : ProviderEvent
    data class Failure(val message: String, val cause: Throwable? = null) : ProviderEvent
}

interface Provider {
    val id: String
    suspend fun models(): List<ModelInfo>
    fun stream(request: ChatRequest): Flow<ProviderEvent>
}

interface ProviderRegistry {
    fun provider(id: String): Provider?
    fun all(): List<Provider>
    suspend fun models(): List<ModelInfo>
    /** Resolve a "provider/model" or bare model id to a concrete (provider, model). */
    suspend fun resolve(modelRef: String): Pair<Provider, ModelInfo>?
}
