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
            for (m in all) byModelId.putIfAbsent(m.id, provider(m.providerId)!! to m)
            cache = all
            return all
        }
    }

    override suspend fun resolve(modelRef: String): Pair<Provider, ModelInfo>? {
        models()
        val slash = modelRef.indexOf('/')
        if (slash > 0) {
            val pid = modelRef.substring(0, slash)
            val mid = modelRef.substring(slash + 1)
            val p = byId[pid] ?: return null
            val m = runCatching { p.models() }.getOrDefault(emptyList()).firstOrNull { it.id == mid }
            return if (m != null) p to m else null
        }
        return byModelId[modelRef]
    }
}
