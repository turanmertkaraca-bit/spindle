package dev.lumen.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
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
import dev.spindle.core.ui.RopeTension
import dev.spindle.core.ui.RopeWave
import kotlinx.coroutines.flow.filter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Mono = FontFamily.Monospace

/** Fling friction: higher bleeds energy faster, so a flick never races away. */
private const val FLING_FRICTION = 2.6f

/** A calmer fling for the rope: it stops in a short, predictable glide. */
private fun calmFling(friction: Float = FLING_FRICTION): FlingBehavior = object : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < 1f) return initialVelocity
        var last = 0f
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateDecay(
            exponentialDecay(frictionMultiplier = friction),
        ) {
            val delta = value - last
            val consumed = scrollBy(delta)
            last = value
            if (abs(delta - consumed) > 0.5f) cancelAnimation()
        }
        return 0f
    }
}

/** Safe spectral colour: never throws, whatever palette is supplied. */
private fun LumenColors.spectrumAt(index: Int): Color {
    val s = spectrum
    if (s.isEmpty()) return accent
    val i = ((index % s.size) + s.size) % s.size
    return s[i]
}

/** The "failed" colour. */
private fun LumenColors.danger(): Color = spectrumAt(0)

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
    onEditKey: (() -> Unit)? = null,
    ambient: Boolean = true,
) {
    val density = LocalDensity.current
    val nodeSizePx = with(density) { 10.dp.toPx() }
    val baseGapPx = with(density) { 26.dp.toPx() }
    val focusExtraPx = with(density) { 96.dp.toPx() }
    val gutterPx = with(density) { 24.dp.toPx() }
    val bowPx = with(density) { 9.dp.toPx() }
    val bowHalfPx = with(density) { 150.dp.toPx() }
    // Calmer scrolling: one droplet per roughly the rendered near-focus spacing,
    // so a swipe moves the rope about as far as it looks like it should.
    val scrollStridePx = nodeSizePx + baseGapPx + focusExtraPx * 0.72f
    val metrics = remember(nodeSizePx, baseGapPx, focusExtraPx) {
        RopeLayout.Metrics(
            nodeSize = nodeSizePx,
            baseGap = baseGapPx,
            focusGapExtra = focusExtraPx,
            focusHalfWidth = 1.6f,
            focusY = 0f,
            scrollStride = scrollStridePx,
        )
    }
    val stride = metrics.scrollStride

    val scrollState = rememberScrollState()
    val flingBehavior = remember { calmFling() }
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

    // Velocity of the rope (px/s), sampled only while the finger or fling moves
    // it, so squash-stretch follows the real speed instead of a boolean.
    var velocity by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { scrollState.isScrollInProgress }.collect { moving ->
            if (!moving) {
                velocity = 0f
                return@collect
            }
            var prev = scrollState.value
            var prevT = withFrameNanos { it }
            var v = 0f
            while (scrollState.isScrollInProgress) {
                val t = withFrameNanos { it }
                val cur = scrollState.value
                val dt = ((t - prevT) / 1_000_000_000f).coerceAtLeast(1e-3f)
                v += ((cur - prev) / dt - v) * 0.35f
                velocity = v
                prev = cur
                prevT = t
            }
            velocity = 0f
        }
    }
    val stretch = if (mode == FocusPolicy.Mode.FOCUSED) 1f
        else DropletShape.stretchFor(abs(velocity), gain = 0.00022f)

    // A slow breath on the focused droplet keeps the surface feeling alive.
    // Ambient motion is switched off in tests so the frame clock can settle.
    val breath = if (ambient) {
        val transition = rememberInfiniteTransition(label = "breath")
        transition.animateFloat(
            initialValue = 0.97f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "breath",
        ).value
    } else {
        1f
    }

    val nodes = remember(steps) {
        steps.map { RopeLayout.Node(key = it.id, bodyHeight = 0f, merged = it.merged) }
    }
    val result = remember(nodes, scrollValue, metrics) {
        RopeLayout.compute(nodes, scrollValue, metrics)
    }

    // Cold start lands on the newest droplet. Afterwards a freshly appended
    // droplet is followed only when the reader was already at the tail — so a
    // reader further up is never yanked away mid-read.
    var lastCount by remember { mutableStateOf(0) }
    LaunchedEffect(count, forceOpenIndex, scrollState.maxValue) {
        if (count == 0) {
            lastCount = 0
            return@LaunchedEffect
        }
        if (scrollState.maxValue <= 0) return@LaunchedEffect // wait for layout
        if (forceOpenIndex != null) {
            scrollState.scrollTo((forceOpenIndex.coerceIn(0, count - 1) * stride).roundToInt())
            lastCount = count
            return@LaunchedEffect
        }
        val prev = lastCount
        val atTail = prev == 0 ||
            (scrollState.value.toFloat() / stride).roundToInt() >= prev - 1
        if (count > prev && atTail) {
            scrollState.scrollTo(((count - 1) * stride).roundToInt())
        }
        lastCount = count
    }

    // Appended droplets "turn and twist into place": a damped wave races along
    // the rope for ~1.5s, then dies out.
    val twist = remember { Animatable(1f) }
    var waveCount by remember { mutableStateOf(0) }
    LaunchedEffect(count) {
        val appended = waveCount > 0 && count > waveCount && count - waveCount <= 2
        waveCount = count
        if (appended) {
            twist.snapTo(0f)
            twist.animateTo(1f, animationSpec = tween(1500, easing = LinearEasing))
        }
    }
    val twistElapsed = twist.value * 1.5f

    // click into place: on every settle, ease the nearest droplet onto the line.
    // A long-lived flow (not keyed on the in-progress flag) is what stops the
    // animation from cancelling itself and ping-ponging.
    LaunchedEffect(count, forceOpenIndex) {
        if (forceOpenIndex != null) return@LaunchedEffect
        snapshotFlow { scrollState.isScrollInProgress }
            .filter { !it }
            .collect {
                if (count > 1) {
                    val idx = (scrollState.value.toFloat() / stride).roundToInt().coerceIn(0, count - 1)
                    val target = idx * stride
                    if (abs(scrollState.value - target) > 2f) {
                        scrollState.animateScrollTo(target.roundToInt())
                    }
                }
            }
    }

    val focusedStep = steps.getOrNull(focusedIndex)
    val showPanel = focusedStep != null && (forceOpenIndex != null || bloom > 0.05f)

    Column(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportHeightPx = constraints.maxHeight.toFloat()
            val focusY = viewportHeightPx / 2f
            val maxScrollPx = (count - 1).coerceAtLeast(0) * stride
            val canvasHeightPx = viewportHeightPx + maxScrollPx
            val panelLeftPx = gutterPx + with(density) { 50.dp.toPx() }
            val panelMaxHeight = with(density) { (viewportHeightPx * 0.72f).toDp() }

            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier.fillMaxSize()
                        .verticalScroll(scrollState, flingBehavior = flingBehavior)
                        .testTag("timeline"),
                ) {
                    Canvas(
                        Modifier.fillMaxWidth().height(with(density) { canvasHeightPx.toDp() }),
                    ) {
                        val ropeX = gutterPx
                        val ropeFocus = scrollValue + focusY
                        val gapHalf = 20.dp.toPx() * bloom
                        val step = 6.dp.toPx()

                        // The rope reaches toward whichever side holds more
                        // droplets, so an unbalanced list visibly tugs that way.
                        val pull = RopeTension.pull(
                            aboveCount = focusedIndex,
                            belowCount = max(0, count - 1 - focusedIndex),
                            viewport = viewportHeightPx,
                            minReach = 46.dp.toPx(),
                            halfFactor = 0.5f,
                            saturation = 2.0f,
                            lean = bowPx * 1.1f,
                        )
                        val reachAbove = pull.reachAbove * reveal
                        val reachBelow = pull.reachBelow * reveal
                        val waveAmp = 6.dp.toPx()
                        val waveLen = 110.dp.toPx()
                        val waveSpeed = 170.dp.toPx()
                        val waveFade = 240.dp.toPx()
                        val waveEnv = RopeWave.envelope(twistElapsed)

                        fun ropeXAt(contentY: Float): Float {
                            val dy = contentY - ropeFocus
                            val bow = RopeCurve.offset(dy, bowPx, bowHalfPx)
                            val tug = pull.leanBias * RopeCurve.offset(dy, 1f, bowHalfPx)
                            val wave = RopeWave.displacement(dy, twistElapsed, waveAmp, waveLen, waveSpeed, waveFade)
                            return ropeX + (bow + tug + wave) * reveal
                        }

                        val visibleTop = scrollValue - 60f
                        val visibleBottom = scrollValue + viewportHeightPx + 60f

                        fun drawRope(from: Float, to: Float) {
                            if (to <= from) return
                            val p = Path().apply { moveTo(ropeXAt(from), from) }
                            var y = from + step
                            while (y < to) { p.lineTo(ropeXAt(y), y); y += step }
                            p.lineTo(ropeXAt(to), to)
                            drawPath(
                                p,
                                color = colors.faint.copy(alpha = 0.7f + 0.2f * waveEnv),
                                style = Stroke(width = 1.2.dp.toPx()),
                            )
                        }

                        // the rope grows out of the focus; the notch is where the
                        // focused droplet detaches from it
                        drawRope(visibleTop.coerceAtLeast(ropeFocus - reachAbove), ropeFocus - gapHalf)
                        drawRope(ropeFocus + gapHalf, visibleBottom.coerceAtMost(ropeFocus + reachBelow))


                        for (p in result.placements) {
                            val step0 = steps.getOrNull(p.index) ?: continue
                            val contentY = focusY + p.arc
                            val screenY = contentY - scrollValue
                            if (screenY < -120f || screenY > viewportHeightPx + 120f) continue

                            val focused = p.index == focusedIndex && bloom > 0.3f
                            val isTool = step0.kind == StepKind.TOOL || step0.kind == StepKind.SUBAGENT
                            val tint = if (isTool) colors.spectrumAt(4) else colors.accent
                            val breathe = if (focused) breath else 1f
                            val x = ropeXAt(contentY) + 0.4f * p.size + (if (focused) 6.dp.toPx() * bloom else 0f)
                            val radius = (p.size * (0.42f + 0.36f * p.bloom) + 2.2.dp.toPx() * bloom) * breathe
                            val rw = radius / stretch
                            val rh = radius * stretch
                            val topLeft = Offset(x - rw, contentY - rh)
                            val oval = Size(2f * rw, 2f * rh)

                            if (focused) {
                                val glowR = 34.dp.toPx()
                                drawCircle(
                                    brush = Brush.radialGradient(
                                        colors = listOf(tint.copy(alpha = 0.22f * bloom), Color.Transparent),
                                        center = Offset(x, contentY),
                                        radius = glowR,
                                    ),
                                    radius = glowR,
                                    center = Offset(x, contentY),
                                )
                            }

                            when {
                                step0.failed -> drawOval(colors.danger(), topLeft, oval)
                                step0.running -> {
                                    // a calm ring with a breathing core while a tool runs
                                    drawOval(
                                        tint.copy(alpha = 0.14f * (if (focused) bloom.coerceAtLeast(0.5f) else 0.6f)),
                                        Offset(topLeft.x - 4.dp.toPx(), topLeft.y - 4.dp.toPx()),
                                        Size(oval.width + 8.dp.toPx(), oval.height + 8.dp.toPx()),
                                    )
                                    drawOval(colors.bg, topLeft, oval)
                                    drawOval(tint, topLeft, oval, style = Stroke(1.6.dp.toPx()))
                                    val ir = radius * 0.4f * breathe
                                    drawOval(tint, Offset(x - ir, contentY - ir), Size(2f * ir, 2f * ir))
                                }
                                else -> {
                                    drawOval(lerp(colors.faint, colors.fg, p.bloom), topLeft, oval)
                                    if (focused) {
                                        drawOval(
                                            tint.copy(alpha = 0.6f * bloom),
                                            topLeft,
                                            oval,
                                            style = Stroke(1.4.dp.toPx()),
                                        )
                                        // a quiet thread from the bloomed bead to its text
                                        drawLine(
                                            color = colors.faint.copy(alpha = 0.45f * bloom),
                                            start = Offset(x + rw + 2.dp.toPx(), contentY),
                                            end = Offset(panelLeftPx, contentY),
                                            strokeWidth = 1.dp.toPx(),
                                        )
                                    }
                                }
                            }

                            if (step0.merged > 1) {
                                // a merged run keeps a tidy spectrum stack above the bead
                                val dots = min(step0.merged, 3)
                                val a = if (focused) bloom else 0.5f
                                for (k in 0 until dots) {
                                    drawCircle(
                                        color = colors.spectrumAt(k).copy(alpha = a),
                                        radius = (if (focused) 3.2f else 2f).dp.toPx(),
                                        center = Offset(x, contentY - rh - 5.dp.toPx() - k * 4.dp.toPx()),
                                    )
                                }
                            }

                            // prism: a focused tool/subagent fans into the spectrum,
                            // staying inside the gutter so it never crosses the text.
                            if (focused && step0.merged == 1 && step0.rows.isNotEmpty()) {
                                val n = min(step0.rows.size, 6)
                                val spread = 44.dp.toPx()
                                for (k in 0 until n) {
                                    val t = if (n == 1) 0.5f else k / (n - 1).toFloat()
                                    val endY = contentY + (t - 0.5f) * spread
                                    drawLine(
                                        color = colors.spectrumAt(k).copy(alpha = 0.5f * bloom),
                                        start = Offset(x + rw * 0.4f, contentY),
                                        end = Offset(panelLeftPx, endY),
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
                        colors = colors,
                        startPadding = with(density) { panelLeftPx.toDp() },
                        maxHeight = panelMaxHeight,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }

                if (count == 0) {
                    EmptyRope(colors, Modifier.align(Alignment.Center))
                }
            }
        }

        if (error != null) {
            ErrorNotice(error, colors, onEditKey)
        }
        Composer(input, busy, colors, onInput, onSend, onStop, onToggleTheme, onEditKey)
    }
}

/** Shown before the first message: an intentional start, not an empty screen. */
@Composable
private fun EmptyRope(colors: LumenColors, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("lumen", color = colors.faint, fontFamily = Mono, fontSize = 15.sp, letterSpacing = 7.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            "ask the agent to begin",
            color = colors.faint, fontFamily = Mono, fontSize = 12.sp,
        )
    }
}

