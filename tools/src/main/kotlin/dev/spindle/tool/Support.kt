package dev.spindle.tool

import dev.spindle.core.tool.ToolContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.Path

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
