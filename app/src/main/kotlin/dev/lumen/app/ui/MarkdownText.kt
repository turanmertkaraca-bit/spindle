package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spindle.core.refs.ReferenceResolver
import dev.spindle.core.refs.tag
import dev.spindle.core.text.Markdown
import dev.spindle.core.text.MdBlock
import dev.spindle.core.text.MdSpan

private val MdMono = FontFamily.Monospace

/** Annotation tags the linked runs carry; both sit on the reference range. */
private const val FILE_TAG = "file"
private const val FILE_LINE_TAG = "file-line"

/**
 * Layout bounds. Text layout (`StaticLayout`) is super-linear and allocation
 * heavy, so a multi-hundred-KB string must never be laid out as one Text on a
 * streaming frame:
 *
 *  - while a body is streaming only its tail ([STREAM_WINDOW] chars) is parsed
 *    and laid out, so growth stays bounded and the newest answer stays visible;
 *  - a finished body longer than [BODY_MAX] renders a bounded prefix until the
 *    reader asks for everything, which is then shown in a height-bounded scroll
 *    box laid out in [BODY_CHUNK]-char pieces.
 */
private const val STREAM_WINDOW = 6_000
private const val BODY_MAX = 8_000
private const val BODY_CHUNK = 4_000

/**
 * Split [text] into pieces of at most [size] characters, preferring a newline
 * break in the second half of a piece so prose stays readable. Shared with the
 * reasoning/literal windows, so no single Text ever sees an unbounded string.
 */
internal fun textChunks(text: String, size: Int): List<String> {
    if (text.length <= size) return listOf(text)
    val out = ArrayList<String>(text.length / size + 1)
    var start = 0
    while (start < text.length) {
        var end = (start + size).coerceAtMost(text.length)
        if (end < text.length) {
            val nl = text.lastIndexOf('\n', end - 1)
            if (nl > start + size / 2) end = nl + 1
        }
        out += text.substring(start, end)
        start = end
    }
    return out
}

/**
 * Renders assistant prose as markdown using [LumenColors] only. The parse is
 * pure and memoised on the source string, so streaming updates re-parse at most
 * once per body change. User turns and reasoning stay plain text elsewhere.
 *
 * When [cwd] is non-empty, inline spans are scanned by [ReferenceResolver] and
 * every existing file reference becomes an accent-underlined tap target calling
 * [onOpenFile]. Fenced code blocks stay inert (they are rendered raw, never
 * scanned). [exists] gates candidates (no dead taps); [touchedPaths] marks
 * references to files changed this run with a subtly stronger weight.
 */
@Composable
fun MarkdownBody(
    markdown: String,
    colors: LumenColors,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
    cwd: String = "",
    exists: (String) -> Boolean = { false },
    touchedPaths: Set<String> = emptySet(),
    onOpenFile: (String, Int?) -> Unit = { _, _ -> },
) {
    // The parse is memoised on whatever string is actually laid out, never on
    // the unbounded source. While streaming we window to the tail so the newest
    // text is what grows on screen; once finished an oversized body windows to a
    // bounded prefix with an explicit opt-in to the whole thing.
    val cap = if (streaming) STREAM_WINDOW else BODY_MAX
    var showFull by remember(markdown) { mutableStateOf(false) }

    when {
        markdown.length <= cap -> {
            val blocks = remember(markdown) { Markdown.parse(markdown) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkdownBlocks(blocks, colors, cwd, exists, touchedPaths, onOpenFile)
            }
        }

        streaming -> {
            val shown = "\u2026\n" + markdown.takeLast(cap)
            val blocks = remember(shown) { Markdown.parse(shown) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkdownBlocks(blocks, colors, cwd, exists, touchedPaths, onOpenFile)
            }
        }

        showFull -> {
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Column {
                        for (chunk in textChunks(markdown, BODY_CHUNK)) {
                            Text(
                                chunk,
                                color = colors.fg,
                                fontFamily = MdMono,
                                fontSize = 14.sp,
                                lineHeight = 21.sp,
                            )
                        }
                    }
                }
                Text(
                    "show less",
                    color = colors.accent, fontFamily = MdMono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .border(1.dp, colors.rule, LumenShapes.small)
                        .clickable { showFull = false }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("md-show-less"),
                )
            }
        }

        else -> {
            val shown = markdown.take(cap)
            val blocks = remember(shown) { Markdown.parse(shown) }
            Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkdownBlocks(blocks, colors, cwd, exists, touchedPaths, onOpenFile)
                Text(
                    "\u2026[${markdown.length - cap} chars hidden] \u00b7 show full",
                    color = colors.accent, fontFamily = MdMono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .border(1.dp, colors.rule, LumenShapes.small)
                        .clickable { showFull = true }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("md-show-full"),
                )
            }
        }
    }
}

