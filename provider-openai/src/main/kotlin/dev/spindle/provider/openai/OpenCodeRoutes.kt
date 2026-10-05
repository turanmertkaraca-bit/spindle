package dev.spindle.provider.openai

/**
 * Per-model wire routing for the OpenCode Zen/Go gateways.
 *
 * Zen and Go each expose three surfaces and select one **per model** (see
 * `docs/PROVIDERS.md` and `docs/SPEC.md` §4). This file is deliberately a pure,
 * side-effect-free table: it performs no IO and holds no keys, so the routing
 * decision can be unit tested without a network. The host (`:cli`) turns a
 * [RouteSpec] into a concrete adapter and supplies auth material at request time.
 */

/** The wire shape a model speaks on the OpenCode gateways. */
enum class WireProtocol {
    /** OpenAI `POST /chat/completions` streaming (SSE). */
    CHAT_COMPLETIONS,

    /** Anthropic `POST /messages` streaming (SSE). */
    MESSAGES,

    /** OpenAI `POST /responses` streaming (SSE). Not implemented (see report). */
    RESPONSES,
}

/** How the gateway expects the credential for a surface. */
enum class AuthStyle {
    /** `Authorization: Bearer <key>` — `/chat/completions` and `/responses`. */
    BEARER,

    /** `x-api-key: <key>` + `anthropic-version` — `/messages`. */
    X_API_KEY,
}

/**
 * A fully-resolved routing decision for one (provider, model) pair.
 *
 * [baseUrl] plus [path] is the exact streaming endpoint. [protocol] selects the
 * adapter; [auth] selects the credential header. The flags describe the
 * OpenCode Go hard requirements so callers (and tests) can assert them without
 * inspecting adapter internals.
 */
data class RouteSpec(
    val protocol: WireProtocol,
    val baseUrl: String,
    val path: String,
    val auth: AuthStyle,
    val extraHeaders: Map<String, String> = emptyMap(),
    /** Go requires `x-opencode-session: <sessionHint>` on every inference request. */
    val requiresSessionHeader: Boolean = false,
    /** Go requires a stable, non-default `User-Agent` on every request. */
    val requiresCustomUserAgent: Boolean = false,
)

object OpenCodeRouter {
    const val ZEN_PROVIDER_ID = "opencode"
    const val GO_PROVIDER_ID = "opencode-go"

    const val ZEN_BASE_URL = "https://opencode.ai/zen/v1"
    const val GO_BASE_URL = "https://opencode.ai/zen/go/v1"

    /**
     * Model-id token used for classification. Splitting on any non-alphanumeric
     * run means `claude-sonnet-4`, `openrouter/anthropic/claude-3.5` and
     * `qwen3.7-max` all classify on their leading vendor token rather than on an
     * incidental substring.
     */
    private val TOKEN_SPLIT = Regex("[^a-z0-9]+")

    /** OpenAI reasoning families that are only served over `/responses`. */
    private val RESPONSES_TOKENS = setOf("gpt", "grok")

    /** Anthropic-style families served over `/messages`. */
    private val MESSAGES_TOKENS = setOf("claude", "qwen", "anthropic")

    /** The gateway base URL for a known OpenCode provider id, or null otherwise. */
    fun baseUrlFor(providerId: String): String? = when (providerId) {
        ZEN_PROVIDER_ID -> ZEN_BASE_URL
        GO_PROVIDER_ID -> GO_BASE_URL
        else -> null
    }

    /**
     * Classify by model id per the documented intent: Claude-family and Qwen go
     * to `/messages`; GPT and Grok go to `/responses`; everything else keeps the
     * existing `/chat/completions` behaviour.
     */
    fun wireFor(modelId: String): WireProtocol {
        val tokens = modelId.lowercase().split(TOKEN_SPLIT).filter { it.isNotEmpty() }
        // `startsWith` catches versioned family ids such as `qwen3.7-max` and
        // `grok4` while `+` sharing a prefix with an unrelated token is unlikely
        // for these vendor names.
        fun family(names: Set<String>) = tokens.any { t -> names.any { t == it || t.startsWith(it) } }
        return when {
            family(MESSAGES_TOKENS) -> WireProtocol.MESSAGES
            family(RESPONSES_TOKENS) -> WireProtocol.RESPONSES
            else -> WireProtocol.CHAT_COMPLETIONS
        }
    }

    /**
     * Resolve the route for [modelId] on [providerId].
     *
     * @throws IllegalArgumentException when [providerId] is not an OpenCode provider.
     */
    fun route(providerId: String, modelId: String): RouteSpec {
        val base = baseUrlFor(providerId)
            ?: throw IllegalArgumentException("not an OpenCode provider: $providerId")
        val go = providerId == GO_PROVIDER_ID
        val wire = wireFor(modelId)
        return when (wire) {
            WireProtocol.CHAT_COMPLETIONS -> RouteSpec(
                protocol = wire,
                baseUrl = base,
                path = "/chat/completions",
                auth = AuthStyle.BEARER,
                requiresSessionHeader = go,
                requiresCustomUserAgent = go,
            )
            WireProtocol.MESSAGES -> RouteSpec(
                protocol = wire,
                baseUrl = base,
                path = "/messages",
                auth = AuthStyle.X_API_KEY,
                requiresSessionHeader = go,
                requiresCustomUserAgent = go,
            )
            WireProtocol.RESPONSES -> RouteSpec(
                protocol = wire,
                baseUrl = base,
                path = "/responses",
                auth = AuthStyle.BEARER,
                requiresSessionHeader = go,
                requiresCustomUserAgent = go,
            )
        }
    }
}
