package dev.spindle.core.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class SimpleProviderRegistryTest {

    private class FakeProvider(
        override val id: String,
        private val listed: List<ModelInfo>,
    ) : Provider {
        override suspend fun models(): List<ModelInfo> = listed
        override fun stream(request: ChatRequest): Flow<ProviderEvent> = flowOf()
    }

    /** Counts how often [models] is hit so a refetch would be observable. */
    private class CountingProvider(
        override val id: String,
        private val listed: List<ModelInfo>,
    ) : Provider {
        var modelsCalls = 0
            private set

        override suspend fun models(): List<ModelInfo> {
            modelsCalls++
            return listed
        }

        override fun stream(request: ChatRequest): Flow<ProviderEvent> = flowOf()
    }

    @Test
    fun `resolve matches a model id that itself contains slashes`() = runTest {
        val openrouter = FakeProvider(
            id = "openrouter",
            listed = listOf(
                ModelInfo(providerId = "openrouter", id = "openrouter/auto"),
                ModelInfo(providerId = "openrouter", id = "anthropic/x"),
            ),
        )
        val registry = SimpleProviderRegistry(listOf(openrouter))

        // The slug "openrouter/auto" IS the model id; it must win over the
        // provider/model split even though it also contains a slash.
        val full = registry.resolve("openrouter/auto")
        assertSame(openrouter, full?.first)
        assertEquals("openrouter/auto", full?.second?.id)

        // A provider whose model ids are themselves namespaced resolves the
        // full, provider-prefixed ref by splitting on the FIRST slash only.
        val nested = registry.resolve("openrouter/anthropic/x")
        assertSame(openrouter, nested?.first)
        assertEquals("anthropic/x", nested?.second?.id)
    }

    @Test
    fun `resolve finds a bare model id through the cache`() = runTest {
        val openrouter = FakeProvider(
            id = "openrouter",
            listed = listOf(ModelInfo(providerId = "openrouter", id = "anthropic/x")),
        )
        val registry = SimpleProviderRegistry(listOf(openrouter))

        val resolved = registry.resolve("anthropic/x")
        assertSame(openrouter, resolved?.first)
        assertEquals("anthropic/x", resolved?.second?.id)
    }

    @Test
    fun `resolve returns null for unknown refs`() = runTest {
        val openrouter = FakeProvider(
            id = "openrouter",
            listed = listOf(ModelInfo(providerId = "openrouter", id = "openrouter/auto")),
        )
        val registry = SimpleProviderRegistry(listOf(openrouter))

        assertNull(registry.resolve("openrouter/missing"))
        assertNull(registry.resolve("nope/whatever"))
        assertNull(registry.resolve("totally-unknown"))
    }

    @Test
    fun `models skips a model whose providerId is not hosted without throwing`() = runTest {
        // Regression: the old `provider(m.providerId)!!` threw an NPE here.
        val host = FakeProvider(
            id = "openrouter",
            listed = listOf(ModelInfo(providerId = "ghost", id = "ghost/model")),
        )
        val registry = SimpleProviderRegistry(listOf(host))

        val models = registry.models()
        assertEquals(listOf("ghost/model"), models.map { it.id })
        assertNull(registry.resolve("ghost/model"))
    }

    @Test
    fun `resolve does not refetch a provider after models are cached`() = runTest {
        val provider = CountingProvider(
            id = "counted",
            listed = listOf(ModelInfo(providerId = "counted", id = "m1")),
        )
        val registry = SimpleProviderRegistry(listOf(provider))

        registry.models()
        assertEquals(1, provider.modelsCalls)

        // A split ref absent from the cached snapshot is genuinely unknown: it
        // must not trigger another provider fetch (the fallback used to call
        // p.models() directly, bypassing the registry cache).
        assertNull(registry.resolve("counted/missing"))
        assertNull(registry.resolve("counted/also-missing"))
        assertEquals(1, provider.modelsCalls)
    }
}
