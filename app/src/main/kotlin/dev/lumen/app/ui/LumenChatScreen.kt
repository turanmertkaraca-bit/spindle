package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep

private val Mono = FontFamily.Monospace

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
            bg = Color(0xFFFCFCFB), fg = Color(0xFF141414), dim = Color(0xFF747474),
            faint = Color(0xFFB4B4B4), rule = Color(0xFFECECEC), accent = Color(0xFF3B82F6),
            spectrum = listOf(
                Color(0xFFFF4B4B), Color(0xFFFF9F1A), Color(0xFFF5D000),
                Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFF8B5CF6),
            ),
        )
        val Dark = LumenColors(
            bg = Color(0xFF0B0B0E), fg = Color(0xFFF2F2F5), dim = Color(0xFF9A9AA4),
            faint = Color(0xFF55555F), rule = Color(0xFF232329), accent = Color(0xFF6EA8FF),
            spectrum = Light.spectrum,
        )
    }
}

/**
 * The whole screen: the timeline above, one-line composer below.
 *
 * The row at the bottom of the viewport is expanded (see the pure
 * [dev.spindle.core.ui.TimelineLayout]); tapping a row opens it explicitly.
 */
@Composable
fun LumenChatScreen(
    steps: List<UiStep>,
    input: String,
    busy: Boolean,
    error: String?,
    colors: LumenColors = LumenColors.Light,
    modifier: Modifier = Modifier,
    onInput: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    var scrollOpen by remember(steps.size) { mutableIntStateOf(steps.lastIndex) }
    var pinned by remember(steps.size) { mutableIntStateOf(-1) }   // user-tapped row

    LaunchedEffect(listState, steps.size) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) steps.lastIndex
            else {
                val end = info.viewportEndOffset
                visible.lastOrNull { it.offset < end }?.index ?: visible.last().index
            }
        }.collect { scrollOpen = it; pinned = -1 }
    }

    // follow new content only when already at the bottom
    LaunchedEffect(steps.size) {
        if (steps.isEmpty()) return@LaunchedEffect
        val info = listState.layoutInfo
        val atBottom = info.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (atBottom >= steps.size - 2) {
            listState.animateScrollToItem(steps.lastIndex)
        }
    }

    val openIndex = if (pinned >= 0) pinned else scrollOpen

    Column(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().testTag("timeline"),
            ) {
                itemsIndexed(steps) { i, step ->
                    StepRow(
                        step = step,
                        open = i == openIndex,
                        colors = colors,
                        onTap = { pinned = if (pinned == i) -1 else i },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        if (error != null) {
            Text(
                error, color = colors.fg, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Composer(input, busy, colors, onInput, onSend, onStop)
    }
}

@Composable
private fun StepRow(step: UiStep, open: Boolean, colors: LumenColors, onTap: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(vertical = 6.dp)
            .clickable(onClick = onTap),
        verticalAlignment = Alignment.Top,
    ) {
        Beam(step, open, colors)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.fillMaxWidth().padding(end = 14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    step.label,
                    color = if (open) colors.fg else colors.faint,
                    fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.5.sp,
                )
                Text(
                    step.tag,
                    color = if (open) colors.dim else colors.faint.copy(alpha = 0.45f),
                    fontFamily = Mono, fontSize = 11.sp,
                )
            }
            Text(
                text = if (open) step.body else step.summary,
                color = if (open) colors.fg else colors.faint,
                fontFamily = Mono, fontSize = 14.sp,
                maxLines = if (open) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (open && step.rows.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                for ((k, r) in step.rows.withIndex()) {
                    Row(Modifier.padding(start = 12.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(RoundedCornerShape(2.dp))
                                .background(colors.spectrum[k % colors.spectrum.size]),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(r.first, color = colors.spectrum[k % colors.spectrum.size], fontFamily = Mono, fontSize = 13.sp)
                        if (r.second.isNotEmpty()) {
                            Spacer(Modifier.width(10.dp))
                            Text(r.second, color = colors.dim, fontFamily = Mono, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Beam(step: UiStep, open: Boolean, colors: LumenColors) {
    Box(Modifier.width(28.dp), contentAlignment = Alignment.TopCenter) {
        val shape = when (step.kind) {
            StepKind.QUESTION -> RoundedCornerShape(1.dp)
            StepKind.SUBAGENT -> RoundedCornerShape(3.dp, 8.dp, 8.dp, 3.dp)
            else -> CircleShape
        }
        val fill = when {
            step.failed -> colors.spectrum.first()
            step.running -> colors.bg
            open -> colors.fg
            else -> colors.faint
        }
        val ring = step.running
        Box(
            Modifier
                .padding(top = 4.dp)
                .size(if (open) 11.dp else 9.dp)
                .clip(shape)
                .background(fill)
                .then(if (ring) Modifier.border(2.dp, colors.fg, shape) else Modifier),
        )
    }
}

@Composable
private fun Composer(
    input: String,
    busy: Boolean,
    colors: LumenColors,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(colors.bg)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(">", color = colors.faint, fontFamily = Mono, fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (input.isEmpty()) {
                Text("ask the agent…", color = colors.faint, fontFamily = Mono, fontSize = 15.sp)
            }
            BasicTextField(
                value = input,
                onValueChange = onInput,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 15.sp),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth().testTag("composer"),
            )
        }
        Spacer(Modifier.width(8.dp))
        val label = if (busy) "stop" else "send"
        Text(
            label,
            color = colors.accent,
            fontFamily = Mono,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.clickable { if (busy) onStop() else onSend() }.testTag(if (busy) "stop" else "send"),
        )
    }
}

