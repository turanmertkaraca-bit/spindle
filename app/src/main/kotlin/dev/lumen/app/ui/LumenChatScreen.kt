package dev.lumen.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.StepMapper
import dev.lumen.app.ui.model.UiStep
import kotlin.math.abs

private val Mono = FontFamily.Monospace

/** Fling friction: higher bleeds energy faster, so a flick never races away. */
private const val FLING_FRICTION = 3.0f

/** A calmer fling for the timeline: it stops in a short, predictable glide. */
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

/** The "failed" colour — an explicit red, independent of the water spectrum. */
private fun LumenColors.danger(): Color = Color(0xFFE5484D)

/** Light / dark palettes. Dark is deliberately not pure black (see bg). */
data class LumenColors(
    val bg: Color,
    val surface: Color,
    val fg: Color,
    val dim: Color,
    val faint: Color,
    val rule: Color,
    val accent: Color,
    /** The core water hue: cyan-teal. Used for the spine, ripples and send. */
    val water: Color,
    val spectrum: List<Color>,
) {
    companion object {
        val Light = LumenColors(
            bg = Color(0xFFF7FBFC), surface = Color(0xFFEDF5F8), fg = Color(0xFF0C2530),
            dim = Color(0xFF557785), faint = Color(0xFFA6C0CB), rule = Color(0xFFDBE9EF),
            accent = Color(0xFF0E7490), water = Color(0xFF0EA5C9),
            spectrum = listOf(
                Color(0xFFFF4B4B), Color(0xFF0EA5C9), Color(0xFF14B8A6),
                Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFF6366F1),
            ),
        )
        val Dark = LumenColors(
            // Non-OLED-friendly: a touch of light so pixels never fully switch off,
            // but dark enough to read as "dark", not gray.
            bg = Color(0xFF07161F), surface = Color(0xFF0E2230), fg = Color(0xFFE8F4F8),
            dim = Color(0xFF8FB0BE), faint = Color(0xFF4A6673), rule = Color(0xFF173340),
            accent = Color(0xFF38BDF8), water = Color(0xFF22D3EE),
            spectrum = Light.spectrum,
        )
    }
}

/** A synthetic node shown while the model is working but has not spoken yet. */
private val WorkingStep = UiStep(
    id = "\u0000working", kind = StepKind.THINKING, label = "THINKING", tag = "",
    summary = "…", body = "", running = true,
)

