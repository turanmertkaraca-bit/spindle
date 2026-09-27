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

    val hasKey: Boolean get() = !apiKey.isNullOrBlank()

    companion object {
        fun defaultModel(provider: String): String = when (provider) {
            "deepseek" -> "deepseek/deepseek-flash"
            "openrouter" -> "openrouter/anthropic/claude-3.5-sonnet"
            else -> "opencode-go/deepseek-v4.1-flash"
        }
    }
}
