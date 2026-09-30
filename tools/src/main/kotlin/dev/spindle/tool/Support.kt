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
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Shared caps so tool output can never blow up the model context. */
internal object Limits {
    const val READ_MAX_LINES = 2000
    const val GLOB_MAX_RESULTS = 1000
    const val GREP_MAX_RESULTS = 1000
    const val BINARY_SNIFF_BYTES = 8000
    const val BASH_MAX_OUTPUT_CHARS = 50_000
    const val WEB_MAX_CHARS = 20_000
}

/**
 * Resolve [rawPath] against the sandbox [ToolContext.cwd] and refuse anything
 * that normalizes outside of it. This is the single choke point every
 * path-taking tool goes through.
 */
internal fun resolveInsideCwd(ctx: ToolContext, rawPath: String): Path {
    val cwd = ctx.cwd.toAbsolutePath().normalize()
    val resolved = cwd.resolve(rawPath).normalize()
    if (resolved != cwd && !resolved.startsWith(cwd)) {
        throw IllegalArgumentException("Path escapes the working directory: $rawPath")
    }
    return resolved
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
