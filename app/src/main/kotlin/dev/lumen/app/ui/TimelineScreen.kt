package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep

private val Mono = FontFamily.Monospace

/** A palette. Light is the default; dark is "a dark room with one beam". */
data class LumenColors(
    val bg: Color,
    val fg: Color,
    val dim: Color,
    val faint: Color,
    val rule: Color,
    val accent: Color,
    val spectrum: List<Color>,
) {
    companion object {
        val Light = LumenColors(
            bg = Color(0xFFFCFCFB),
            fg = Color(0xFF141414),
            dim = Color(0xFF747474),
            faint = Color(0xFFB4B4B4),
            rule = Color(0xFFECECEC),
            accent = Color(0xFF3B82F6),
            spectrum = listOf(
                Color(0xFFFF4B4B), Color(0xFFFF9F1A), Color(0xFFF5D000),
                Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFF8B5CF6),
            ),
        )
        val Dark = LumenColors(
            bg = Color(0xFF0B0B0E),
            fg = Color(0xFFF2F2F5),
            dim = Color(0xFF9A9AA4),
            faint = Color(0xFF55555F),
            rule = Color(0xFF232329),
            accent = Color(0xFF6EA8FF),
            spectrum = Light.spectrum,
        )
    }
}

/**
 * The timeline. One column, a beam down the left, every row a single height
 * when collapsed. The row at the bottom of the viewport is expanded — this is
 * computed by [dev.spindle.core.ui.TimelineLayout] as a pure function of scroll,
 * so it is exact and cannot jitter.
 *
 * For this first build the "which row is open" decision uses LazyColumn's own
 * index-at-bottom rather than pixel math, because that is what Compose gives us
 * for free and it matches the pure model's rule.
 */
@Composable
fun TimelineScreen(
    steps: List<UiStep>,
    colors: LumenColors = LumenColors.Light,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // The row at the bottom edge of the viewport is the open one.
    //
    // Rule (matches dev.spindle.core.ui.TimelineLayout): among the rows currently
    // laid out, take the last one whose top starts above the viewport bottom —
    // that is the row the eye is resting on. Everything else collapses.
    //
    // Before the first layout pass `visibleItemsInfo` is empty; default to the
    // newest row so a freshly-opened short chat still shows its latest step.
    val openIndex by remember(steps.size) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) {
                steps.lastIndex
            } else {
                val end = info.viewportEndOffset
                visible.lastOrNull { it.offset < end }?.index ?: visible.last().index
            }
        }
    }

    Box(modifier.fillMaxWidth().background(colors.bg)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().testTag("timeline"),
        ) {
            itemsIndexed(steps) { i, step ->
                StepRow(
                    step = step,
                    open = i == openIndex,
                    colors = colors,
                )
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun StepRow(step: UiStep, open: Boolean, colors: LumenColors) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Beam(step, open, colors)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth()) {
            // label row
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    step.label,
                    color = if (open) colors.fg else colors.faint,
                    fontFamily = Mono,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    step.tag,
                    color = if (open) colors.dim else colors.faint.copy(alpha = 0.45f),
                    fontFamily = Mono,
                    fontSize = 11.sp,
                )
            }
            Text(
                text = if (open) step.body else step.summary,
                color = if (open) colors.fg else colors.faint,
                fontFamily = Mono,
                fontSize = 14.sp,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (open && step.rows.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for ((k, r) in step.rows.withIndex()) {
                    Row(Modifier.padding(start = 12.dp, top = 2.dp)) {
                        Box(
                            Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                                .background(colors.spectrum[k % colors.spectrum.size])
                                .align(Alignment.CenterVertically),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            r.first,
                            color = colors.spectrum[k % colors.spectrum.size],
                            fontFamily = Mono,
                            fontSize = 13.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(r.second, color = colors.dim, fontFamily = Mono, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun Beam(step: UiStep, open: Boolean, colors: LumenColors) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.TopCenter) {
        // node
        val shape = when (step.kind) {
            StepKind.QUESTION -> RoundedCornerShape(1.dp)
            StepKind.SUBAGENT -> RoundedCornerShape(3.dp, 8.dp, 8.dp, 3.dp)
            else -> CircleShape
        }
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(if (open) 11.dp else 9.dp)
                .clip(shape)
                .background(if (open) colors.fg else colors.faint),
        )
    }
}
