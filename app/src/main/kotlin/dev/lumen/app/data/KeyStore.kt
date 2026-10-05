package dev.lumen.app.data

import android.content.Context

/**
 * Tiny key store on private SharedPreferences. The API key is sealed with
 * [SecretCipher] (Android Keystore AES/GCM) before it is persisted, so it is
 * never cleartext at rest; the manifest disables backup so it cannot be
 * cloud-backed-up either.
 */
class KeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lumen.keys", Context.MODE_PRIVATE)
    private val cipher = SecretCipher(context)

    /**
     * Last-resort, process-lifetime holder used only when even the software AES
     * fallback cannot seal the key. It is never written to disk, so a failure to
     * encrypt can never silently downgrade the key to cleartext at rest.
     */
    @Volatile
    private var transientKey: String? = null

    var apiKey: String?
        get() {
            prefs.getString("apiKeyEnc", null)?.let { sealed ->
                cipher.decrypt(sealed)?.let { return it }
            }
            transientKey?.let { return it }
            // Migrate a legacy cleartext key to the encrypted form on first read.
            val legacy = prefs.getString("apiKey", null) ?: return null
            apiKey = legacy
            return legacy
        }
        set(value) {
            if (value.isNullOrBlank()) {
                transientKey = null
                prefs.edit().remove("apiKeyEnc").remove("apiKey").apply()
                return
            }
            val clean = value.trim()
            val sealed = cipher.encrypt(clean)
            if (sealed != null) {
                transientKey = null
                prefs.edit().putString("apiKeyEnc", sealed).remove("apiKey").apply()
            } else {
                // Encryption is unavailable (Keystore broken and no AES):
                // hold the key in memory only. Never persist cleartext.
                transientKey = clean
                prefs.edit().remove("apiKeyEnc").remove("apiKey").apply()
            }
        }

    /**
     * Which provider the key belongs to. Switching providers resets [model] to
     * the new provider's default, so a model id the provider cannot serve is
     * never carried across the change.
     */
    var provider: String
        get() = prefs.getString("provider", DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        set(value) {
            val previous = prefs.getString("provider", DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
            prefs.edit().putString("provider", value).apply()
            if (previous != value) {
                prefs.edit().putString("model", defaultModel(value)).apply()
            }
        }

    var model: String
        get() = prefs.getString("model", defaultModel(provider)) ?: defaultModel(provider)
        set(value) = prefs.edit().putString("model", value).apply()

    /** "system" | "light" | "dark". */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    /** The active primary agent: "build" | "plan". */
    var agentMode: String
        get() = prefs.getString("agentMode", "build") ?: "build"
        set(value) = prefs.edit().putString("agentMode", value).apply()

    /**
     * When true, every tool call goes through an interactive ask before it runs.
     * Default false keeps the historical unattended behaviour (allow all).
     */
    var askBeforeTools: Boolean
        get() = prefs.getBoolean("askBeforeTools", false)
        set(value) = prefs.edit().putBoolean("askBeforeTools", value).apply()

    /**
     * Per-session cost ceiling in USD. `0.0` means unlimited (the default), so
     * the loop runs year-round unless the user opts into a budget. When set, the
     * loop emits a `BudgetWarning` as spend approaches it and stops a run that
     * would exceed it. Stored as a string so the value keeps full double
     * precision (the old Float round-trip lost cents).
     */
    var maxCostUsd: Double
        get() {
            val raw = prefs.all["maxCostUsd"] ?: return 0.0
            val value = when (raw) {
                is String -> raw.toDoubleOrNull()
                is Number -> raw.toDouble()
                else -> null
            }
            return value?.takeIf { it.isFinite() } ?: 0.0
        }
        set(value) = prefs.edit().putString("maxCostUsd", value.toString()).apply()

    /**
     * Scope keys (tool name, or a bash command's first token / file path) the
     * user chose "always allow" for. Stored as a single delimited string; an
     * empty or missing value means nothing has been remembered yet.
     */
    var allowedPatterns: Set<String>
        get() = prefs.getString("allowedPatterns", null)
            ?.split(PATTERN_DELIM)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: emptySet()
        set(value) {
            // Bounded so a very long-lived install cannot grow the pref without
            // limit; the oldest "always allow" entries fall off first (the set is
            // insertion-ordered), which merely re-prompts for them later.
            val capped = value.filter { it.isNotBlank() }.takeLast(MAX_ALLOWED_PATTERNS)
            prefs.edit().putString("allowedPatterns", capped.joinToString(PATTERN_DELIM)).apply()
        }

    val hasKey: Boolean get() = !apiKey.isNullOrBlank()

    companion object {
        private const val DEFAULT_PROVIDER = "opencode-go"

        /** Unit separator: cannot occur in tool names, commands or paths we store. */
        private const val PATTERN_DELIM = "\u001F"

        /** Most remembered "always allow" scope keys retained; oldest drop first. */
        const val MAX_ALLOWED_PATTERNS = 200

        /**
         * The model id is `<provider>/<model id>`. OpenRouter model ids may
         * themselves contain slashes, and its router ids are the safest default
         * because they always resolve to something the account can call:
         *   openrouter/free         -> picks a free model automatically
         */
        fun defaultModel(provider: String): String = when (provider) {
            "deepseek" -> "deepseek/deepseek-flash"
            "openrouter" -> "openrouter/openrouter/free"
            else -> "opencode-go/deepseek-v4.1-flash"
        }
    }
}
