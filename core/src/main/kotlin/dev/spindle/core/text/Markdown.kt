package dev.spindle.core.text

/**
 * The block-level shape of a parsed markdown document. Deliberately tiny: this
 * is the subset the assistant actually emits in chat (headings, paragraphs,
 * simple lists, fenced code), not a general CommonMark tree.
 */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Bullet(val items: List<List<MdSpan>>) : MdBlock
    data class Ordered(val items: List<List<MdSpan>>) : MdBlock
    data class Code(val lang: String?, val text: String) : MdBlock
}

/** One run of inline text, carrying its emphasis / code / link attributes. */
data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val linkUrl: String? = null,
)

/**
 * A small, zero-dependency markdown parser. Pure and deterministic, so it runs
 * on a plain JVM in tests and is cheap enough to re-run on every recomposition
 * of a streaming message.
 *
 * Supported: ATX headings `#`..`######`, bullet lists (`-`, `*`, `+`), ordered
 * lists (`1.` / `1)`), fenced code blocks (``` ``` ``` and `~~~`, optional info
 * string), paragraphs, and inline `**bold**` / `__bold__`, `*italic*` /
 * `_italic_`, `` `code` `` and `[text](url)`.
 *
 * Forgiving by design: an unclosed fence swallows the rest of the input as
 * code, and malformed inline markers pass through as literal text.
 */
object Markdown {

    private val BULLET = Regex("^\\s*[-*+]\\s+(.*)$")
    private val ORDERED = Regex("^\\s*\\d+[.)]\\s+(.*)$")

    private class Fence(val marker: Char, val info: String, val count: Int)

    fun parse(src: String): List<MdBlock> {
        if (src.isEmpty()) return emptyList()
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = ArrayList<MdBlock>()
        val paragraph = ArrayList<String>()
        var i = 0

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            val text = paragraph.joinToString("\n")
            if (text.isNotBlank()) out += MdBlock.Paragraph(parseInline(text))
            paragraph.clear()
        }

        while (i < lines.size) {
            val line = lines[i]

            val fence = fenceAt(line)
            if (fence != null) {
                flushParagraph()
                i++
                val code = ArrayList<String>()
                while (i < lines.size && !isFenceClose(lines[i], fence)) {
                    code += lines[i]
                    i++
                }
                if (i < lines.size) i++ // consume the closing fence when present
                val lang = fence.info.substringBefore(' ').takeIf { it.isNotBlank() }
                out += MdBlock.Code(lang, code.joinToString("\n"))
                continue
            }

            val level = headingLevel(line)
            if (level > 0) {
                flushParagraph()
                out += MdBlock.Heading(level, headingText(line, level))
                i++
                continue
            }

            if (BULLET.matches(line)) {
                flushParagraph()
                val items = ArrayList<List<MdSpan>>()
                while (i < lines.size && BULLET.matches(lines[i])) {
                    items += parseInline(BULLET.matchEntire(lines[i])!!.groupValues[1].trim())
                    i++
                }
                out += MdBlock.Bullet(items)
                continue
            }

            if (ORDERED.matches(line)) {
                flushParagraph()
                val items = ArrayList<List<MdSpan>>()
                while (i < lines.size && ORDERED.matches(lines[i])) {
                    items += parseInline(ORDERED.matchEntire(lines[i])!!.groupValues[1].trim())
                    i++
                }
                out += MdBlock.Ordered(items)
                continue
            }

            if (line.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            paragraph += line.trim()
            i++
        }

        flushParagraph()
        return out
    }

    /** A fence opener at [line], or null. Info strings are trimmed of spaces. */
    private fun fenceAt(line: String): Fence? {
        val t = line.trimStart()
        if (t.length < 3) return null
        val marker = t[0]
        if (marker != '`' && marker != '~') return null
        var n = 0
        while (n < t.length && t[n] == marker) n++
        if (n < 3) return null
        val info = t.substring(n).trim()
        if (marker == '`' && info.contains('`')) return null
        return Fence(marker, info, n)
    }

    private fun isFenceClose(line: String, fence: Fence): Boolean {
        val t = line.trim()
        if (t.isEmpty() || t[0] != fence.marker) return false
        var n = 0
        while (n < t.length && t[n] == fence.marker) n++
        if (n < fence.count) return false
        return t.substring(n).isBlank()
    }

    /**
     * ATX heading level, or 0. Up to three leading spaces are allowed, and a
     * space after the hashes is conventional but not required: models often emit
     * `##hello##`, and being forgiving reads better than showing raw markup.
     */
    private fun headingLevel(line: String): Int {
        var i = 0
        while (i < line.length && i < 3 && line[i] == ' ') i++
        var n = 0
        while (i + n < line.length && n < 6 && line[i + n] == '#') n++
        if (n == 0) return 0
        if (n == 6 && i + n < line.length && line[i + n] == '#') return 0
        return n
    }

