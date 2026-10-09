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

    /**
     * Version of the embedded offline snapshot below. Bump whenever a shipped
     * snapshot refresh changes the fallback catalogue, so a stale on-device copy
     * can be told apart from a newer build. The embedded lists are the models.dev
     * snapshot equivalent: hand-curated, always resolvable without the network.
     */
    const val SNAPSHOT_VERSION = 1

    fun registry(provider: String, key: String): SimpleProviderRegistry =
        SimpleProviderRegistry(providers(provider, key))

    /** Base URL per provider id. */
    private val baseUrls: Map<String, String> = linkedMapOf(
        "opencode-go" to "https://opencode.ai/zen/go/v1",
        "deepseek" to "https://api.deepseek.com",
        "openrouter" to "https://openrouter.ai/api/v1",
    )

    // Embedded snapshot (SNAPSHOT_VERSION) per provider. These lists are handed
    // to the provider adapter, which overlays advertised live fields at the JSON
    // level and always preserves these ids — so they remain the offline fallback.
    private val opencodeGoModels = listOf(
        ModelInfo(
            "opencode-go", "deepseek-v4.1-flash", contextWindow = 1_000_000, supportsReasoning = true,
            inputCostPerM = 0.28, outputCostPerM = 0.42, cacheReadCostPerM = 0.028,
        ),
        ModelInfo(
            "opencode-go", "glm-5.3-flash", contextWindow = 200_000,
            inputCostPerM = 0.10, outputCostPerM = 0.10, cacheReadCostPerM = 0.02,
        ),
        ModelInfo(
            "opencode-go", "kimi-k2.7-code", contextWindow = 200_000,
            inputCostPerM = 0.55, outputCostPerM = 2.20, cacheReadCostPerM = 0.15,
        ),
    )
    private val deepseekModels = listOf(
        ModelInfo(
            "deepseek", "deepseek-flash", contextWindow = 1_000_000, supportsReasoning = true,
            inputCostPerM = 0.28, outputCostPerM = 0.42, cacheReadCostPerM = 0.028,
        ),
        ModelInfo(
            "deepseek", "deepseek-v4-pro", contextWindow = 1_000_000, supportsReasoning = true,
            inputCostPerM = 0.55, outputCostPerM = 2.19, cacheReadCostPerM = 0.14,
        ),
    )

    // Fallback catalog so resolution always succeeds even if the live /models
    // fetch is unavailable. `openrouter/free` auto-selects a currently-free model.
    private val openrouterModels = listOf(
        ModelInfo(
            "openrouter", "openrouter/free", label = "OpenRouter Free (auto)", contextWindow = 128_000,
            inputCostPerM = 0.0, outputCostPerM = 0.0,
        ),
        ModelInfo(
            "openrouter", "openrouter/auto", label = "OpenRouter Auto", contextWindow = 200_000,
            inputCostPerM = 0.5, outputCostPerM = 1.5,
        ),
        ModelInfo(
            "openrouter", "openrouter/pareto-code", label = "OpenRouter Pareto Code", contextWindow = 200_000,
            inputCostPerM = 0.5, outputCostPerM = 1.5,
        ),
    )

    /** The embedded model catalog for a provider — resolves offline, always. */
    fun defaultModels(provider: String): List<ModelInfo> = when (provider) {
        "deepseek" -> deepseekModels
        "openrouter" -> openrouterModels
        else -> opencodeGoModels
    }

    /**
     * Merge a live [ModelInfo] list over the embedded snapshot by id. Live wins
     * for fields it actually provides, embedded fills the rest; ids only in
     * [live] are appended (so a newer model stays usable) and ids only in
     * [embedded] survive (so offline resolution never regresses).
     *
     * Context window and max output take the live value whenever it is positive,
     * so a real provider limit (including exactly 128_000) always wins over the
     * embedded snapshot. Costs/capabilities fall back to embedded when the live
     * value is zero/false. Callers that parsed a richer payload pass the values
     * through; the provider adapter does the authoritative presence-aware overlay
     * while it still has the raw JSON.
     */
    fun mergeModels(embedded: List<ModelInfo>, live: List<ModelInfo>): List<ModelInfo> {
        val embeddedById = embedded.associateBy { it.id }
        val merged = LinkedHashMap<String, ModelInfo>(embedded.size + live.size)
        for (m in embedded) merged[m.id] = m
        for (l in live) {
            val base = embeddedById[l.id]
            merged[l.id] = if (base == null) l else base.overlay(l)
        }
        return merged.values.toList()
    }

    /** Fill every field [live] left at its ModelInfo default from the embedded copy. */
    private fun ModelInfo.overlay(live: ModelInfo): ModelInfo = copy(
        label = live.label.takeIf { it.isNotBlank() && it != live.id } ?: label,
        // Live context/output win whenever positive. A sentinel comparison against
        // the 128_000 default would discard a real live 128k value and wrongly
        // keep an embedded 1M, which is exactly the "use the model's real limit"
        // case we must not lose.
        contextWindow = live.contextWindow.takeIf { it > 0 } ?: contextWindow,
        maxOutputTokens = live.maxOutputTokens.takeIf { it > 0 } ?: maxOutputTokens,
        supportsTools = live.supportsTools.takeIf { !it } ?: supportsTools,
        supportsReasoning = live.supportsReasoning.takeIf { it } ?: supportsReasoning,
        supportsVision = live.supportsVision.takeIf { it } ?: supportsVision,
        inputCostPerM = live.inputCostPerM.takeIf { it > 0.0 } ?: inputCostPerM,
        outputCostPerM = live.outputCostPerM.takeIf { it > 0.0 } ?: outputCostPerM,
        cacheReadCostPerM = live.cacheReadCostPerM.takeIf { it > 0.0 } ?: cacheReadCostPerM,
        cacheWriteCostPerM = live.cacheWriteCostPerM.takeIf { it > 0.0 } ?: cacheWriteCostPerM,
    )

    /** Human label for a provider id. */
    fun label(provider: String): String =
        choices.firstOrNull { it.first == provider }?.second ?: provider

    /**
     * Build the selected provider only. Previously this constructed every base
     * URL with the one key, so resolving a model dialled unrelated providers
     * (with the user's credential). Only the chosen provider is ever created.
     */
    fun providers(provider: String, key: String): List<Provider> {
        val url = baseUrls[provider]
            ?: throw IllegalArgumentException("Unknown provider: $provider")
        return listOf(
            dev.spindle.provider.openai.OpenAiProvider(
                baseUrl = url,
                apiKey = key, id = provider, userAgent = USER_AGENT,
                defaultModels = defaultModels(provider),
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
