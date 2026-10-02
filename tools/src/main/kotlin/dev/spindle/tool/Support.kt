package dev.spindle.tool

import dev.spindle.core.model.Ids
import dev.spindle.core.model.Snapshot
import dev.spindle.core.tool.ToolContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest

/** Shared caps so tool output can never blow up the model context. */
internal object Limits {
    const val READ_MAX_LINES = 2000
    const val READ_MAX_BYTES = 2 * 1024 * 1024
    const val READ_MAX_SCAN_LINES = 1_000_000
    const val GLOB_MAX_RESULTS = 1000
    const val GREP_MAX_RESULTS = 1000
    const val GREP_MAX_FILE_BYTES = 2 * 1024 * 1024
    const val GREP_MAX_LINE_CHARS = 2000
    const val GREP_MATCH_BUDGET_BASE = 1_000_000L
    const val GREP_MATCH_BUDGET_PER_CHAR = 100L
    const val BINARY_SNIFF_BYTES = 8000
    const val BASH_MAX_OUTPUT_CHARS = 50_000
    const val WEB_MAX_CHARS = 20_000
    const val WEB_MAX_BYTES = 1_000_000
    const val WEBSEARCH_MAX_CHARS = 12_000
    const val WEBSEARCH_MAX_HTML_BYTES = 1_000_000
    const val WEBSEARCH_MAX_HTML_CHARS = 1_000_000
}

/**
 * Build a JDK proxy from the ambient sandbox environment when present. Kept
 * here so every HTTP-backed tool (OkHttp) shares the same proxy-awareness.
 */
internal fun proxyFromEnvironment(): Proxy? {
    val raw = System.getenv("https_proxy")
        ?: System.getenv("HTTPS_PROXY")
        ?: System.getenv("http_proxy")
        ?: System.getenv("HTTP_PROXY")
    if (raw.isNullOrBlank()) return null
    return try {
        val uri = URI(if (raw.contains("://")) raw else "http://$raw")
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else 80
        Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    } catch (e: Exception) {
        null
    }
}

/** Decode the small set of HTML entities that appear in scraped markup. */
internal fun unescapeHtmlEntities(text: String): String {
    var result = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
    result = Regex("&#([0-9]+);").replace(result) { match ->
        match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: match.value
    }
    result = Regex("&#[xX]([0-9a-fA-F]+);").replace(result) { match ->
        match.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: match.value
    }
    result = Regex("&[a-zA-Z][a-zA-Z0-9]*;").replace(result, "")
    return result
}

/**
 * Resolve [rawPath] against the sandbox [ToolContext.cwd] and refuse anything
 * that normalizes outside of it. This is the single choke point every
 * path-taking tool goes through.
 *
 * The lexical `..`/absolute check is necessary but not sufficient: `normalize()`
 * never touches the filesystem, so a symlink inside cwd can point anywhere.
 * [requireRealInside] closes that hole by canonicalizing the deepest existing
 * ancestor of the candidate.
 */
internal fun resolveInsideCwd(ctx: ToolContext, rawPath: String): Path {
    val cwd = ctx.cwd.toAbsolutePath().normalize()
    val resolved = cwd.resolve(rawPath).normalize()
    if (resolved != cwd && !resolved.startsWith(cwd)) {
        throw IllegalArgumentException("Path escapes the working directory: $rawPath")
    }
    requireRealInside(cwd, resolved, rawPath)
    return resolved
}

/**
 * Canonical symlink guard: resolve the deepest *existing* ancestor of
 * [candidate] with [Path.toRealPath] and require it to equal or live under the
 * real path of [cwd]. A symlinked ancestor that escapes the sandbox is rejected;
 * a symlink that still resolves back inside cwd is allowed. Any failure to
 * canonicalize counts as an escape.
 */
internal fun requireRealInside(cwd: Path, candidate: Path, rawPath: String) {
    val realCwd = try {
        cwd.toRealPath()
    } catch (e: Exception) {
        throw IllegalArgumentException("Path escapes the working directory: $rawPath")
    }
    var ancestor: Path? = candidate
    while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
        ancestor = ancestor.parent
    }
    val realAncestor = try {
        ancestor?.toRealPath()
    } catch (e: Exception) {
        null
    } ?: throw IllegalArgumentException("Path escapes the working directory: $rawPath")

    if (realAncestor != realCwd && !realAncestor.startsWith(realCwd)) {
        throw IllegalArgumentException("Path escapes the working directory: $rawPath")
    }
}

