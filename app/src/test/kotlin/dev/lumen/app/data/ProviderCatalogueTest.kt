package dev.lumen.app.data

import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.SimpleProviderRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The bug this exists to prevent: the app saved a key, then died with
 * "Unknown model: openrouter/…" because the default model string was not in the
 * provider's catalogue. Every provider a user can pick MUST resolve its own
 * default model offline — the live /models fetch may fail.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProviderCatalogueTest {

    @Test
    fun `every provider resolves its own default model without the network`() = runBlocking {
        for ((provider, label) in ProviderCatalogue.choices) {
            val default = KeyStore.defaultModel(provider)
            val registry = ProviderCatalogue.registry(provider, key = "")
            val resolved = registry.resolve(default)
            assertNotNull(resolved, "'$label' default '$default' did not resolve — this is the Unknown model bug")
            assertTrue(resolved.first.id == provider, "resolved provider ${resolved.first.id} != $provider for '$default'")
        }
    }

    @Test
    fun `the provider part of a default model matches the chosen provider`() {
        for ((provider, _) in ProviderCatalogue.choices) {
            val model = KeyStore.defaultModel(provider)
            assertTrue(model.startsWith("$provider/"), "'$model' should start with '$provider/'")
        }
    }

    @Test
    fun `openrouter defaults to the free router`() {
        val m = KeyStore.defaultModel("openrouter")
        assertTrue(m.endsWith("openrouter/free"), "expected the free router, got $m")
    }

    @Test
    fun `each provider offers at least one tool-capable model with a context window`() = runBlocking {
        for ((provider, _) in ProviderCatalogue.choices) {
            val reg = ProviderCatalogue.registry(provider, "")
            val models = reg.models()
            assertTrue(models.isNotEmpty(), "$provider exposed no models")
            assertTrue(models.all { it.contextWindow > 0 }, "$provider has a model with no context window")
        }
    }

    @Test
    fun `only the selected provider is constructed`() {
        for ((provider, _) in ProviderCatalogue.choices) {
            val built = ProviderCatalogue.providers(provider, key = "secret")
            assertTrue(
                built.all { it.id == provider },
                "selected '$provider' also built ${built.map { it.id }} — the key must not span providers",
            )
            assertTrue(built.map { it.id } == listOf(provider))
        }
    }

    @Test
    fun `catalogue models carry pricing so the cost budget can fire`() = runBlocking {
        for ((provider, _) in ProviderCatalogue.choices) {
            val models = ProviderCatalogue.registry(provider, "").models()
            assertTrue(
                models.all { it.inputCostPerM >= 0.0 && it.outputCostPerM >= 0.0 },
                "$provider has a model with negative pricing",
            )
        }
        val pro = ProviderCatalogue.defaultModels("deepseek").first { it.id == "deepseek-v4-pro" }
        assertTrue(pro.outputCostPerM > 0.0, "deepseek-v4-pro needs a non-zero price or the budget is dead")
    }

    @Test
    fun `mergeModels lets live override provided fields and keeps embedded fallbacks`() {
        val embedded = listOf(
            ModelInfo(
                "p", "m", label = "Embedded label", contextWindow = 200_000,
                supportsReasoning = true, inputCostPerM = 1.0, outputCostPerM = 2.0,
                cacheReadCostPerM = 0.1,
            ),
        )
        val live = listOf(
            ModelInfo("p", "m", contextWindow = 64_000, supportsVision = true, inputCostPerM = 0.5),
        )

        val merged = ProviderCatalogue.mergeModels(embedded, live).single()
        assertEquals(64_000, merged.contextWindow, "live provided a context window")
        assertEquals(0.5, merged.inputCostPerM, 1e-9)
        assertTrue(merged.supportsVision, "live provided vision")
        assertEquals("Embedded label", merged.label, "live omitted the label; embedded fills it")
        assertEquals(2.0, merged.outputCostPerM, 1e-9)
        assertEquals(0.1, merged.cacheReadCostPerM, 1e-9)
        assertTrue(merged.supportsReasoning, "embedded reasoning flag must survive")
    }

    @Test
    fun `mergeModels keeps an unknown live id usable and resolvable`() = runBlocking {
        val embedded = ProviderCatalogue.defaultModels("deepseek")
        val live = embedded + ModelInfo(
            "deepseek", "deepseek-future", contextWindow = 500_000, inputCostPerM = 1.0,
        )

        val merged = ProviderCatalogue.mergeModels(embedded, live)
        assertTrue(merged.any { it.id == "deepseek-future" }, "unknown live id must survive the merge")
        assertTrue(merged.any { it.id == "deepseek-v4-pro" }, "embedded ids must survive the merge")

        val registry = SimpleProviderRegistry(listOf(FakeProvider("deepseek", merged)))
        val resolved = registry.resolve("deepseek/deepseek-future")
        assertNotNull(resolved, "a merged live id must still resolve")
        assertEquals("deepseek-future", resolved.second.id)
    }

    @Test
    fun `mergeModels ignores a blank live label and keeps the embedded one`() {
        val embedded = listOf(ModelInfo("p", "m", label = "Embedded", contextWindow = 200_000))
        val live = listOf(ModelInfo("p", "m", label = ""))
        val merged = ProviderCatalogue.mergeModels(embedded, live).single()
        assertEquals("Embedded", merged.label, "a blank live label must not erase the embedded label")
    }

    @Test
    fun `mergeModels keeps the last duplicate live id`() {
        val live = listOf(
            ModelInfo("p", "dup", contextWindow = 100_000),
            ModelInfo("p", "dup", contextWindow = 300_000),
        )
        val merged = ProviderCatalogue.mergeModels(emptyList(), live)
        assertEquals(1, merged.size)
        assertEquals(300_000, merged.single().contextWindow)
    }

    @Test
    fun `defaultModels still returns the embedded snapshot offline`() {
        for ((provider, _) in ProviderCatalogue.choices) {
            val embedded = ProviderCatalogue.defaultModels(provider)
            assertTrue(embedded.isNotEmpty(), "$provider has no embedded snapshot")
            assertTrue(embedded.all { it.providerId == provider }, "$provider snapshot leaks another provider")
        }
    }

    @Test
    fun `embedded snapshot is versioned`() {
        assertTrue(ProviderCatalogue.SNAPSHOT_VERSION >= 1)
    }

    private class FakeProvider(
        override val id: String,
        private val entries: List<ModelInfo>,
    ) : Provider {
        override suspend fun models(): List<ModelInfo> = entries
        override fun stream(request: ChatRequest): Flow<ProviderEvent> = emptyFlow()
    }
}
