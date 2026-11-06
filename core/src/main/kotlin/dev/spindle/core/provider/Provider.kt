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
    /**
     * Whether the zero prices above were actually *reported* rather than simply
     * absent. A cost of `0.0` means two very different things:
     *
     *  - `pricingKnown = true`  -> the provider says this model is genuinely free
     *    (e.g. OpenRouter's `openrouter/free`).
     *  - `pricingKnown = false` -> nobody told us a price; it is *unknown*, and
     *    the UI must not claim it is free.
     *
     * Defaults to false so an un-annotated model reads as "unknown" rather than
     * silently asserting it costs nothing.
     */
    val pricingKnown: Boolean = false,
) {
    /**
     * True only when a price was reported AND it is zero — a genuinely free
     * model. Unknown pricing is not free.
     */
    val isFree: Boolean
        get() = pricingKnown && inputCostPerM <= 0.0 && outputCostPerM <= 0.0
}

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
    data class Failure(
        val message: String,
        val cause: Throwable? = null,
        val statusCode: Int? = null,
        val retryAfterMs: Long? = null,
        val retryable: Boolean? = null,
        val contextOverflow: Boolean = false,
    ) : ProviderEvent
}

/**
 * Structured classification of provider error text/status, so the agent loop can
 * treat a context-window overflow as a compaction trigger rather than a hard
 * terminal failure.
 */
object ProviderErrors {
    private val CONTEXT_OVERFLOW_MARKERS = listOf(
        "context length",
        "context_length_exceeded",
        "maximum context length",
        "too many tokens",
        "prompt is too long",
    )

    fun isContextOverflow(message: String, statusCode: Int? = null): Boolean {
        if (statusCode == 413) return true
        val m = message.lowercase()
        return CONTEXT_OVERFLOW_MARKERS.any { it in m }
    }

    /**
     * Providers sometimes state the wait in the error **body** rather than in a
     * `Retry-After` header — e.g. OpenCode Go's capacity rejections say
     * "the cell is at capacity; retry shortly (after 30 s)". Reading only the
     * header threw that away and fell back to our own sub-second backoff, which
     * hammered an already-full cell and burned the whole attempt budget in ~30s
     * of needless retries.
     *
     * Recognises "after 30 s", "after 30s", "after 1 minute", "retry in 30
     * seconds". Returns milliseconds, or null when none is stated. Bounded to
     * 5 minutes so a garbled body cannot stall a run indefinitely.
     */
    private val RETRY_HINT: Regex =
        Regex("(?:after|retry (?:in|after))\\s+([0-9]+(?:\\.[0-9]+)?)\\s*(ms|milliseconds?|s|secs?|seconds?|m|mins?|minutes?)", RegexOption.IGNORE_CASE)

    fun retryHintFrom(message: String?): Long? {
        if (message.isNullOrBlank()) return null
        val m = RETRY_HINT.find(message) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        if (!value.isFinite() || value <= 0.0) return null
        val unit = m.groupValues[2].lowercase()
        val ms = when {
            unit.startsWith("ms") || unit.startsWith("milli") -> value
            unit == "m" || unit.startsWith("min") -> value * 60_000.0
            else -> value * 1_000.0
        }
        return ms.toLong().coerceIn(1L, 300_000L)
    }
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
