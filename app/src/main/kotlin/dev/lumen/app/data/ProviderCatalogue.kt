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

    fun providers(provider: String, key: String): List<Provider> {
        val ua = USER_AGENT
        return listOf(
            dev.spindle.provider.openai.OpenAiProvider(
                baseUrl = "https://opencode.ai/zen/go/v1",
                apiKey = key, id = "opencode-go", userAgent = ua,
                defaultModels = listOf(
                    ModelInfo("opencode-go", "deepseek-v4.1-flash", contextWindow = 1_000_000, supportsReasoning = true),
                    ModelInfo("opencode-go", "glm-5.3-flash", contextWindow = 200_000),
                    ModelInfo("opencode-go", "kimi-k2.7-code", contextWindow = 200_000),
                ),
            ),
            dev.spindle.provider.openai.OpenAiProvider(
                baseUrl = "https://api.deepseek.com",
                apiKey = key, id = "deepseek", userAgent = ua,
                defaultModels = listOf(
                    ModelInfo("deepseek", "deepseek-flash", contextWindow = 1_000_000, supportsReasoning = true),
                    ModelInfo("deepseek", "deepseek-v4-pro", contextWindow = 1_000_000, supportsReasoning = true),
                ),
            ),
            dev.spindle.provider.openai.OpenAiProvider(
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = key, id = "openrouter", userAgent = ua,
                // Fallback catalog so resolution always succeeds even if the live
                // /models fetch is unavailable. `openrouter/free` is the router
                // that auto-selects a currently-free model.
                defaultModels = listOf(
                    ModelInfo("openrouter", "openrouter/free", label = "OpenRouter Free (auto)", contextWindow = 128_000),
                    ModelInfo("openrouter", "openrouter/auto", label = "OpenRouter Auto", contextWindow = 200_000),
                    ModelInfo("openrouter", "openrouter/pareto-code", label = "OpenRouter Pareto Code", contextWindow = 200_000),
                ),
            ),
        )
    }

    /** The providers a user can pick on the key screen. */
    val choices: List<Pair<String, String>> = listOf(
        "opencode-go" to "OpenCode Go",
        "deepseek" to "DeepSeek",
        "openrouter" to "OpenRouter",
    )
}
