package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.net.URLEncoder

/**
 * Keyless web search backed by DuckDuckGo's HTML endpoint. Each invocation is
 * gated behind a permission request and the numbered result list is capped in
 * both count and characters. Network and parse problems are returned as error
 * outcomes rather than thrown. A live "anomaly" bot challenge (HTTP 2xx with no
 * anchors) is reported as an explicit blocked error, never as "no results".
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
            // A 2xx "anomaly" challenge page parses to zero hits just like a
            // genuinely empty result set. Tell the two apart so the model gets
            // an honest "blocked" error it can retry instead of a false "no
            // results".
            if (WebSearchParser.isChallengePage(html)) {
                return ToolOutcome(
                    "Search blocked: DuckDuckGo served a bot challenge instead of results. " +
                        "Retry shortly or rephrase the query.",
                    isError = true,
                    metadata = mapOf("query" to query, "results" to "0", "blocked" to "true"),
                )
            }
            return ToolOutcome(
                "No results for \"$query\" (DuckDuckGo returned no parseable results).",
                isError = true,
                metadata = mapOf("query" to query, "results" to "0", "blocked" to "false"),
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
        val builder = StringBuilder(HTML_URL).append("?q=").append(encoded)
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
        const val DEFAULT_COUNT = 5
        const val MAX_COUNT = 10
    }
}

private const val HTML_URL = "https://html.duckduckgo.com/html/"
private const val LITE_URL = "https://lite.duckduckgo.com/lite/"

/** A real browser UA; DuckDuckGo bot-walls the default/curl-style agents. */
private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Default transport: OkHttp with the ambient sandbox proxy honoured. A plain
 * GET to DuckDuckGo's HTML endpoint is commonly answered with an HTTP 2xx
 * "anomaly" challenge; submitting the same query as a browser-style form POST
 * returns the real result markup. We try POST first, then a browser GET, then
 * the lite endpoint, and only hand back a challenge/empty body once every hop
 * has been tried so [WebSearchTool] can classify it honestly.
 */
internal suspend fun fetchSearchHtml(uri: URI): String = withContext(Dispatchers.IO) {
    val form = uri.rawQuery.orEmpty()
    val client = defaultHttpClient(followRedirects = true)
    val errors = mutableListOf<String>()

    fun read(response: okhttp3.Response, what: String): String? {
        if (response.code >= 400) throw IllegalStateException("$what HTTP ${response.code}")
        return response.body?.byteStream()?.readNBytes(Limits.WEBSEARCH_MAX_HTML_BYTES)
            ?.toString(Charsets.UTF_8)
    }

    fun get(target: URI): String? = try {
        val request = Request.Builder()
            .url(target.toURL())
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get()
            .build()
        client.newCall(request).execute().use { read(it, "GET") }
    } catch (e: Exception) {
        errors += e.message ?: "GET failed"
        null
    }

    fun post(): String? = try {
        val request = Request.Builder()
            .url(HTML_URL)
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Origin", "https://html.duckduckgo.com")
            .header("Referer", HTML_URL)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        client.newCall(request).execute().use { read(it, "POST") }
    } catch (e: Exception) {
        errors += e.message ?: "POST failed"
        null
    }

    val posted = post()
    if (posted != null && !WebSearchParser.isChallengePage(posted)) return@withContext posted
    val fetched = get(uri)
    if (fetched != null && !WebSearchParser.isChallengePage(fetched)) return@withContext fetched
    val lite = get(URI.create("$LITE_URL?$form"))
    if (lite != null && !WebSearchParser.isChallengePage(lite)) return@withContext lite

    posted ?: fetched ?: lite
        ?: throw IllegalStateException(errors.joinToString("; ").ifBlank { "all search requests failed" })
}
