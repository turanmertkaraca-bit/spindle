package dev.spindle.cli

import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.provider.anthropic.AnthropicProvider
import dev.spindle.provider.openai.OpenAiProvider
import dev.spindle.provider.openai.OpenCodeRouter
import dev.spindle.provider.openai.WireProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A single OpenCode provider id (Zen or Go) fronts several wire surfaces. This
 * composite exposes one [Provider] per gateway id and dispatches each request to
 * the adapter matching the model's wire format, using the pure routing table in
 * [OpenCodeRouter]:
 *
 * - Claude-family / Qwen -> the `:provider-anthropic` adapter at `/messages`
 *   (`x-api-key` + `anthropic-version`).
 * - GPT / Grok -> `/responses`, which spindle does **not** implement; an explicit
 *   `Failure` is emitted rather than sending a guessed payload.
 * - Everything else -> the existing OpenAI `/chat/completions` adapter.
 *
 * Go's mandatory `x-opencode-session` (threaded from `ChatRequest.sessionHint`)
 * and custom `User-Agent` are sent by both delegates on every streaming surface,
 * including `/messages`.
 *
 * The adapter instances are injectable so routing can be unit tested on the JVM
 * without a network.
 */
class OpenCodeRoutingProvider(
    override val id: String,
    baseUrl: String = OpenCodeRouter.baseUrlFor(id)
        ?: throw IllegalArgumentException("not an OpenCode provider: $id"),
    apiKey: String,
    userAgent: String,
    defaultModels: List<ModelInfo> = emptyList(),
    private val chat: Provider = OpenAiProvider(
        baseUrl = baseUrl,
        apiKey = apiKey,
        id = id,
        userAgent = userAgent,
        defaultModels = defaultModels,
    ),
    private val messages: Provider = AnthropicProvider(
        baseUrl = baseUrl,
        apiKey = apiKey,
        id = id,
        userAgent = userAgent,
        defaultModels = defaultModels,
        messagesPath = "/messages",
    ),
) : Provider {

    override suspend fun models(): List<ModelInfo> = chat.models().map { it.copy(providerId = id) }

    override fun stream(request: ChatRequest): Flow<ProviderEvent> {
        val route = OpenCodeRouter.route(id, request.model)
        return when (route.protocol) {
            WireProtocol.CHAT_COMPLETIONS -> chat.stream(request)
            WireProtocol.MESSAGES -> messages.stream(request)
            WireProtocol.RESPONSES -> flowOf(
                ProviderEvent.Failure(
                    "OpenCode $id: model '${request.model}' targets the /responses surface, " +
                        "which spindle does not implement. Refusing to guess the wire format; " +
                        "use a /messages or /chat/completions model instead.",
                ),
            )
        }
    }
}
