package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spindle.core.text.Markdown
import dev.spindle.core.text.MdBlock
import dev.spindle.core.text.MdSpan

private val MdMono = FontFamily.Monospace

/**
 * Renders assistant prose as markdown using [LumenColors] only. The parse is
 * pure and memoised on the source string, so streaming updates re-parse at most
 * once per body change. User turns and reasoning stay plain text elsewhere.
 */
@Composable
fun MarkdownBody(markdown: String, colors: LumenColors, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { Markdown.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Heading -> MarkdownHeading(block, colors)
                is MdBlock.Paragraph -> MarkdownParagraph(block.spans, colors)
                is MdBlock.Bullet -> MarkdownList(block.items, colors, ordered = false)
                is MdBlock.Ordered -> MarkdownList(block.items, colors, ordered = true)
                is MdBlock.Code -> MarkdownCode(block, colors)
            }
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
private fun MarkdownParagraph(spans: List<MdSpan>, colors: LumenColors) {
    Text(
        markdownAnnotated(spans, colors),
        color = colors.fg,
        fontFamily = MdMono,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    )
}

@Composable
private fun MarkdownList(items: List<List<MdSpan>>, colors: LumenColors, ordered: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        for ((index, item) in items.withIndex()) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    if (ordered) "${index + 1}." else "\u2022",
                    color = colors.water,
                    fontFamily = MdMono,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    markdownAnnotated(item, colors),
                    color = colors.fg,
                    fontFamily = MdMono,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
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
            .clip(RoundedCornerShape(9.dp))
            .background(colors.bg.copy(alpha = 0.55f))
            .border(1.dp, colors.rule, RoundedCornerShape(9.dp))
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

/** Builds the annotated run for a list of inline spans, palette-aware. */
private fun markdownAnnotated(spans: List<MdSpan>, colors: LumenColors): AnnotatedString =
    buildAnnotatedString {
        for (span in spans) {
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
        }
    }
