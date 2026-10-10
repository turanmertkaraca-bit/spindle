package dev.spindle.tool

import dev.spindle.core.provider.ToolSpec
import dev.spindle.core.tool.Tool
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/** Raw response for a single HTTP hop, with the body capped before decoding. */
internal data class HttpResponseData(
    val status: Int,
    val location: String?,
    val body: ByteArray,
    val truncated: Boolean,
)

/** One-hop HTTP transport. Injectable so redirect/SSRF logic is testable offline. */
internal interface HttpResponseTransport {
    suspend fun get(uri: URI, maxBytes: Int): HttpResponseData
}

/** Outcome of following a request through manual redirect validation. */
internal sealed class FetchResult {
    data class Success(val status: Int, val body: ByteArray, val truncated: Boolean) : FetchResult()
    data class Failure(val message: String) : FetchResult()
}

/** OkHttp-backed transport; the JDK HTTP client is absent on Android. */
internal class OkHttpHttpTransport(
    private val client: OkHttpClient = defaultHttpClient(followRedirects = false),
) : HttpResponseTransport {
    override suspend fun get(uri: URI, maxBytes: Int): HttpResponseData = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(uri.toURL())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val input = response.body?.byteStream()
            val bytes = if (input != null) input.readNBytes(maxBytes) else ByteArray(0)
            val truncated = if (input != null) input.read() != -1 else false
            HttpResponseData(
                status = response.code,
                location = response.header("Location"),
                body = bytes,
                truncated = truncated,
            )
        }
    }
}

internal fun defaultHttpClient(followRedirects: Boolean): OkHttpClient {
    val builder = OkHttpClient.Builder()
        .followRedirects(followRedirects)
        .followSslRedirects(followRedirects)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
    proxyFromEnvironment()?.let { builder.proxy(it) }
    return builder.build()
}

private const val USER_AGENT =
    "Mozilla/5.0 (compatible; spindle-webfetch/0.1; +https://github.com/anomalyco/opencode)"
private const val MAX_REDIRECTS = 5

/**
 * Follow redirects by hand so every hop is re-validated (http/https only, no
 * loopback/link-local/private targets). Policy and network failures are returned
 * as [FetchResult.Failure] instead of thrown.
 */
internal suspend fun fetchFollowingRedirects(
    start: URI,
    transport: HttpResponseTransport,
    maxBytes: Int,
): FetchResult {
    var uri = start
    var redirects = 0
    while (true) {
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return FetchResult.Failure("Refusing to fetch non-http(s) URL: $uri")
        }
        val blocked = blockedAddressReason(uri.host)
        if (blocked != null) {
            return FetchResult.Failure("Refusing to fetch ${uri.host}: $blocked")
        }
        val response = try {
            transport.get(uri, maxBytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return FetchResult.Failure("Fetch failed: ${e.message}")
        }
        if (response.status in 300..399) {
            val location = response.location
                ?: return FetchResult.Failure("Redirect from $uri had no Location header")
            redirects++
            if (redirects > MAX_REDIRECTS) {
                return FetchResult.Failure("Too many redirects (limit $MAX_REDIRECTS)")
            }
            uri = try {
                uri.resolve(location)
            } catch (e: Exception) {
                return FetchResult.Failure("Invalid redirect target: $location")
            }
            continue
        }
        return FetchResult.Success(response.status, response.body, response.truncated)
    }
}

/**
 * Null when [host] resolves only to public addresses, otherwise a rejection
 * reason. Literal IPs and loopback names are judged without any lookup, so the
 * SSRF gate never depends on DNS for the common dangerous cases.
 *
 * [allowUnresolved] covers proxy-only sandboxes (Android, CI, this guest):
 * there is no local resolver, but the HTTP proxy resolves the name for the real
 * connection, so a failed local lookup is not by itself a reason to refuse.
 * Without a proxy an unresolvable host stays a hard failure.
 */
internal fun blockedAddressReason(
    host: String?,
    allowUnresolved: Boolean = proxyFromEnvironment() != null,
): String? {
    if (host.isNullOrBlank()) return "missing host"
    val lower = host.lowercase().removeSurrounding("[", "]")
    if (lower == "localhost" || lower.endsWith(".localhost")) return "loopback host"
    val addresses = try {
        InetAddress.getAllByName(lower)
    } catch (e: Exception) {
        return if (allowUnresolved) null else "could not resolve host"
    }
    if (addresses.isEmpty()) return if (allowUnresolved) null else "could not resolve host"
    for (address in addresses) {
        if (isBlockedAddress(address)) {
            return "address resolves to a non-public range (${address.hostAddress})"
        }
    }
    return null
}

/** Reject loopback, link-local, site-local, any-local, multicast and IPv6 ULA. */
internal fun isBlockedAddress(address: InetAddress): Boolean =
    address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isAnyLocalAddress ||
        address.isMulticastAddress ||
        (address is Inet6Address && (address.address[0].toInt() and 0xfe) == 0xfc)

/** Fetch a URL over OkHttp and render it as markdown, text or raw HTML. */
internal class WebFetchTool(
    private val transport: HttpResponseTransport = OkHttpHttpTransport(),
) : Tool {
    override val spec = ToolSpec(
        name = "webfetch",
        description = "Fetch an http(s) URL and return its content as markdown (default), " +
            "plain text, or raw HTML. Redirects are followed with each hop validated. " +
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

    override val timeoutMs = 60_000L

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

        ctx.checkAborted()
        val result = when (val fetched = fetchFollowingRedirects(uri, transport, Limits.WEB_MAX_BYTES)) {
            is FetchResult.Failure -> return ToolOutcome(fetched.message, isError = true)
            is FetchResult.Success -> fetched
        }

        val body = String(result.body, StandardCharsets.UTF_8)
        val converted = when (format) {
            "html" -> body
            "text" -> htmlToText(body)
            else -> htmlToMarkdown(body)
        }
        val charTruncated = converted.length > Limits.WEB_MAX_CHARS
        val shown = if (charTruncated) converted.substring(0, Limits.WEB_MAX_CHARS) else converted
        val truncated = charTruncated || result.truncated
        val note = if (charTruncated) charCapNote("webfetch", Limits.WEB_MAX_CHARS) else ""
        // Surface the status in the text body: only `output` reaches the model,
        // so keeping it solely in metadata left the model unable to report it.
        val header = "HTTP ${result.status}\n\n"

        return ToolOutcome(
            output = header + shown + note,
            isError = result.status >= 400,
            metadata = mapOf(
                "url" to raw,
                "format" to format,
                "status" to result.status.toString(),
                "truncated" to truncated.toString(),
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
    }
}
