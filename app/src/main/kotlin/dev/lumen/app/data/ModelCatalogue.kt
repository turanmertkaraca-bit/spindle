package dev.lumen.app.data

import android.content.Context
import dev.spindle.core.provider.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The live `/models` catalogue, merged over the embedded [ProviderCatalogue]
 * snapshot and cached on-device so it survives a restart. The embedded list is
 * always the floor: [models] never returns less than it, so an offline cold
 * start behaves exactly as before and a corrupt cache is silently ignored.
 */
class ModelCatalogue(
    private val keys: KeyStore,
    private val context: Context,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _refreshing = MutableStateFlow<Set<String>>(emptySet())
    val refreshing: StateFlow<Set<String>> = _refreshing.asStateFlow()

    private val _errors = MutableStateFlow<Map<String, String?>>(emptyMap())
    val errors: StateFlow<Map<String, String?>> = _errors.asStateFlow()

    /** The offline snapshot, always resolvable without the network. */
    fun embeddedModels(provider: String): List<ModelInfo> = ProviderCatalogue.defaultModels(provider)

    /**
     * The embedded snapshot merged with any cached live list. Never throws and
     * always returns at least the embedded list.
     */
    fun models(provider: String): List<ModelInfo> {
        val embedded = embeddedModels(provider)
        val cached = runCatching { decode(prefs.getString(cacheKey(provider), null)) }
            .getOrDefault(emptyList())
        return if (cached.isEmpty()) embedded else ProviderCatalogue.mergeModels(embedded, cached)
    }

    /**
     * Fetch the live `/models` list for [provider], persist it and return the
     * merge over the embedded snapshot. A blank key for a keyed provider skips
     * the request; every failure keeps the current list and records an error.
     */
    suspend fun refresh(provider: String): List<ModelInfo> {
        val embedded = embeddedModels(provider)
        if (provider != "openrouter" && keys.apiKey.isNullOrBlank()) return models(provider)

        markRefreshing(provider, true)
        return try {
            val adapter = ProviderCatalogue.providers(provider, keys.apiKey.orEmpty()).first()
            val live = withContext(Dispatchers.IO) { adapter.models() }
            persist(provider, live)
            val cleared: String? = null
            _errors.value = _errors.value + (provider to cleared)
            ProviderCatalogue.mergeModels(embedded, live)
        } catch (t: Throwable) {
            val failure: String? = t.message ?: t.toString()
            _errors.value = _errors.value + (provider to failure)
            models(provider)
        } finally {
            markRefreshing(provider, false)
        }
    }

    /**
     * Refresh the currently selected provider only. OpenRouter's catalogue is
     * public but is fetched lazily when it is actually selected, so an app start
     * with no key never opens a socket.
     */
    suspend fun refreshAll() {
        runCatching { refresh(keys.provider) }
    }

    /** Epoch millis of the last successful persist for [provider], or 0. */
    fun lastRefreshed(provider: String): Long = prefs.getLong(timeKey(provider), 0L)

    private fun markRefreshing(provider: String, on: Boolean) {
        _refreshing.value = if (on) _refreshing.value + provider else _refreshing.value - provider
    }

    /** Encode [models] for [provider] and stamp the refresh time. */
    internal fun persist(provider: String, models: List<ModelInfo>) {
        val array = JSONArray()
        for (m in models) {
            array.put(
                JSONObject()
                    .put("providerId", m.providerId)
                    .put("id", m.id)
                    .put("label", m.label)
                    .put("contextWindow", m.contextWindow)
                    .put("maxOutputTokens", m.maxOutputTokens)
                    .put("supportsTools", m.supportsTools)
                    .put("supportsReasoning", m.supportsReasoning)
                    .put("supportsVision", m.supportsVision)
                    .put("inputCostPerM", m.inputCostPerM)
                    .put("outputCostPerM", m.outputCostPerM)
                    .put("cacheReadCostPerM", m.cacheReadCostPerM)
                    .put("cacheWriteCostPerM", m.cacheWriteCostPerM),
            )
        }
        prefs.edit()
            .putString(cacheKey(provider), array.toString())
            .putLong(timeKey(provider), now())
            .apply()
    }

    /** Decode a cached blob, dropping rows without a usable id. */
    private fun decode(raw: String?): List<ModelInfo> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = JSONArray(raw)
        val out = ArrayList<ModelInfo>(array.length())
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = o.optString("id", "").trim().takeIf { it.isNotEmpty() } ?: continue
            val providerId = o.optString("providerId", "").trim().takeIf { it.isNotEmpty() }
                ?: id.substringBefore('/')
            out += ModelInfo(
                providerId = providerId,
                id = id,
                label = o.optString("label", id).ifBlank { id },
                contextWindow = o.optInt("contextWindow", 128_000),
                maxOutputTokens = o.optInt("maxOutputTokens", 8_192),
                supportsTools = o.optBoolean("supportsTools", true),
                supportsReasoning = o.optBoolean("supportsReasoning", false),
                supportsVision = o.optBoolean("supportsVision", false),
                inputCostPerM = o.optDouble("inputCostPerM", 0.0),
                outputCostPerM = o.optDouble("outputCostPerM", 0.0),
                cacheReadCostPerM = o.optDouble("cacheReadCostPerM", 0.0),
                cacheWriteCostPerM = o.optDouble("cacheWriteCostPerM", 0.0),
            )
        }
        return out
    }

    companion object {
        internal const val PREFS = "lumen.models"

        internal fun cacheKey(provider: String): String = "models.$provider"

        internal fun timeKey(provider: String): String = "modelsAt.$provider"
    }
}
