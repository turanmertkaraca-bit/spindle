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

    val hasKey: Boolean get() = !apiKey.isNullOrBlank()

    companion object {
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