/** True when the path links to a symlink or a non-regular file (FIFO, socket, device). */
internal fun isNonRegularOrSymlink(path: Path): Boolean =
    Files.isSymbolicLink(path) || !Files.isRegularFile(path)

private val BINARY_EXTENSIONS = listOf(
    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".pdf", ".zip", ".gz", ".tar",
    ".jar", ".class", ".so", ".dylib", ".dll", ".exe", ".bin", ".woff", ".woff2",
    ".ttf", ".otf", ".mp3", ".mp4", ".mov", ".avi", ".wasm",
)

/**
 * Cheap binary sniff shared by `read` and `grep`: a known binary extension or a
 * NUL byte in the first [Limits.BINARY_SNIFF_BYTES] bytes. Failures are treated
 * as binary so an unreadable file is never streamed as text.
 */
internal fun looksBinary(path: Path): Boolean {
    val name = path.fileName?.toString()?.lowercase() ?: return true
    if (BINARY_EXTENSIONS.any { name.endsWith(it) }) return true
    return try {
        val bytes = Files.newInputStream(path).use { it.readNBytes(Limits.BINARY_SNIFF_BYTES) }
        bytes.any { it == 0.toByte() }
    } catch (e: Exception) {
        true
    }
}

/** Path relative to cwd, using forward slashes for stable display. */
internal fun Path.displayPath(cwd: Path): String {
    val base = cwd.toAbsolutePath().normalize()
    val self = toAbsolutePath().normalize()
    return if (self.startsWith(base)) {
        base.relativize(self).toString().replace('\\', '/')
    } else {
        toString().replace('\\', '/')
    }
}

internal fun JsonObject.stringOrNull(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.contentOrNull
}

internal fun JsonObject.requireString(key: String): String =
    stringOrNull(key) ?: throw IllegalArgumentException("Missing required string field '$key'")

internal fun JsonObject.intOrNull(key: String): Int? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.intOrNull
}

internal fun JsonObject.boolOrNull(key: String): Boolean? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return (element as? JsonPrimitive)?.booleanOrNull
}

internal fun JsonObject.stringList(key: String): List<String> {
    val array = this[key] as? JsonArray ?: return emptyList()
    return array.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.contentOrNull }
}

internal fun capNote(shown: Int, total: Int, what: String): String =
    if (total > shown) "\n\n…[${what}: showing $shown of $total]" else ""

/** Unified character-cap marker shared by the HTTP-backed tools. */
internal fun charCapNote(label: String, limit: Int): String =
    "\n\n…[$label: truncated at $limit chars]"

/** Line contents of [text], ignoring a single trailing newline. */
internal fun splitLines(text: String): List<String> =
    if (text.isEmpty()) emptyList() else text.removeSuffix("\n").split("\n")

/**
 * Record a whole-file snapshot before a mutation. Returns the snapshot id, or
 * null when no store is attached or the file does not exist yet (a creation has
 * nothing to revert to).
 */
internal suspend fun recordSnapshot(ctx: ToolContext, path: Path, relativePath: String): String? {
    val store = ctx.snapshots ?: return null
    if (!Files.isRegularFile(path)) return null
    val content = try {
        Files.readString(path, StandardCharsets.UTF_8)
    } catch (_: Exception) {
        return null
    }
    val snapshot = Snapshot(
        id = Ids.new("snap"),
        sessionId = ctx.sessionId,
        path = relativePath,
        content = content,
        sha256 = sha256(content),
        createdAt = System.currentTimeMillis(),
    )
    store.record(snapshot)
    return snapshot.id
}

/** A whole-file unified diff: every line removed then every line added. */
internal fun wholeFileDiff(relativePath: String, before: String, after: String): String {
    val old = splitLines(before)
    val new = splitLines(after)
    val builder = StringBuilder()
    builder.append("--- a/").append(relativePath).append('\n')
    builder.append("+++ b/").append(relativePath).append('\n')
    builder.append("@@ -1,").append(old.size).append(" +1,").append(new.size).append(" @@\n")
    old.forEach { builder.append('-').append(it).append('\n') }
    new.forEach { builder.append('+').append(it).append('\n') }
    return builder.toString()
}

private fun sha256(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
