package dev.lumen.app.data

import android.content.Context

/** Tiny key store on private SharedPreferences. Nothing leaves the device. */
class KeyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lumen.keys", Context.MODE_PRIVATE)

    var apiKey: String?
        get() = prefs.getString("apiKey", null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrBlank()) remove("apiKey") else putString("apiKey", value.trim())
            }.apply()
        }

    /** Which provider the key belongs to. Drives the default model. */
    var provider: String
        get() = prefs.getString("provider", "opencode-go") ?: "opencode-go"
        set(value) = prefs.edit().putString("provider", value).apply()

    var model: String
        get() = prefs.getString("model", defaultModel(provider)) ?: defaultModel(provider)
        set(value) = prefs.edit().putString("model", value).apply()

    /** "system" | "light" | "dark". */
    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    /**
     * When true, every tool call goes through an interactive ask before it runs.
     * Default false keeps the historical unattended behaviour (allow all).
     */
    var askBeforeTools: Boolean
        get() = prefs.getBoolean("askBeforeTools", false)
        set(value) = prefs.edit().putBoolean("askBeforeTools", value).apply()

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
        set(value) = prefs.edit()
            .putString("allowedPatterns", value.filter { it.isNotBlank() }.joinToString(PATTERN_DELIM))
            .apply()

    val hasKey: Boolean get() = !apiKey.isNullOrBlank()

    companion object {
        /** Unit separator: cannot occur in tool names, commands or paths we store. */
        private const val PATTERN_DELIM = "\u001F"

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