    /**
     * Heading content. When there is a space after the opening hashes, only a
     * closing run preceded by a space is stripped, so `# C#` keeps its `#`.
     * Without that space the forgiving path strips any trailing run.
     */
    private fun headingText(line: String, level: Int): String {
        var i = 0
        while (i < line.length && i < 3 && line[i] == ' ') i++
        val raw = line.substring((i + level).coerceAtMost(line.length))
        val body = raw.trim()
        if (body.isEmpty()) return ""
        if (raw[0] == ' ') {
            val trimmed = body.trimEnd('#')
            return if (trimmed.length < body.length && (trimmed.isEmpty() || trimmed.last() == ' ')) {
                trimmed.trim()
            } else {
                body
            }
        }
        return body.trimEnd('#').trimEnd()
    }

    /**
     * Inline scan. Emphasis requires non-space content on both ends so that a
     * stray `*` or arithmetic like `2 * 3 * 4` passes through literally. For
     * `_`, left/right flanking is also checked so `snake_case` stays intact.
     */
    private fun parseInline(src: String): List<MdSpan> {
        val spans = ArrayList<MdSpan>()
        val plain = StringBuilder()

        fun flush() {
            if (plain.isNotEmpty()) {
                spans += MdSpan(plain.toString())
                plain.setLength(0)
            }
        }

        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c == '`' -> {
                    val end = src.indexOf('`', i + 1)
                    if (end > i + 1) {
                        flush()
                        spans += MdSpan(src.substring(i + 1, end), code = true)
                        i = end + 1
                    } else {
                        plain.append(c); i++
                    }
                }

                c == '[' -> {
                    val close = src.indexOf(']', i + 1)
                    if (close > i && close + 1 < src.length && src[close + 1] == '(') {
                        val urlEnd = src.indexOf(')', close + 2)
                        if (urlEnd > close) {
                            flush()
                            val label = src.substring(i + 1, close)
                            val url = src.substring(close + 2, urlEnd)
                            val inner = parseInline(label)
                            if (inner.isEmpty()) {
                                if (label.isNotEmpty()) spans += MdSpan(label, linkUrl = url)
                            } else {
                                for (s in inner) spans += s.copy(linkUrl = url)
                            }
                            i = urlEnd + 1
                        } else {
                            plain.append(c); i++
                        }
                    } else {
                        plain.append(c); i++
                    }
                }

                c == '*' && i + 1 < src.length && src[i + 1] == '*' -> {
                    val end = src.indexOf("**", i + 2)
                    val inner = if (end > i + 2) src.substring(i + 2, end) else null
                    if (inner != null && isEmphasisContent(inner)) {
                        flush()
                        spans += MdSpan(inner, bold = true)
                        i = end + 2
                    } else {
                        plain.append("**"); i += 2
                    }
                }

                c == '_' && i + 1 < src.length && src[i + 1] == '_' -> {
                    val end = src.indexOf("__", i + 2)
                    val inner = if (end > i + 2) src.substring(i + 2, end) else null
                    if (inner != null && isEmphasisContent(inner) &&
                        canOpenUnderscore(src, i) && canCloseUnderscore(src, end)
                    ) {
                        flush()
                        spans += MdSpan(inner, bold = true)
                        i = end + 2
                    } else {
                        plain.append("__"); i += 2
                    }
                }

                c == '*' -> {
                    val end = src.indexOf('*', i + 1)
                    val inner = if (end > i + 1) src.substring(i + 1, end) else null
                    if (inner != null && isEmphasisContent(inner)) {
                        flush()
                        spans += MdSpan(inner, italic = true)
                        i = end + 1
                    } else {
                        plain.append(c); i++
                    }
                }

                c == '_' -> {
                    val end = src.indexOf('_', i + 1)
                    val inner = if (end > i + 1) src.substring(i + 1, end) else null
                    if (inner != null && isEmphasisContent(inner) &&
                        canOpenUnderscore(src, i) && canCloseUnderscore(src, end)
                    ) {
                        flush()
                        spans += MdSpan(inner, italic = true)
                        i = end + 1
                    } else {
                        plain.append(c); i++
                    }
                }

                else -> {
                    plain.append(c); i++
                }
            }
        }

        flush()
        return spans
    }

    private fun isEmphasisContent(s: String): Boolean =
        s.isNotEmpty() && !s.first().isWhitespace() && !s.last().isWhitespace()

    private fun canOpenUnderscore(src: String, i: Int): Boolean {
        if (i > 0 && src[i - 1].isLetterOrDigit()) return false
        return i + 1 < src.length && !src[i + 1].isWhitespace()
    }

    private fun canCloseUnderscore(src: String, end: Int): Boolean {
        if (end > 0 && src[end - 1].isWhitespace()) return false
        return end + 1 >= src.length || !src[end + 1].isLetterOrDigit()
    }
}
