package dev.spindle.tool

import java.net.URLDecoder

/** One parsed web-search result. */
internal data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
)

/**
 * A tiny, dependency-free extractor for DuckDuckGo's HTML endpoint. It pairs
 * `result__a` anchors (title + link) with the following `result__snippet`
 * anchor, unwraps DuckDuckGo redirect links and decodes entities. Kept pure so
 * the parsing is unit-tested without touching the network.
 */
internal object WebSearchParser {
    private val ANCHOR = Regex("(?is)<a\\b([^>]*)>(.*?)</a>")

    fun parse(html: String, limit: Int): List<SearchHit> {
        if (limit <= 0) return emptyList()
        val hits = mutableListOf<SearchHit>()
        var title: String? = null
        var url: String? = null
        var snippet = ""

        fun flush() {
            val t = title
            val u = url
            if (t != null && u != null && hits.size < limit) hits += SearchHit(t, u, snippet)
            title = null
            url = null
            snippet = ""
        }

        for (match in ANCHOR.findAll(html)) {
            val attrs = match.groupValues[1]
            val classes = attribute(attrs, "class")?.split(Regex("\\s+")) ?: emptyList()
            when {
                "result__a" in classes -> {
                    flush()
                    val text = clean(match.groupValues[2])
                    val resolved = attribute(attrs, "href")?.let { unwrapUrl(it) }
                    if (text.isNotEmpty() && !resolved.isNullOrEmpty()) {
                        title = text
                        url = resolved
                    }
                }
                "result__snippet" in classes -> {
                    if (title != null) snippet = clean(match.groupValues[2])
                }
            }
        }
        if (hits.size < limit) flush()
        return hits
    }

    private fun attribute(attrs: String, name: String): String? {
        val quoted = Regex("(?i)\\b$name\\s*=\\s*\"([^\"]*)\"").find(attrs)
            ?: Regex("(?i)\\b$name\\s*=\\s*'([^']*)'").find(attrs)
        if (quoted != null) return quoted.groupValues[1]
        return Regex("(?i)\\b$name\\s*=\\s*([^\\s>]+)").find(attrs)?.groupValues?.get(1)
    }

    private fun clean(html: String): String {
        val text = unescapeHtmlEntities(Regex("(?s)<[^>]*>").replace(html, ""))
        return text.replace(Regex("\\s+"), " ").trim()
    }

    /** Unwrap `//duckduckgo.com/l/?uddg=<encoded>` style redirect links. */
    fun unwrapUrl(raw: String): String {
        var value = unescapeHtmlEntities(raw).trim()
        if (value.startsWith("//")) value = "https:$value"
        val marker = "uddg="
        val index = value.indexOf(marker)
        if (index >= 0) {
            val encoded = value.substring(index + marker.length).substringBefore('&')
            return try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (e: Exception) {
                encoded
            }
        }
        return value
    }
}
