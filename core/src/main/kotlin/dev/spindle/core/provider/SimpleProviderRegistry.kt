package dev.spindle.core.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Default registry. Resolves `provider/model`, or a bare model id against all providers. */
class SimpleProviderRegistry(private val providers: List<Provider>) : ProviderRegistry {
    private val byId = providers.associateBy { it.id }
    private val mutex = Mutex()
    private var cache: List<ModelInfo>? = null
    private val byModelId = HashMap<String, Pair<Provider, ModelInfo>>()

    override fun provider(id: String): Provider? = byId[id]
    override fun all(): List<Provider> = providers

    override suspend fun models(): List<ModelInfo> {
        mutex.withLock {
            cache?.let { return it }
            val all = providers.flatMap { it.models() }
            for (m in all) {
                // A provider may report a model whose providerId we do not host;
                // skip it rather than NPE (models() must never throw here).
                val p = provider(m.providerId) ?: continue
                byModelId.putIfAbsent(m.id, p to m)
            }
            cache = all
            return all
        }
    }

    override suspend fun resolve(modelRef: String): Pair<Provider, ModelInfo>? {
        val all = models()
        // A provider may declare a model id that slashes even with a matching
        // model list, so try the full ref as the model id first — for providers
        // like OpenRouter the slug ("openrouter/auto") IS the model id. Then
        // fall back to splitting "provider/model" on the FIRST slash.
        val asModel = byModelId[modelRef]
        if (asModel != null) return asModel

        val slash = modelRef.indexOf('/')
        if (slash > 0) {
            val pid = modelRef.substring(0, slash)
            val mid = modelRef.substring(slash + 1)
            val p = byId[pid] ?: return null
            val cached = all.firstOrNull { it.providerId == pid && it.id == mid }
            if (cached != null) return p to cached
            val m = runCatching { p.models() }.getOrDefault(emptyList()).firstOrNull { it.id == mid }
            return if (m != null) p to m else null
        }
        return all.firstOrNull { it.id == modelRef }?.let { m ->
            provider(m.providerId)?.let { it to m }
        }
    }
}