/** Draws parsed blocks in order; shared by every [MarkdownBody] window. */
@Composable
private fun MarkdownBlocks(
    blocks: List<MdBlock>,
    colors: LumenColors,
    cwd: String,
    exists: (String) -> Boolean,
    touchedPaths: Set<String>,
    onOpenFile: (String, Int?) -> Unit,
) {
    for (block in blocks) {
        when (block) {
            is MdBlock.Heading -> MarkdownHeading(block, colors)
            is MdBlock.Paragraph -> MarkdownParagraph(block.spans, colors, cwd, exists, touchedPaths, onOpenFile)
            is MdBlock.Bullet -> MarkdownList(block.items, colors, ordered = false, cwd = cwd, exists = exists, touchedPaths = touchedPaths, onOpenFile = onOpenFile)
            is MdBlock.Ordered -> MarkdownList(block.items, colors, ordered = true, cwd = cwd, exists = exists, touchedPaths = touchedPaths, onOpenFile = onOpenFile)
            is MdBlock.Code -> MarkdownCode(block, colors)
        }
    }
}

@Composable
private fun MarkdownHeading(block: MdBlock.Heading, colors: LumenColors) {
    val size = when (block.level) {
        1 -> 19.sp
        2 -> 17.sp
        3 -> 15.5.sp
        else -> 14.5.sp
    }
    Text(
        block.text,
        color = colors.fg,
        fontFamily = MdMono,
        fontSize = size,
        lineHeight = size * 1.35f,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.testTag("md-heading"),
    )
}

@Composable
private fun MarkdownParagraph(
    spans: List<MdSpan>,
    colors: LumenColors,
    cwd: String,
    exists: (String) -> Boolean,
    touchedPaths: Set<String>,
    onOpenFile: (String, Int?) -> Unit,
) {
    // Resolving references stats the filesystem, so memoise the annotated run on
    // everything that can change its result — NOT on the (unstable) `exists`
    // lambda, which is re-created on every parent recomposition. `rememberUpdated`
    // keeps the latest gate without invalidating the memo.
    val gate by rememberUpdatedState(exists)
    val annotated = remember(spans, colors, cwd, touchedPaths) {
        markdownAnnotated(spans, colors, cwd, gate, touchedPaths)
    }
    LinkedText(
        text = annotated,
        colors = colors,
        fontSize = 14.sp,
        lineHeight = 21.sp,
        onOpenFile = onOpenFile,
    )
}

