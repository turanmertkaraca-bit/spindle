package dev.lumen.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.ui.DropletShape
import dev.spindle.core.ui.FocusPolicy
import dev.spindle.core.ui.RopeCurve
import dev.spindle.core.ui.RopeLayout
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Mono = FontFamily.Monospace

/** How many lines a focused droplet shows while there is still rope below it. */
private const val LIMITED_LINES = 3

/** Light / dark palettes. Dark is deliberately not pure black (see bg). */
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
            // Non-OLED-friendly: a touch of light so pixels never fully switch off,
            // but dark enough to read as "dark", not gray.
            bg = Color(0xFF101014), fg = Color(0xFFF1F1F4), dim = Color(0xFF9A9AA4),
            faint = Color(0xFF565660), rule = Color(0xFF26262D), accent = Color(0xFF6EA8FF),
            spectrum = Light.spectrum,
        )
    }
}

/**
 * The timeline is a rope of droplets. Each step is a droplet on a static
 * vertical channel; the droplet at the focus line (center) blooms into its
 * text. Scrolling is the only navigation; double-tapping the bloomed text
 * copies it, long-press selects it.
 *
 * All placement math lives in [RopeLayout] / [FocusPolicy] / [DropletShape] /
 * [RopeCurve] in :core, so it is unit-tested on a plain JVM.
 */
