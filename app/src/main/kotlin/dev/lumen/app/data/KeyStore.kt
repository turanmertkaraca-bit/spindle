package dev.lumen.app.data

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Tiny key store on private SharedPreferences. Secrets (the provider API key
 * and the GitHub token) are sealed with [SecretCipher] (Android Keystore
 * AES/GCM) before they are persisted, so they are never cleartext at rest; the
 * manifest disables backup so they cannot be cloud-backed-up either.
 */
class KeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lumen.keys", Context.MODE_PRIVATE)
    private val cipher = SecretCipher(context)

    /**
     * Last-resort, process-lifetime holders used only when even the software AES
     * fallback cannot seal a secret. They are never written to disk, so a
     * failure to encrypt can never silently downgrade a secret to cleartext at
     * rest. Keyed by secret name so the provider key and the GitHub token stay
     * independent.
     */
    private val transient = ConcurrentHashMap<String, String>()

    /** Read [name], migrating a legacy cleartext [legacyKey] on first read. */
    private fun readSecret(name: String, encKey: String, legacyKey: String): String? {
        prefs.getString(encKey, null)?.let { sealed ->
            cipher.decrypt(sealed)?.let { return it }
        }
        transient[name]?.let { return it }
        val legacy = prefs.getString(legacyKey, null) ?: return null
        writeSecret(name, encKey, legacyKey, legacy)
        return legacy
    }

    /** Seal [value] at rest, or hold it in memory only. Never persists cleartext. */
    private fun writeSecret(name: String, encKey: String, legacyKey: String, value: String?) {
        if (value.isNullOrBlank()) {
            transient.remove(name)
            prefs.edit().remove(encKey).remove(legacyKey).apply()
            return
        }
        val clean = value.trim()
        val sealed = cipher.encrypt(clean)
        if (sealed != null) {
            transient.remove(name)
            prefs.edit().putString(encKey, sealed).remove(legacyKey).apply()
        } else {
            // Encryption is unavailable (Keystore broken and no AES):
            // hold the key in memory only. Never persist cleartext.
            transient[name] = clean
            prefs.edit().remove(encKey).remove(legacyKey).apply()
        }
    }

    var apiKey: String?
        get() = readSecret("apiKey", "apiKeyEnc", "apiKey")
        set(value) = writeSecret("apiKey", "apiKeyEnc", "apiKey", value)

    /**
     * GitHub personal access token, sealed with the same [SecretCipher] as
     * [apiKey] and migrated from a legacy cleartext `githubToken` pref. Never
     * logged, echoed or embedded in a remote URL.
     */
    var githubToken: String?
        get() = readSecret("githubToken", "githubTokenEnc", "githubToken")
        set(value) = writeSecret("githubToken", "githubTokenEnc", "githubToken", value)

    /** The authenticated GitHub login (non-secret; shown on the status card). */
    var githubLogin: String
        get() = prefs.getString("githubLogin", "") ?: ""
        set(value) = prefs.edit().putString("githubLogin", value).apply()

    /** The chosen GitHub repository as `owner/name` (non-secret). */
    var githubRepo: String
        get() = prefs.getString("githubRepo", "") ?: ""
        set(value) = prefs.edit().putString("githubRepo", value).apply()

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

    /** The active primary agent: "build" | "plan" | "delegate". */
    var agentMode: String
        get() = prefs.getString("agentMode", "build") ?: "build"
        set(value) = prefs.edit().putString("agentMode", value).apply()

    /**
     * Auto-compaction threshold as a percentage of the model's context window
     * (50..95). 80 matches the historical [Overflow.COMPACT_AT]-ish behaviour;
     * a lower value compacts earlier.
     */
    var autoCompactPercent: Int
        get() = prefs.getInt("autoCompactPercent", 80).coerceIn(50, 95)
        set(value) = prefs.edit().putInt("autoCompactPercent", value.coerceIn(50, 95)).apply()

    /** True once the first-run setup wizard has been finished or skipped. */
    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    /** True when the user granted shared Downloads access to the sandbox. */
    var downloadsAccess: Boolean
        get() = prefs.getBoolean("downloadsAccess", false)
        set(value) = prefs.edit().putBoolean("downloadsAccess", value).apply()

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

    /** True when a GitHub token has been stored (and can be decrypted). */
    val hasGithubToken: Boolean get() = !githubToken.isNullOrBlank()

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