@Composable
private fun MarkdownList(
    items: List<List<MdSpan>>,
    colors: LumenColors,
    ordered: Boolean,
    cwd: String,
    exists: (String) -> Boolean,
    touchedPaths: Set<String>,
    onOpenFile: (String, Int?) -> Unit,
) {
    val gate by rememberUpdatedState(exists)
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((index, item) in items.withIndex()) {
            val annotated = remember(item, colors, cwd, touchedPaths) {
                markdownAnnotated(item, colors, cwd, gate, touchedPaths)
            }
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (ordered) "${index + 1}." else "\u2022",
                    color = colors.water,
                    fontFamily = MdMono,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Spacer(Modifier.width(8.dp))
                LinkedText(
                    text = annotated,
                    colors = colors,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    onOpenFile = onOpenFile,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun MarkdownCode(block: MdBlock.Code, colors: LumenColors) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.inset)
            .background(colors.bg.copy(alpha = 0.55f))
            .border(1.dp, colors.rule, LumenShapes.inset)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        val lang = block.lang
        if (lang != null) {
            Text(
                lang,
                color = colors.faint,
                fontFamily = MdMono,
                fontSize = 10.sp,
                letterSpacing = 0.6.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
        Text(
            block.text.ifEmpty { " " },
            color = colors.fg,
            fontFamily = MdMono,
            fontSize = 12.5.sp,
            lineHeight = 18.sp,
            softWrap = false,
            modifier = Modifier.testTag("md-code"),
        )
    }
}

/**
 * A markdown run that may carry file-reference annotations. Taps are mapped
 * back to the tapped character offset through the [TextLayoutResult], so only
 * the reference range responds; when nothing is linked this is a plain [Text].
 * A click semantics action is exposed so the link is reachable (and testable)
 * from accessibility even though it is not a separate node.
 */
@Composable
private fun LinkedText(
    text: AnnotatedString,
    colors: LumenColors,
    onOpenFile: (String, Int?) -> Unit,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 21.sp,
) {
    val refs = text.getStringAnnotations(FILE_TAG, 0, text.length)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    fun refAt(offset: Int): Pair<String, Int?>? {
        val path = text.getStringAnnotations(FILE_TAG, offset, offset).firstOrNull()?.item ?: return null
        val line = text.getStringAnnotations(FILE_LINE_TAG, offset, offset).firstOrNull()?.item?.toIntOrNull()
        return path to line
    }

    val openFirst: () -> Boolean = {
        val first = refs.firstOrNull()
        if (first == null) {
            false
        } else {
            val line = text.getStringAnnotations(FILE_LINE_TAG, first.start, first.end)
                .firstOrNull()?.item?.toIntOrNull()
            onOpenFile(first.item, line)
            true
        }
    }

    Text(
        text = text,
        color = colors.fg,
        fontFamily = MdMono,
        fontSize = fontSize,
        lineHeight = lineHeight,
        onTextLayout = { layout = it },
        modifier = modifier.then(
            if (refs.isEmpty()) {
                Modifier
            } else {
                Modifier
                    .pointerInput(text) {
                        detectTapGestures { pos ->
                            val l = layout ?: return@detectTapGestures
                            refAt(l.getOffsetForPosition(pos))?.let { (path, line) -> onOpenFile(path, line) }
                        }
                    }
                    .semantics { onClick(label = "open file reference") { openFirst() } }
            },
        ),
    )
}

/**
 * Builds the annotated run for a list of inline spans, palette-aware. Each span
 * is scanned independently for file references (so offsets stay local), then
 * the matches are underlined, annotated with their cwd-relative path + line,
 * and — for files touched this run — given a slightly heavier weight.
 */
private fun markdownAnnotated(
    spans: List<MdSpan>,
    colors: LumenColors,
    cwd: String,
    exists: (String) -> Boolean,
    touchedPaths: Set<String>,
): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val spanStart = length
        val link = span.linkUrl != null
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            color = when {
                link -> colors.accent
                span.code -> colors.water
                else -> Color.Unspecified
            },
            background = if (span.code) colors.bg.copy(alpha = 0.6f) else Color.Unspecified,
            textDecoration = if (link) TextDecoration.Underline else null,
        )
        withStyle(style) { append(span.text) }

        if (cwd.isEmpty() || span.text.isEmpty()) continue
        val refs = tag(ReferenceResolver.resolve(span.text, cwd, exists), touchedPaths)
        for (ref in refs) {
            val from = spanStart + ref.start
            val to = spanStart + ref.end
            if (from < 0 || to > length || from >= to) continue
            addStyle(
                SpanStyle(
                    color = colors.accent,
                    textDecoration = TextDecoration.Underline,
                    fontWeight = if (ref.touched) FontWeight.SemiBold else null,
                ),
                from,
                to,
            )
            addStringAnnotation(FILE_TAG, ref.path, from, to)
            addStringAnnotation(FILE_LINE_TAG, ref.line?.toString() ?: "", from, to)
        }
    }
}
