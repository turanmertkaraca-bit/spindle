package dev.spindle.core.refs

/**
 * What the caller's existence gate found at a candidate path: a regular file or
 * a directory. Returning `null` from a gate means "nothing there", so the
 * mention stays inert.
 */
enum class FileKind { FILE, DIRECTORY }

/**
 * One file or directory reference found in assistant text.
 *
 * [raw] is the target exactly as written (`src/App.kt:42`, `x.kt#L10-L20`),
 * [path] is the cwd-relative path after normalization, and [start]/[end] are
 * character offsets into the ORIGINAL text so the UI can style that range.
 * [line]/[endLine]/[column] carry the typed range when the model wrote one.
 * [isDir] is true when the gate reported a directory (a trailing `/` mention
 * like `core/` or a plain `core/src` that resolves to one). [touched] is set
 * later by [tag]: a file changed this run opens a diff, an untouched mention
 * opens the file.
 */
data class FileReference(
    val raw: String,
    val path: String,
    val start: Int,
    val end: Int,
    val line: Int? = null,
    val endLine: Int? = null,
    val column: Int? = null,
    val touched: Boolean = false,
    val isDir: Boolean = false,
)

/**
 * Pure, JVM-testable resolver that turns assistant text into tappable file
 * references. Fenced code blocks are inert; inline `code` spans and bare
 * path-shaped tokens are candidates. A candidate only survives when the
 * caller's [resolve]-supplied existence gate says the cwd-relative path is a
 * real file, so the UI never renders a dead tap. Follows the old `Mentions`
 * shape filter, extended with typed `:line`/`#L` targets.
 */
object ReferenceResolver {

    private val ANCHOR = Regex("""^(.+?)#[Ll](\d+)(?:-[Ll]?(\d+))?$""")
    private val LINE_COL = Regex("""^(.+?):(\d+)(?::(\d+))?$""")
    private val WINDOWS_DRIVE = Regex("""^[A-Za-z]:""")

    /**
     * Resolve every linkable reference in [text], treating a gate hit as a real
     * file. Signature and behavior are unchanged from the original boolean
     * gate, so existing callers (including trailing-lambda ones) keep working:
     * directory mentions are simply never produced. Use [resolveKinds] when the
     * gate can also report directories.
     */
    fun resolve(text: String, cwd: String, exists: (String) -> Boolean): List<FileReference> {
        val kindGate: (String) -> FileKind? = { rel -> if (exists(rel)) FileKind.FILE else null }
        return resolveKinds(text, cwd, kindGate)
    }