/** A single, non-scary error line; offers the key screen when it is auth. */
@Composable
private fun ErrorNotice(message: String, colors: LumenColors, onEditKey: (() -> Unit)?) {
    val lower = message.lowercase()
    val auth = lower.contains("401") || lower.contains("auth") ||
        lower.contains("api key") || lower.contains("unauthorized")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.rule)
            .border(1.dp, colors.danger().copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(colors.danger()))
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (auth) "Authentication failed — check your API key." else oneLine(message),
            color = colors.fg, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (auth && onEditKey != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                "update key",
                color = colors.accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onEditKey() }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .testTag("update-key"),
            )
        }
    }
}

private fun oneLine(s: String): String =
    s.replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(160) + "…" else it }

/** The bloomed droplet's text, to the right of the rope at the focus line. */
@Composable
private fun FocusPanel(
    step: UiStep,
    bloom: Float,
    colors: LumenColors,
    startPadding: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(step.id) { mutableStateOf(false) }
    val textScroll = remember(step.id) { ScrollState(0) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = 16.dp)
            .background(colors.bg)
            .alpha(bloom)
            .heightIn(max = maxHeight)
            .animateContentSize(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(textScroll)
                .testTag("panel")
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
                )
            }
            if (step.rows.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                for ((k, r) in step.rows.withIndex()) {
                    Row(
                        Modifier.padding(start = 4.dp, top = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("·", color = colors.spectrumAt(k), fontFamily = Mono, fontSize = 13.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(r.first, color = colors.spectrumAt(k), fontFamily = Mono, fontSize = 13.sp)
                        if (r.second.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            Text(r.second, color = colors.dim, fontFamily = Mono, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
        if (textScroll.maxValue > 0 && textScroll.value < textScroll.maxValue) {
            // a quiet cue that the text continues below the fold
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(64.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.55f to colors.bg.copy(alpha = 0.72f),
                            1f to colors.bg,
                        ),
                    ),
            )
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
    onEditKey: (() -> Unit)?,
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
        if (onEditKey != null) {
            Text(
                "key",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = 12.sp,
                modifier = Modifier
                    .clickable { onEditKey() }
                    .padding(horizontal = 6.dp)
                    .testTag("key"),
            )
        }
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
