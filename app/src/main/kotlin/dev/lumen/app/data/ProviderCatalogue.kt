package dev.lumen.app.data

import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.SimpleProviderRegistry

/**
 * Where the provider configs live, so both the app and its tests use the exact
 * same ones. Every provider here MUST be able to resolve its own default model —
 * `ProviderCatalogueTest` fails the build if one cannot.
 */
object ProviderCatalogue {

    const val USER_AGENT = "lumen/0.1"

    fun registry(provider: String, key: String): SimpleProviderRegistry =
        SimpleProviderRegistry(providers(provider, key))

    /** Base URL per provider id. */
    private val baseUrls: Map<String, String> = linkedMapOf(
        "opencode-go" to "https://opencode.ai/zen/go/v1",
        "deepseek" to "https://api.deepseek.com",
        "openrouter" to "https://openrouter.ai/api/v1",
    )

    private val opencodeGoModels = listOf(
        ModelInfo("opencode-go", "deepseek-v4.1-flash", contextWindow = 1_000_000, supportsReasoning = true),
        ModelInfo("opencode-go", "glm-5.3-flash", contextWindow = 200_000),
        ModelInfo("opencode-go", "kimi-k2.7-code", contextWindow = 200_000),
    )
    private val deepseekModels = listOf(
        ModelInfo("deepseek", "deepseek-flash", contextWindow = 1_000_000, supportsReasoning = true),
        ModelInfo("deepseek", "deepseek-v4-pro", contextWindow = 1_000_000, supportsReasoning = true),
    )

    // Fallback catalog so resolution always succeeds even if the live /models
    // fetch is unavailable. `openrouter/free` auto-selects a currently-free model.
    private val openrouterModels = listOf(
        ModelInfo("openrouter", "openrouter/free", label = "OpenRouter Free (auto)", contextWindow = 128_000),
        ModelInfo("openrouter", "openrouter/auto", label = "OpenRouter Auto", contextWindow = 200_000),
        ModelInfo("openrouter", "openrouter/pareto-code", label = "OpenRouter Pareto Code", contextWindow = 200_000),
    )

    /** The embedded model catalog for a provider — resolves offline, always. */
    fun defaultModels(provider: String): List<ModelInfo> = when (provider) {
        "deepseek" -> deepseekModels
        "openrouter" -> openrouterModels
        else -> opencodeGoModels
    }

    /** Human label for a provider id. */
    fun label(provider: String): String =
        choices.firstOrNull { it.first == provider }?.second ?: provider

    fun providers(provider: String, key: String): List<Provider> {
        val ua = USER_AGENT
        return baseUrls.map { (id, url) ->
            dev.spindle.provider.openai.OpenAiProvider(
                baseUrl = url,
                apiKey = key, id = id, userAgent = ua,
                defaultModels = defaultModels(id),
            )
        }
    }

    /** The providers a user can pick on the key screen. */
    val choices: List<Pair<String, String>> = listOf(
        "opencode-go" to "OpenCode Go",
        "deepseek" to "DeepSeek",
        "openrouter" to "OpenRouter",
    )
}