@Composable
fun LumenChatScreen(
    steps: List<UiStep>,
    input: String,
    busy: Boolean,
    error: String?,
    colors: LumenColors = LumenColors.Light,
    modifier: Modifier = Modifier,
    forceOpenIndex: Int? = null,
    onInput: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onToggleTheme: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val nodeSizePx = with(density) { 10.dp.toPx() }
    val baseGapPx = with(density) { 26.dp.toPx() }
    val focusExtraPx = with(density) { 96.dp.toPx() }
    val gutterPx = with(density) { 24.dp.toPx() }
    val bowPx = with(density) { 9.dp.toPx() }
    val bowHalfPx = with(density) { 150.dp.toPx() }
    val metrics = remember(nodeSizePx, baseGapPx, focusExtraPx) {
        RopeLayout.Metrics(
            nodeSize = nodeSizePx,
            baseGap = baseGapPx,
            focusGapExtra = focusExtraPx,
            focusHalfWidth = 1.6f,
            focusY = 0f,
        )
    }
    val stride = metrics.stride

    val scrollState = rememberScrollState()
    val scrollValue = scrollState.value.toFloat()

    val count = steps.size
    val focusPos = if (count == 0) 0f else (scrollValue / stride).coerceIn(0f, (count - 1).toFloat())

    // No bloom while the rope is being flung; it "clicks into place" at rest.
    val scrolling = scrollState.isScrollInProgress
    val mode = if (forceOpenIndex != null || !scrolling) FocusPolicy.Mode.FOCUSED else FocusPolicy.Mode.FREE
    val focusedIndex = (forceOpenIndex ?: focusPos.roundToInt()).coerceIn(0, max(0, count - 1))
    val bloom by animateFloatAsState(
        targetValue = if (mode == FocusPolicy.Mode.FOCUSED) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "bloom",
    )

    // The rope sprouts into place the first time it has something to hold.
    val reveal by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow),
        label = "reveal",
    )
    // Moving rope stretches the droplets along it, then they settle.
    val stretch by animateFloatAsState(
        targetValue = if (scrolling) 1.35f else 1f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "stretch",
    )

    val nodes = remember(steps) {
        steps.map { RopeLayout.Node(key = it.id, bodyHeight = 0f, merged = it.merged) }
    }
    val result = remember(nodes, scrollValue, metrics) {
        RopeLayout.compute(nodes, scrollValue, metrics)
    }

    // start at the newest droplet without a visible jitter
    var didInit by remember { mutableStateOf(false) }
    LaunchedEffect(count) {
        if (!didInit && count > 0) {
            didInit = true
            scrollState.scrollTo(((count - 1) * stride).roundToInt())
        }
    }

    // click into place: when we settle, pull the target droplet onto the line.
    // The tolerance guard stops a zero-distance animation from looping forever.
    LaunchedEffect(scrolling, focusedIndex, count) {
        if (forceOpenIndex == null && !scrolling && count > 1) {
            val target = focusedIndex * stride
            if (abs(scrollState.value - target) > 2f) {
                scrollState.animateScrollTo(target.roundToInt())
            }
        }
    }

    val focusedStep = steps.getOrNull(focusedIndex)
    val fullText = focusedIndex >= count - 1
    val showPanel = focusedStep != null && (forceOpenIndex != null || bloom > 0.05f)

    Column(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeightPx = constraints.maxHeight.toFloat()
            val focusY = viewportHeightPx / 2f
            val maxScrollPx = (count - 1).coerceAtLeast(0) * stride
            val canvasHeightPx = viewportHeightPx + maxScrollPx
            val panelLeftPx = gutterPx + with(density) { 20.dp.toPx() }
            val panelMaxHeight = with(density) { (viewportHeightPx * 0.82f).toDp() }

            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize().verticalScroll(scrollState).testTag("timeline"),
                ) {
                    Canvas(
                        Modifier.fillMaxWidth().height(with(density) { canvasHeightPx.toDp() }),
                    ) {
                        val ropeX = gutterPx
                        val ropeFocus = scrollValue + focusY
                        val gapHalf = 20.dp.toPx() * bloom
                        val step = 6.dp.toPx()

                        fun ropeXAt(contentY: Float): Float =
                            ropeX + RopeCurve.offset(contentY - ropeFocus, bowPx, bowHalfPx) * reveal

                        // rope above the notch where the focused droplet detaches
                        run {
                            var y = scrollValue - 60f
                            val end = ropeFocus - gapHalf
                            if (y < end) {
                                val p = Path().apply { moveTo(ropeXAt(y), y) }
                                y += step
                                while (y < end) { p.lineTo(ropeXAt(y), y); y += step }
                                p.lineTo(ropeXAt(end), end)
                                drawPath(p, color = colors.rule, style = Stroke(width = 1.dp.toPx()))
                            }
                        }
                        run {
                            var y = ropeFocus + gapHalf
                            val end = scrollValue + viewportHeightPx + 60f
                            if (y < end) {
                                val p = Path().apply { moveTo(ropeXAt(y), y) }
                                y += step
                                while (y < end) { p.lineTo(ropeXAt(y), y); y += step }
                                drawPath(p, color = colors.rule, style = Stroke(width = 1.dp.toPx()))
                            }
                        }

                        for (p in result.placements) {
                            val step0 = steps.getOrNull(p.index) ?: continue
                            val contentY = focusY + p.arc
                            val screenY = contentY - scrollValue
                            if (screenY < -120f || screenY > viewportHeightPx + 120f) continue

                            val focused = p.index == focusedIndex && bloom > 0.3f
                            val dy = contentY - ropeFocus
                            val lean = -RopeCurve.lean(dy, bowPx, bowHalfPx)
                            val jiggle = if (focused) 12.dp.toPx() * bloom else 0f
                            val x = ropeXAt(contentY) + 0.4f * p.size + jiggle
                            val radius = p.size * (0.5f + 0.35f * p.bloom) + 2.dp.toPx() * bloom
                            val tail = if (p.bloom > 0f) 9.dp.toPx() * p.bloom else 0f
                            val spout = if (focused) 14.dp.toPx() * bloom else 0f

                            if (focused) {
                                val glowR = 30.dp.toPx()
                                val glow = if (step0.kind == StepKind.TOOL || step0.kind == StepKind.SUBAGENT) {
                                    colors.spectrum[4].copy(alpha = 0.20f * bloom)
                                } else {
                                    colors.accent.copy(alpha = 0.22f * bloom)
                                }
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(glow, Color.Transparent),
                                        center = Offset(x, contentY),
                                        radius = glowR,
                                    ),
                                    radius = glowR,
                                    center = Offset(x, contentY),
                                )
                            }

                            val fill = when {
                                step0.failed -> colors.spectrum.first()
                                step0.running -> colors.bg
                                focused || p.bloom > 0.5f -> colors.fg
                                else -> colors.faint
                            }
                            val pts = DropletShape.outline(
                                x, contentY,
                                DropletShape.Params(
                                    radius = radius, tail = tail, spout = spout,
                                    stretch = stretch, lean = lean,
                                ),
                            )
                            val path = Path()
                            path.moveTo(pts[0].x, pts[0].y)
                            for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                            path.close()
                            drawPath(path, color = fill)
                            if (step0.running) {
                                drawPath(path, color = colors.fg, style = Stroke(width = 2.dp.toPx()))
                            }

                            if (focused && step0.merged > 1) {
                                // a merged run blooms into its dots in the middle
                                val dots = min(step0.merged, 3)
                                for (k in 0 until dots) {
                                    drawCircle(
                                        color = colors.spectrum[k % colors.spectrum.size].copy(alpha = bloom),
                                        radius = 3.dp.toPx(),
                                        center = Offset(x + 12.dp.toPx() + k * 9.dp.toPx(), contentY),
                                    )
                                }
                            } else if (step0.merged > 1) {
                                val extra = (step0.merged - 1).coerceAtMost(2)
                                for (k in 1..extra) {
                                    drawCircle(
                                        color = colors.faint,
                                        radius = 1.6.dp.toPx(),
                                        center = Offset(x - 1.8.dp.toPx() * k, contentY - 7.dp.toPx() - 3.dp.toPx() * k),
                                    )
                                }
                            }

                            // prism: a focused tool/subagent fans into the spectrum
                            if (focused && step0.rows.isNotEmpty()) {
                                val n = min(step0.rows.size, 6)
                                val spread = 56.dp.toPx()
                                for (k in 0 until n) {
                                    val t = if (n == 1) 0.5f else k / (n - 1).toFloat()
                                    val endY = contentY + (t - 0.5f) * spread
                                    drawLine(
                                        color = colors.spectrum[k % colors.spectrum.size].copy(alpha = 0.5f * bloom),
                                        start = Offset(x + spout, contentY),
                                        end = Offset(panelLeftPx + 8.dp.toPx(), endY),
                                        strokeWidth = 1.2.dp.toPx(),
                                    )
                                }
                            }
                        }
                    }
                }

                if (showPanel && focusedStep != null) {
                    FocusPanel(
                        step = focusedStep,
                        bloom = if (forceOpenIndex != null) 1f else bloom,
                        full = fullText || forceOpenIndex != null,
                        colors = colors,
                        startPadding = with(density) { panelLeftPx.toDp() },
                        maxHeight = panelMaxHeight,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
            }
        }

        if (error != null) {
            Text(
                error, color = colors.spectrum.first(), fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Composer(input, busy, colors, onInput, onSend, onStop, onToggleTheme)
    }
}

