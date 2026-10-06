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
    fun parse(html: String, limit: Int): List<SearchHit> {
        if (limit <= 0) return emptyList()
        // Bound the adversarial input before doing any scanning.
        val source = if (html.length > Limits.WEBSEARCH_MAX_HTML_CHARS) {
            html.substring(0, Limits.WEBSEARCH_MAX_HTML_CHARS)
        } else {
            html
        }
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

        // Linear scan: each indexOf starts where the previous anchor ended, so
        // adversarial markup (many `<a` with no closing tag) is O(n), not O(n²).
        var cursor = 0
        while (cursor < source.length) {
            val start = source.indexOf("<a", cursor, ignoreCase = true)
            if (start < 0) break
            val afterName = start + 2
            if (afterName < source.length) {
                val next = source[afterName]
                if (next != '>' && next != '/' && !next.isWhitespace()) {
                    cursor = afterName
                    continue
                }
            }
            val tagEnd = source.indexOf('>', afterName)
            if (tagEnd < 0) break
            val close = source.indexOf("</a", tagEnd, ignoreCase = true)
            if (close < 0) break

            val attrs = source.substring(afterName, tagEnd)
            val textHtml = source.substring(tagEnd + 1, close)
            val classes = attribute(attrs, "class")?.split(Regex("\\s+")) ?: emptyList()
            when {
                "result__a" in classes -> {
                    flush()
                    val text = clean(textHtml)
                    val resolved = attribute(attrs, "href")?.let { unwrapUrl(it) }
                    if (text.isNotEmpty() && !resolved.isNullOrEmpty()) {
                        title = text
                        url = resolved
                    }
                }
                "result__snippet" in classes -> {
                    if (title != null) snippet = clean(textHtml)
                }
            }
            cursor = close + 3
        }
        if (hits.size < limit) flush()
        return hits
    }

    /**
     * True when [html] is DuckDuckGo's "anomaly" bot challenge rather than a
     * results page. Both are HTTP 2xx, so the only way to tell a block from a
     * genuinely empty query is to look for the challenge's own markers. The
     * scan is bounded so an adversarial body cannot make it pathological.
     */
    fun isChallengePage(html: String): Boolean {
        val sample = if (html.length > CHALLENGE_SCAN_CHARS) {
            html.substring(0, CHALLENGE_SCAN_CHARS)
        } else {
            html
        }
        return sample.contains("anomaly-modal", ignoreCase = true) ||
            sample.contains("assets/anomaly/", ignoreCase = true) ||
            sample.contains("challenge-form", ignoreCase = true) ||
            sample.contains("bots use DuckDuckGo too", ignoreCase = true)
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

    /**
     * Unwrap `//duckduckgo.com/l/?uddg=<encoded>` style redirect links. Only
     * `http(s)` targets are returned; `javascript:`, `data:` and every other
     * scheme yield the empty string so they can never be rendered as a link.
     */
    fun unwrapUrl(raw: String): String {
        var value = unescapeHtmlEntities(raw).trim()
        if (value.startsWith("//")) value = "https:$value"
        val marker = "uddg="
        val index = value.indexOf(marker)
        if (index >= 0) {
            val encoded = value.substring(index + marker.length).substringBefore('&')
            value = try {
                URLDecoder.decode(encoded, "UTF-8")
            } catch (e: Exception) {
                encoded
            }
        }
        return if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            value
        } else {
            ""
        }
    }

    private const val CHALLENGE_SCAN_CHARS = 200_000
}