/**
 * The timeline is a spine down the middle of the screen. Every step is a node on
 * it: the node nearest the focus line (the vertical centre) expands into a
 * capsule sized to its text; the rest collapse to dots. Scrolling is the only
 * navigation; double-tapping a capsule copies its body, long-press selects it.
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
    title: String = "",
    onHome: (() -> Unit)? = null,
    onInput: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onToggleTheme: (() -> Unit)? = null,
    onEditKey: (() -> Unit)? = null,
    ambient: Boolean = true,
) {
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val flingBehavior = remember { calmFling() }

    // Ambient motion (a slow water breathe on running nodes). It only runs while
    // something is actually running, so an idle timeline costs no frames.
    val running = busy || steps.any { it.running }
    val pulse = if (ambient && running) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse",
        ).value
    } else {
        1f
    }

    // Fold each think block into the answer/tool that follows, so it can
    // auto-collapse once the block has a real body. The raw rows stay intact
    // upstream, so streaming deltas still find their part id.
    val grouped = remember(steps) { StepMapper.groupSteps(steps) }

    // While the loop is between turns, show a working node so the screen is
    // never blank. It disappears as soon as real text arrives.
    val pending = busy && grouped.none { it.running } && (
        grouped.isEmpty() ||
            grouped.last().kind == StepKind.YOU ||
            grouped.last().kind == StepKind.TOOL ||
            grouped.last().kind == StepKind.SUBAGENT
        )
    val display = remember(grouped, pending) { if (pending) grouped + WorkingStep else grouped }
    val count = display.size

    // The expanded node is sticky: it follows the reader's scroll, or the tail
    // while following. It is never recomputed from live geometry while a node
    // grows — that flip-flop was the bulk of the old timeline's glitch.
    var focusedId by remember { mutableStateOf<String?>(null) }
    val firstId = display.firstOrNull()?.id

    Column(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        if (onHome != null) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 16.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "‹",
                    color = colors.dim, fontFamily = Mono, fontSize = 20.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onHome() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                        .testTag("home"),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    title.ifBlank { "chat" },
                    color = colors.dim, fontFamily = Mono, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val viewportPx = constraints.maxHeight.toFloat()
            val viewportCenter = viewportPx / 2f
            val verticalPad = with(density) { viewportCenter.toDp() }

            val focusedIndex = forceOpenIndex?.let { clampIndex(it, count) }
                ?: display.indexOfFirst { it.id == focusedId }.takeIf { it >= 0 }
                ?: clampIndex(count - 1, count)

            // A new session (or an emptied one) clears the anchor, so focus
            // falls back to the tail again.
            LaunchedEffect(firstId) { focusedId = null }

            // One owner for scroll. It:
            //  * lands on the newest node at cold start,
            //  * follows appends only when the reader was at the tail,
            //  * anchors the focused node to the focus line while it grows,
            //  * re-picks the focused node only from a settled scroll (never
            //    mid-growth), so a streaming answer cannot flip the capsule.
            // Keeping all of it in one loop is what removed the jitter: the old
            // code had three effects each recomputing geometry every frame.
            LaunchedEffect(count, firstId, forceOpenIndex) {
                if (count == 0) return@LaunchedEffect
                if (forceOpenIndex != null) {
                    // Screenshots/tests: put the requested node on the line.
                    withFrameNanos { }
                    val vp = TimelineViewport.read(listState, clampIndex(forceOpenIndex, count))
                    vp.centerDelta()?.let { listState.scrollBy(it) }
                    return@LaunchedEffect
                }

                // Cold start: no scroll history yet, so land on the newest node.
                var lastCount = 0
                if (listState.firstVisibleItemIndex == 0 && listState.layoutInfo.totalItemsCount == 0) {
                    listState.scrollToItem(clampIndex(count - 1, count))
                    lastCount = count
                }

                snapshotFlow {
                    val vp = TimelineViewport.read(listState, focusedIndex)
                    // Only react to settled frames; while a gesture/fling runs we
                    // leave the reader alone.
                    if (listState.isScrollInProgress) vp.copy(focusedTop = null) else vp
                }.collect { vp ->
                    if (!vp.hasFocus) return@collect

                    // Re-anchor the expanded node from a settled scroll only.
                    // Growth never triggers this: the node's own height change is
                    // handled by the top anchor below, not by re-picking focus.
                    val nearestId = display.getOrNull(vp.firstVisibleIndex)?.id
                    val nearest = listState.layoutInfo.visibleItemsInfo
                        .minByOrNull { abs((it.offset + it.size / 2f) - vp.center) }
                    val nearestFocused = nearest?.index == focusedIndex
                    if (!nearestFocused && nearest != null && nearestId != null) {
                        val id = display.getOrNull(nearest.index)?.id
                        if (id != null && id != focusedId) focusedId = id
                        return@collect
                    }

                    // Keep the focused node's TOP on the line so added text grows
                    // downward; this is the only scroll we do while streaming.
                    vp.topDelta()?.let { delta ->
                        if (abs(delta) > 1f) listState.scrollBy(delta)
                    }

                    // Follow appends when the reader is at the tail.
                    if (count > lastCount && vp.lastVisibleIndex >= count - 2) {
                        listState.animateScrollToItem(clampIndex(count - 1, count))
                    }
                    lastCount = count
                }
            }

            Box(Modifier.fillMaxSize()) {
                if (count == 0) {
                    EmptyState(colors, Modifier.align(Alignment.Center))
                } else {
                    LazyColumn(
                        state = listState,
                        flingBehavior = flingBehavior,
                        contentPadding = PaddingValues(vertical = verticalPad),
                        modifier = Modifier.fillMaxSize().testTag("timeline"),
                    ) {
                        itemsIndexed(display, key = { _, s -> s.id }) { index, step ->
                            SpineNode(
                                step = step,
                                focused = index == focusedIndex,
                                colors = colors,
                                pulse = pulse,
                            )
                        }
                    }
                }
            }
        }

        if (error != null) {
            ErrorNotice(error, colors, onEditKey)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.rule))
        Composer(input, busy, colors, onInput, onSend, onStop, onToggleTheme, onEditKey)
    }
}

/**
 * One node on the spine. A collapsed node is a dot; the focused one expands into
 * a capsule sized to its text, so streaming tokens simply grow it.
 */