/** The bloomed droplet's text, to the right of the rope at the focus line. */
@Composable
private fun FocusPanel(
    step: UiStep,
    bloom: Float,
    full: Boolean,
    colors: LumenColors,
    startPadding: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 16.dp)
            .alpha(bloom)
            .heightIn(max = maxHeight)
            .animateContentSize()
            .pointerInput(step.id) {
                detectTapGestures(
                    onTap = { /* single tap does nothing */ },
                    onDoubleTap = {
                        clipboard.setText(AnnotatedString(step.body))
                        copied = true
                    },
                )
            },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                step.label,
                color = colors.fg,
                fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.5.sp,
            )
            Text(
                if (copied) "copied" else step.tag,
                color = if (copied) colors.accent else colors.dim,
                fontFamily = Mono, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(6.dp))
        SelectionContainer {
            Text(
                text = step.body,
                color = colors.fg,
                fontFamily = Mono, fontSize = 14.sp, lineHeight = 20.sp,
                maxLines = if (full) Int.MAX_VALUE else LIMITED_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!full && step.rows.isNotEmpty()) {
            // the tail is where the droplet opens all the way
            Spacer(Modifier.height(5.dp))
            Text("scroll on for the rest", color = colors.faint, fontFamily = Mono, fontSize = 11.sp)
        }
        if (step.rows.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            for ((k, r) in step.rows.withIndex()) {
                Row(
                    Modifier.padding(start = 4.dp, top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("·", color = colors.spectrum[k % colors.spectrum.size], fontFamily = Mono, fontSize = 13.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(r.first, color = colors.spectrum[k % colors.spectrum.size], fontFamily = Mono, fontSize = 13.sp)
                    if (r.second.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(r.second, color = colors.dim, fontFamily = Mono, fontSize = 13.sp)
                    }
                }
            }
        }
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
    onToggleTheme: (() -> Unit)?,
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
        if (onToggleTheme != null) {
            Text(
                "◐",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = 15.sp,
                modifier = Modifier
                    .clickable { onToggleTheme() }
                    .padding(horizontal = 6.dp)
                    .testTag("theme"),
            )
        }
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
