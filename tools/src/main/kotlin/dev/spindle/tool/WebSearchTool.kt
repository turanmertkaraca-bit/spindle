package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.Request
import java.net.URI
import java.net.URLEncoder

/**
 * Keyless web search backed by DuckDuckGo's HTML endpoint. Each invocation is
 * gated behind a permission request and the numbered result list is capped in
 * both count and characters. Network and parse problems are returned as error
 * outcomes rather than thrown.
 */
class WebSearchTool(
    private val fetch: suspend (URI) -> String = ::fetchSearchHtml,
) : Tool {
    override val spec = ToolSpec(
        name = "websearch",
        description = "Search the web with DuckDuckGo and return a numbered list of " +
            "results (title, URL and snippet). No API key is required. Up to " +
            "${MAX_COUNT} results, output capped at ${Limits.WEBSEARCH_MAX_CHARS} characters.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "query": {"type": "string"},
                "count": {"type": "integer"},
                "recency": {"type": "string"}
              },
              "required": ["query"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val query = try {
            input.requireString("query").trim()
        } catch (e: Exception) {
            return ToolOutcome(e.message ?: "Missing required field 'query'", isError = true)
        }
        if (query.isEmpty()) return ToolOutcome("query must not be blank", isError = true)

        val count = (input.intOrNull("count") ?: DEFAULT_COUNT).coerceIn(1, MAX_COUNT)
        val recency = recencyParameter(input.stringOrNull("recency"))
        val uri = URI.create(buildUrl(query, recency))

        if (!ctx.requestPermission("websearch", query, uri.host)) {
            return ToolOutcome("denied by user", isError = true)
        }

        val html = try {
            fetch(uri)
        } catch (e: Exception) {
            return ToolOutcome("Search failed: ${e.message}", isError = true)
        }

        val hits = WebSearchParser.parse(html, count)
        if (hits.isEmpty()) {
            return ToolOutcome(
                "No results for \"$query\" (DuckDuckGo returned no parseable results).",
                isError = true,
                metadata = mapOf("query" to query, "results" to "0"),
            )
        }

        val rendered = render(hits)
        val truncated = rendered.length > Limits.WEBSEARCH_MAX_CHARS
        val output = if (truncated) {
            rendered.substring(0, Limits.WEBSEARCH_MAX_CHARS) +
                charCapNote("websearch", Limits.WEBSEARCH_MAX_CHARS)
        } else {
            rendered
        }

        return ToolOutcome(
            output = output,
            metadata = mapOf(
                "query" to query,
                "count" to count.toString(),
                "results" to hits.size.toString(),
                "host" to (uri.host ?: ""),
                "truncated" to truncated.toString(),
            ),
        )
    }

    private fun render(hits: List<SearchHit>): String = buildString {
        hits.forEachIndexed { index, hit ->
            append(index + 1).append(". ").append(hit.title).append(" — ").append(hit.url)
            if (hit.snippet.isNotEmpty()) append('\n').append("  ").append(hit.snippet)
            if (index != hits.lastIndex) append('\n')
        }
    }

    private fun buildUrl(query: String, recency: String?): String {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val builder = StringBuilder(BASE_URL).append("?q=").append(encoded)
        if (recency != null) builder.append("&df=").append(recency)
        return builder.toString()
    }

    /** Map friendly recency words to DuckDuckGo's `df` codes; unknown = ignored. */
    private fun recencyParameter(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        return when (value) {
            "d", "day", "past day", "24h" -> "d"
            "w", "week", "past week", "7d" -> "w"
            "m", "month", "past month", "30d" -> "m"
            "y", "year", "past year", "365d" -> "y"
            else -> null
        }
    }

    private companion object {
        const val BASE_URL = "https://html.duckduckgo.com/html/"
        const val DEFAULT_COUNT = 5
        const val MAX_COUNT = 10
    }
}

/** Default transport: OkHttp with the ambient sandbox proxy honoured. */
internal suspend fun fetchSearchHtml(uri: URI): String = withContext(Dispatchers.IO) {
    val request = Request.Builder()
        .url(uri.toURL())
        .header("User-Agent", "Mozilla/5.0 (compatible; spindle-websearch/0.1)")
        .get()
        .build()

    defaultHttpClient(followRedirects = true).newCall(request).execute().use { response ->
        if (response.code >= 400) throw IllegalStateException("HTTP ${response.code}")
        val input = response.body?.byteStream() ?: return@withContext ""
        val bytes = input.readNBytes(Limits.WEBSEARCH_MAX_HTML_BYTES)
        String(bytes, Charsets.UTF_8)
    }
}
