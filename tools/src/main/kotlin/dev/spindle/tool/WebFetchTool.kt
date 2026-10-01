package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Fetch a URL with the JDK HTTP client and render it as markdown, text or raw HTML. */
class WebFetchTool : Tool {
    override val spec = ToolSpec(
        name = "webfetch",
        description = "Fetch an http(s) URL and return its content as markdown (default), " +
            "plain text, or raw HTML. Follows redirects and times out after 15 seconds. " +
            "Output is capped at ${Limits.WEB_MAX_CHARS} characters.",
        parametersJson = """
            {
              "type": "object",
              "properties": {
                "url": {"type": "string", "description": "The http(s) URL to fetch"},
                "format": {
                  "type": "string",
                  "enum": ["markdown", "text", "html"],
                  "description": "How to render the response body (default markdown)"
                }
              },
              "required": ["url"],
              "additionalProperties": false
            }
        """.trimIndent(),
    )

    override suspend fun run(input: JsonObject, ctx: ToolContext): ToolOutcome {
        val raw = input.requireString("url").trim()
        val format = (input.stringOrNull("format") ?: "markdown").lowercase()
        if (format !in FORMATS) {
            return ToolOutcome("Unsupported format '$format' (expected markdown, text or html)", isError = true)
        }

        val uri = try {
            URI(raw)
        } catch (e: Exception) {
            return ToolOutcome("Invalid URL: $raw", isError = true)
        }
        val scheme = uri.scheme?.lowercase()
        if ((scheme != "http" && scheme != "https") || uri.host.isNullOrBlank()) {
            return ToolOutcome("Refusing to fetch non-http(s) URL: $raw", isError = true)
        }

        val builder = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
        ambientProxySelector()?.let { builder.proxy(it) }
        val client = builder.build()

        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = try {
            withContext(Dispatchers.IO) {
                client.send(request, HttpResponse.BodyHandlers.ofString())
            }
        } catch (e: Exception) {
            return ToolOutcome("Fetch failed: ${e.message}", isError = true)
        }

        val body = response.body() ?: ""
        val converted = when (format) {
            "html" -> body
            "text" -> htmlToText(body)
            else -> htmlToMarkdown(body)
        }
        val truncated = converted.length > Limits.WEB_MAX_CHARS
        val shown = if (truncated) converted.substring(0, Limits.WEB_MAX_CHARS) else converted
        val note = if (truncated) "\n\n…[webfetch: truncated at ${Limits.WEB_MAX_CHARS} chars]" else ""

        return ToolOutcome(
            output = shown + note,
            isError = response.statusCode() >= 400,
            metadata = mapOf(
                "url" to raw,
                "format" to format,
                "status" to response.statusCode().toString(),
            ),
        )
    }

    private fun stripTags(html: String): String =
        Regex("(?s)<[^>]*>").replace(html, "")

    private fun htmlToText(html: String): String {
        var text = removeNonContent(html)
        text = Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6])[^>]*>").replace(text, "\n")
        text = stripTags(text)
        text = unescapeHtmlEntities(text)
        text = Regex("[ \\t\\x0B\\f\\r]+").replace(text, " ")
        text = Regex(" *\\n *").replace(text, "\n")
        text = Regex("\\n{3,}").replace(text, "\n\n")
        return text.trim()
    }

    private fun htmlToMarkdown(html: String): String {
        var text = removeNonContent(html)

        text = Regex("(?is)<a[^>]*href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>").replace(text) { match ->
            val href = match.groupValues[1]
            val label = stripTags(match.groupValues[2]).trim()
            if (label.isEmpty()) href else "[$label]($href)"
        }
        for (level in 1..6) {
            text = Regex("(?is)<h$level[^>]*>(.*?)</h$level>").replace(text) { match ->
                "\n" + "#".repeat(level) + " " + stripTags(match.groupValues[1]).trim() + "\n"
            }
        }
        text = Regex("(?i)<li[^>]*>").replace(text, "\n- ")
        text = Regex("(?i)<(br|/p|/div|/tr)[^>]*>").replace(text, "\n")
        text = stripTags(text)
        text = unescapeHtmlEntities(text)
        text = Regex("[ \\t\\x0B\\f\\r]+").replace(text, " ")
        text = Regex(" *\\n *").replace(text, "\n")
        text = Regex("\\n{3,}").replace(text, "\n\n")
        return text.trim()
    }

    private fun removeNonContent(html: String): String {
        var text = Regex("(?is)<script[^>]*>.*?</script>").replace(html, "")
        text = Regex("(?is)<style[^>]*>.*?</style>").replace(text, "")
        text = Regex("(?is)<!--.*?-->").replace(text, "")
        return text
    }

    private companion object {
        val FORMATS = setOf("markdown", "text", "html")
        const val USER_AGENT =
            "Mozilla/5.0 (compatible; spindle-webfetch/0.1; +https://github.com/anomalyco/opencode)"
    }
}
