package dev.lumen.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.ui.SpineLayout
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

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

/** The "failed" colour. */
private fun LumenColors.danger(): Color = spectrumAt(0)

/** Light / dark palettes. Dark is deliberately not pure black (see bg). */
data class LumenColors(
    val bg: Color,
    val surface: Color,
    val fg: Color,
    val dim: Color,
    val faint: Color,
    val rule: Color,
    val accent: Color,
    val spectrum: List<Color>,
) {
    companion object {
        val Light = LumenColors(
            bg = Color(0xFFFCFCFB), surface = Color(0xFFF3F3F1), fg = Color(0xFF141414),
            dim = Color(0xFF747474), faint = Color(0xFFB4B4B4), rule = Color(0xFFE7E7E4),
            accent = Color(0xFF3B82F6),
            spectrum = listOf(
                Color(0xFFFF4B4B), Color(0xFFFF9F1A), Color(0xFFF5D000),
                Color(0xFF22C55E), Color(0xFF3B82F6), Color(0xFF8B5CF6),
            ),
        )
        val Dark = LumenColors(
            // Non-OLED-friendly: a touch of light so pixels never fully switch off,
            // but dark enough to read as "dark", not gray.
            bg = Color(0xFF101014), surface = Color(0xFF191920), fg = Color(0xFFF1F1F4),
            dim = Color(0xFF9A9AA4), faint = Color(0xFF565660), rule = Color(0xFF26262D),
            accent = Color(0xFF6EA8FF),
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
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val flingBehavior = remember { calmFling() }

    // Ambient motion (a slow pulse on the working node); off in tests so the
    // Robolectric frame clock can settle.
    val pulse = if (ambient) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse",
        ).value
    } else {
        1f
    }

    // While the loop is between turns, show a working node so the screen is
    // never blank. It disappears as soon as real text arrives.
    val pending = busy && steps.none { it.running } && (
        steps.isEmpty() ||
            steps.last().kind == StepKind.YOU ||
            steps.last().kind == StepKind.TOOL ||
            steps.last().kind == StepKind.SUBAGENT
        )
    val display = remember(steps, pending) { if (pending) steps + WorkingStep else steps }
    val count = display.size

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

            val focusedIndex = if (forceOpenIndex != null) {
                forceOpenIndex.coerceIn(0, max(0, count - 1))
            } else {
                SpineLayout.nearestCenter(
                    listState.layoutInfo.visibleItemsInfo.map { it.index to (it.offset + it.size / 2f) },
                    (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2f,
                )
            }

            // Cold start lands on the newest node; appends are followed only when
            // the reader was already at the tail.
            var lastCount by remember { mutableStateOf(0) }
            LaunchedEffect(count, forceOpenIndex) {
                if (count == 0) {
                    lastCount = 0
                    return@LaunchedEffect
                }
                if (forceOpenIndex != null) {
                    listState.scrollToItem(forceOpenIndex.coerceIn(0, count - 1))
                    lastCount = count
                    return@LaunchedEffect
                }
                val prev = lastCount
                if (prev == 0) {
                    listState.scrollToItem(count - 1)
                } else if (count > prev && focusedIndex >= prev - 1) {
                    listState.animateScrollToItem(count - 1)
                }
                lastCount = count
            }

            // Click into place: on every settle, ease the nearest node onto the line.
            LaunchedEffect(count, forceOpenIndex) {
                if (forceOpenIndex != null) return@LaunchedEffect
                snapshotFlow { listState.isScrollInProgress }
                    .filter { !it }
                    .collect {
                        val info = listState.layoutInfo.visibleItemsInfo
                        if (info.isEmpty()) return@collect
                        val vc = (listState.layoutInfo.viewportStartOffset +
                            listState.layoutInfo.viewportEndOffset) / 2f
                        val nearest = info.minByOrNull { abs((it.offset + it.size / 2f) - vc) } ?: return@collect
                        val delta = SpineLayout.centerDelta(nearest.offset + nearest.size / 2f, vc)
                        if (abs(delta) > 1f) listState.animateScrollBy(delta)
                    }
            }

            // The forced node (screenshots/tests) is centred once it is laid out.
            LaunchedEffect(forceOpenIndex, count) {
                if (forceOpenIndex == null || count == 0) return@LaunchedEffect
                withFrameNanos { }
                val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == forceOpenIndex }
                if (info != null) {
                    val vc = (listState.layoutInfo.viewportStartOffset +
                        listState.layoutInfo.viewportEndOffset) / 2f
                    listState.scrollBy(SpineLayout.centerDelta(info.offset + info.size / 2f, vc))
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
                                onGrow = { grew ->
                                    if (!listState.isScrollInProgress) {
                                        scope.launch {
                                            listState.scrollBy(SpineLayout.growthRecenter(grew.toFloat()))
                                        }
                                    }
                                },
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
    onGrow: (Int) -> Unit,
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
        isTool -> colors.spectrumAt(4)
        else -> colors.accent
    }

    var prevSize by remember(step.id) { mutableIntStateOf(0) }
    var wasFocused by remember(step.id) { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val cx = size.width / 2f
                drawLine(
                    color = colors.rule,
                    start = Offset(cx, 0f),
                    end = Offset(cx, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .onSizeChanged { s ->
                val grew = s.height - prevSize
                prevSize = s.height
                if (focused && wasFocused && grew != 0) onGrow(grew)
                wasFocused = focused
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!focused) {
            Box(Modifier.height(42.dp), contentAlignment = Alignment.Center) {
                if (step.running) {
                    Box(
                        Modifier.size(13.dp).clip(CircleShape)
                            .border(2.dp, tint.copy(alpha = 0.35f + 0.65f * pulse), CircleShape),
                    )
                } else {
                    Box(
                        Modifier.size(if (isTool) 7.dp else 9.dp).clip(CircleShape)
                            .background(if (isTool) tint.copy(alpha = 0.85f) else colors.dim),
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .animateContentSize()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.surface)
                    .border(
                        1.dp,
                        if (step.failed) colors.danger().copy(alpha = 0.5f) else colors.rule,
                        RoundedCornerShape(20.dp),
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape).background(
                                if (step.running) tint.copy(alpha = 0.35f + 0.65f * pulse) else tint,
                            ),
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
                            copied -> colors.accent
                            step.running -> tint
                            else -> colors.faint
                        },
                        fontFamily = Mono, fontSize = 11.sp,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (step.running && step.body.isBlank()) {
                    Text("thinking…", color = tint.copy(alpha = 0.4f + 0.6f * pulse), fontFamily = Mono, fontSize = 15.sp)
                } else {
                    SelectionContainer {
                        Text(
                            step.body.ifBlank { step.summary },
                            color = colors.fg, fontFamily = Mono, fontSize = 15.sp, lineHeight = 22.sp,
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
