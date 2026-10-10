package dev.spindle.core.tool

/**
 * Glob matching for persisted approval patterns. `**` matches any characters
 * including `/` and whitespace; `*` matches any characters except `/`; `?`
 * matches one character except `/`. Every other character — including regex
 * metacharacters — is matched literally. Matching is case-sensitive, a pattern
 * with no wildcard is plain string equality, and the call never throws (an
 * unparseable pattern degrades to exact equality).
 */
object ApprovalRules {
    /** Regex metacharacters that must be backslash-escaped to stay literal. */
    private const val META = "\\^$.|+()[]{}"

    fun matches(pattern: String, value: String): Boolean =
        runCatching { Regex(globToRegex(pattern)).matches(value) }
            .getOrDefault(pattern == value)

    /** Compile a glob to an anchored regex, escaping every non-wildcard rune. */
    private fun globToRegex(pattern: String): String {
        val out = StringBuilder(pattern.length + 8).append('^')
        var i = 0
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '*' -> if (i + 1 < pattern.length && pattern[i + 1] == '*') {
                    // `[\s\S]` rather than `.` so `**` also spans newlines.
                    out.append("[\\s\\S]*")
                    i++
                } else {
                    out.append("[^/]*")
                }
                '?' -> out.append("[^/]")
                else -> if (c in META) out.append('\\').append(c) else out.append(c)
            }
            i++
        }
        return out.append('$').toString()
    }
}
