package dev.lumen.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
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
}