    /**
     * Resolve every linkable reference in [text], with the gate reporting what
     * the target is. [exists] is given a cwd-relative path and returns
     * [FileKind.FILE], [FileKind.DIRECTORY], or `null` when nothing is there;
     * a directory puts a trailing `/` mention (or a separator-bearing path that
     * resolves to a directory) on the result with [FileReference.isDir] set.
     * The gate is the sole authority on symlinks: a path whose real target
     * escapes [cwd] must be refused by it.
     *
     * Named [resolveKinds] rather than an overload of [resolve]: Kotlin resolves
     * by the lambda's arity, not its return type, so two `resolve` overloads
     * differing only in `Boolean` vs `FileKind?` would make existing trailing
     * lambdas ambiguous.
     */
    fun resolveKinds(text: String, cwd: String, exists: (String) -> FileKind?): List<FileReference> {
        if (text.isEmpty()) return emptyList()
        val inert = inertMap(text)
        val out = ArrayList<FileReference>()
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (inert[i] || !isPathChar(c) || c == '.') {
                i++
                continue
            }
            var j = i
            while (j < n && !inert[j] && isTokenChar(text[j])) j++
            var end = j
            while (end > i && text[end - 1] in ".:#") end--
            if (end > i) parse(text.substring(i, end), i, end, cwd, exists)?.let { out.add(it) }
            i = if (j > i) j else i + 1
        }
        return out
    }

    private fun parse(
        token: String,
        start: Int,
        end: Int,
        cwd: String,
        exists: (String) -> FileKind?,
    ): FileReference? {
        var pathText = token
        var line: Int? = null
        var endLine: Int? = null
        var column: Int? = null

        val anchor = ANCHOR.matchEntire(token)
        val lineCol = if (anchor == null) LINE_COL.matchEntire(token) else null
        if (anchor != null) {
            val l = anchor.groupValues[2].toIntOrNull()
            if (l != null) {
                pathText = anchor.groupValues[1]
                line = l
                endLine = anchor.groupValues[3].toIntOrNull()
            }
        } else if (lineCol != null) {
            val l = lineCol.groupValues[2].toIntOrNull()
            if (l != null) {
                pathText = lineCol.groupValues[1]
                line = l
                column = lineCol.groupValues[3].toIntOrNull()
            }
        }

        if (!plausible(pathText)) return null
        val rel = resolveRelative(pathText, cwd) ?: return null
        val kind = exists(rel) ?: return null
        return FileReference(
            raw = token,
            path = rel,
            start = start,
            end = end,
            line = line,
            endLine = endLine,
            column = column,
            isDir = kind == FileKind.DIRECTORY,
        )
    }

    /**
     * Shape filter run BEFORE the existence gate. A token must contain a path
     * separator or a dot-extension, carry no whitespace, and not be a URL,
     * protocol-relative, Windows-drive or all-dots/stubs candidate. A trailing
     * `/` is allowed: directory-shaped mentions are candidates and the caller's
     * gate decides whether the target really is a directory.
     */
    private fun plausible(p: String): Boolean {
        if (p.length < 3 || p.length > 300) return false
        if (p.any { it.isWhitespace() }) return false
        if (p.startsWith("//") || p.contains("://")) return false
        if (WINDOWS_DRIVE.containsMatchIn(p)) return false
        if (p.startsWith("/") && p.indexOf('/', 1) < 0) return false
        if (p.all { it == '.' || it == '/' }) return false
        if (!p.contains('/')) {
            val dot = p.lastIndexOf('.')
            if (dot <= 0 || dot == p.length - 1) return false
            val ext = p.substring(dot + 1)
            if (ext.length > 10) return false
            if (ext.none { it in 'a'..'z' || it in 'A'..'Z' }) return false
        }
        return true
    }

    /**
     * Turn a candidate into a normalized cwd-relative path, or null when it
     * cannot live inside the project. Absolute candidates must sit under
     * [cwd]; backslashes are normalized; `..` that climbs above cwd is
     * rejected without an existence check.
     */
    private fun resolveRelative(raw: String, cwd: String): String? {
        var p = raw.replace('\\', '/')
        while (p.startsWith("./")) p = p.substring(2)
        if (p.isEmpty()) return null

        val base = cwd.replace('\\', '/').trimEnd('/')
        val rel = if (p.startsWith("/")) {
            when {
                base.isEmpty() -> p.substring(1)
                p.startsWith("$base/") -> p.substring(base.length + 1)
                else -> return null
            }
        } else p

        val out = ArrayList<String>()
        for (seg in rel.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> {
                    if (out.isEmpty()) return null
                    out.removeAt(out.size - 1)
                }
                else -> out.add(seg)
            }
        }
        if (out.isEmpty()) return null
        return out.joinToString("/")
    }

    private fun inertMap(text: String): BooleanArray {
        val inert = BooleanArray(text.length)
        markFences(text, inert)
        markMarkdownTargets(text, inert)
        return inert
    }

    private fun markFences(text: String, inert: BooleanArray) {
        var lineStart = 0
        var inFence = false
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            val isFence = text.substring(lineStart, lineEnd).trimStart().startsWith("```")
            if (inFence || isFence) {
                for (k in lineStart until lineEnd) inert[k] = true
            }
            if (isFence) inFence = !inFence
            if (nl < 0) break
            lineStart = nl + 1
        }
    }

    private fun markMarkdownTargets(text: String, inert: BooleanArray) {
        var i = text.indexOf("](")
        while (i >= 0) {
            val open = i + 1
            val close = text.indexOf(')', open + 1)
            if (close < 0) break
            for (k in open until close) inert[k] = true
            i = text.indexOf("](", close + 1)
        }
    }

    private fun isPathChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
            c == '/' || c == '\\' || c == '.' || c == '_' ||
            c == '-' || c == '+' || c == '#'

    private fun isTokenChar(c: Char): Boolean = isPathChar(c) || c == ':'
}

/**
 * Mark references whose resolved path was changed this run. Touched → open a
 * diff; untouched → open the file. Pure: returns a new list, input untouched.
 * Paths are compared after backslash/`./` normalization so either spelling of
 * a touched path matches.
 */
fun tag(references: List<FileReference>, touchedPaths: Set<String>): List<FileReference> {
    if (references.isEmpty() || touchedPaths.isEmpty()) return references
    val normalized = touchedPaths.map { it.replace('\\', '/').removePrefix("./") }.toSet()
    return references.map { ref ->
        if (ref.touched || ref.path in normalized) ref.copy(touched = true) else ref
    }
}