@Composable
private fun SpineNode(
    step: UiStep,
    focused: Boolean,
    colors: LumenColors,
    pulse: Float,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }

    val isTool = step.kind == StepKind.TOOL || step.kind == StepKind.SUBAGENT
    val tint = when {
        step.failed -> colors.danger()
        isTool -> colors.water
        step.kind == StepKind.YOU -> colors.dim
        else -> colors.accent
    }

    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val cx = size.width / 2f
                // The spine is water, not a wire: a soft vertical gradient with a
                // ripple at the focused node.
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            colors.rule.copy(alpha = 0.0f),
                            colors.rule,
                            colors.rule.copy(alpha = 0.0f),
                        ),
                    ),
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
                if (focused) {
                    // A ripple ring around the focused node: the water surface.
                    drawCircle(
                        color = colors.water.copy(alpha = 0.16f + 0.1f * pulse),
                        radius = size.height * 0.22f,
                        center = Offset(cx, size.height / 2f),
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!focused) {
            Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) {
                if (step.running) {
                    WaterDot(
                        size = 13.dp,
                        color = tint.copy(alpha = 0.35f + 0.65f * pulse),
                        ring = true,
                    )
                } else {
                    WaterDot(
                        size = if (isTool) 7.dp else 9.dp,
                        color = if (isTool) tint.copy(alpha = 0.9f) else colors.faint,
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .animateContentSize(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                    .clip(WaterShapes.drop(taper = 0.10f))
                    .background(
                        Brush.verticalGradient(
                            0f to colors.surface,
                            1f to colors.surface.copy(alpha = 0.92f),
                        ),
                    )
                    .border(
                        1.dp,
                        if (step.failed) colors.danger().copy(alpha = 0.5f) else tint.copy(alpha = 0.22f),
                        WaterShapes.drop(taper = 0.10f),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WaterDot(
                            size = 8.dp,
                            color = if (step.running) tint.copy(alpha = 0.35f + 0.65f * pulse) else tint,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (step.merged > 1) "${step.label} ×${step.merged}" else step.label,
                            color = colors.dim, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.5.sp,
                        )
                    }
                    Text(
                        when {
                            copied -> "copied"
                            step.running -> "running…"
                            else -> step.tag
                        },
                        color = when {
                            copied -> colors.water
                            step.running -> tint
                            else -> colors.faint
                        },
                        fontFamily = Mono, fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (step.think != null) {
                    ThinkSection(step.think, colors)
                    Spacer(Modifier.height(8.dp))
                }
                if (step.running && step.body.isBlank()) {
                    Text("thinking…", color = tint.copy(alpha = 0.4f + 0.6f * pulse), fontFamily = Mono, fontSize = 15.sp)
                } else {
                    SelectionContainer {
                        Text(
                            step.body.ifBlank { step.summary },
                            color = colors.fg, fontFamily = Mono, fontSize = 15.sp, lineHeight = 22.sp,
                            modifier = Modifier.pointerInput(step.id) {
                                detectTapGestures(
                                    onDoubleTap = {
                                        clipboard.setText(AnnotatedString(step.body))
                                        copied = true
                                    },
                                )
                            },
                        )
                    }
                }
                if (step.rows.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    for ((k, r) in step.rows.withIndex()) {
                        Row(
                            Modifier.padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            WaterDot(size = 4.dp, color = colors.spectrumAt(k))
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
        }
    }
}

/** A water droplet node: a teardrop silhouette, optionally a ring. */
@Composable
private fun WaterDot(size: androidx.compose.ui.unit.Dp, color: Color, ring: Boolean = false) {
    if (ring) {
        Box(
            Modifier.size(size)
                .border(2.dp, color, WaterShapes.droplet(tail = 0.5f)),
        )
    } else {
        Box(
            Modifier.size(size)
                .background(color, WaterShapes.droplet(tail = 0.55f)),
        )
    }
}

/**
 * The reasoning folded into a step: a one-line header that expands on tap. It
 * starts collapsed, so once the answer arrives the thinking tucks away.
 */
@Composable
private fun ThinkSection(think: String, colors: LumenColors) {
    var open by remember(think) { mutableStateOf(false) }
    val arrow by animateFloatAsState(
        targetValue = if (open) 90f else 0f,
        animationSpec = tween(180),
        label = "think-arrow",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.water.copy(alpha = 0.08f))
            .border(1.dp, colors.water.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .testTag("think-toggle"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "▸",
                color = colors.dim, fontFamily = Mono, fontSize = 11.sp,
                modifier = Modifier.graphicsLayer { rotationZ = arrow },
            )
            Spacer(Modifier.width(7.dp))
            Text(
                "thinking · ${think.length} chars",
                color = colors.dim, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.5.sp,
            )
        }
        if (open) {
            Spacer(Modifier.height(6.dp))
            SelectionContainer {
                Text(
                    think,
                    color = colors.dim, fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.testTag("think-body"),
                )
            }
        }
    }
}

/** Shown before the first message: an intentional start, not a blank screen. */
@Composable
private fun EmptyState(colors: LumenColors, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(colors.faint))
        Spacer(Modifier.height(16.dp))
        Text("lumen", color = colors.dim, fontFamily = Mono, fontSize = 15.sp, letterSpacing = 7.sp)
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
    val canSend = input.isNotBlank() && !busy
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // The button gives itself back to the finger: it sinks a little on press.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "send-scale",
    )
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
                cursorBrush = SolidColor(colors.water),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
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
        val enabled = if (busy) true else canSend
        Text(
            label,
            color = when {
                busy -> colors.accent
                enabled -> colors.water
                else -> colors.faint
            },
            fontFamily = Mono,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(8.dp))
                .clickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = LocalIndication.current,
                    onClick = { if (busy) onStop() else onSend() },
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .testTag(if (busy) "stop" else "send"),
        )
    }
}
