package dev.lumen.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
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
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.Usage
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.launch

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

/** The "failed" colour — an explicit red, independent of the spectrum. */
private fun LumenColors.danger(): Color = if (dark) Color(0xFFF87171) else Color(0xFFBE123C)

/**
 * The prism palette: true-black (AMOLED) or near-white base with an iridescent
 * violet→azure→cyan→mint spectrum used as light — bubble edges, droplets, the
 * send button — never as a flat fill. The dark spectrum is desaturated toward
 * pastel so it reads calm rather than neon. Contrast was checked against the
 * base (foreground >16:1, spectrum >9:1 dark / >5:1 light).
 */
data class LumenColors(
    val bg: Color,
    val surface: Color,
    val fg: Color,
    val dim: Color,
    val faint: Color,
    val rule: Color,
    val accent: Color,
    /** Primary spectral hue (cyan); used for cursor, marks and focus glow. */
    val water: Color,
    /** The iridescent stops: violet, azure, cyan, mint. */
    val spectrum: List<Color>,
    val dark: Boolean = false,
) {
    /** A soft wash of colour for the top of the screen (prism bloom). */
    val bloomA: Color get() = spectrum.getOrElse(0) { accent }
    val bloomB: Color get() = spectrum.getOrElse(2) { accent }
    val bloomC: Color get() = spectrum.getOrElse(1) { accent }

    companion object {
        val Light = LumenColors(
            bg = Color(0xFFF7F6FB), surface = Color(0xFFFFFFFF), fg = Color(0xFF1A1730),
            dim = Color(0xFF6B6588), faint = Color(0xFFA9A4C0), rule = Color(0x1A140A32),
            accent = Color(0xFF6D3FC9),
            water = Color(0xFF16708A),
            spectrum = listOf(
                Color(0xFF7147C4), Color(0xFF335FB4), Color(0xFF186F82), Color(0xFF107455),
            ),
            dark = false,
        )
        val Dark = LumenColors(
            bg = Color(0xFF000000), surface = Color(0xFF0A0A0C), fg = Color(0xFFF2EFFB),
            dim = Color(0xFFA49FC4), faint = Color(0xFF5B5675), rule = Color(0x17FFFFFF),
            accent = Color(0xFFB9A6F5),
            water = Color(0xFF7FD8E8),
            spectrum = listOf(
                Color(0xFFB9A6F5), Color(0xFF8FB8F0), Color(0xFF7FD8E8), Color(0xFF8FDCC0),
            ),
            dark = true,
        )
    }
}

/** A synthetic node shown while the model is working but has not spoken yet. */
private val WorkingStep = UiStep(
    id = "\u0000working", kind = StepKind.THINKING, label = "THINKING", tag = "",
    summary = "…", body = "", running = true,
)

/**
 * A bubble chat. Your turns sit right, the agent's left. Reasoning collapses
 * into a pill; tool calls nest in a compact pocket inside the bubble. When the
 * reader is at the tail, new content follows smoothly; when they have scrolled
 * away, a "new" cue floats above the composer instead of yanking them down.
 * This is the render layer only — the data model is unchanged.
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
    onExpandSubagent: (UiStep) -> Unit = {},
    ambient: Boolean = true,
    /** Rolled-up session token/cost totals; the meter hides until non-zero. */
    usage: Usage = Usage(),
    /** Structured file changes for this run; the card hides when empty. */
    changes: RunChanges = RunChanges.EMPTY,
    /** Off in tests/CI so the "answer landed" haptic never fires there. */
    haptics: Boolean = true,
) {
    val listState = rememberLazyListState()
    val flingBehavior = remember { calmFling() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val hapticsActive = haptics && !LocalInspectionMode.current

    // A slow spectrum breathe while something is running; off when idle or in tests.
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

    // Fold each think block into the answer/tool that follows, so it collapses
    // once the block has a real body. The raw rows stay intact upstream.
    val grouped = remember(steps) { StepMapper.groupSteps(steps) }

    // A working bubble between turns so the screen is never blank.
    val pending = busy && grouped.none { it.running } &&
        (grouped.isEmpty() || grouped.last().kind == StepKind.YOU)
    val display = remember(grouped, pending) { if (pending) grouped + WorkingStep else grouped }
    val count = display.size
    val last = display.lastOrNull()

    // Follow the tail while streaming, gently. We only re-anchor when content
    // actually grows and the reader is already at the bottom, so a bubble that
    // merely gains height (token, or think→answer) does not yank the list.
    var followTail by remember { mutableStateOf(true) }
    var showNewCue by remember { mutableStateOf(false) }
    var cueArmed by remember { mutableStateOf(false) }
    var prevCount by remember { mutableStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()
            (info.totalItemsCount == 0) || (lastVisible != null && lastVisible.index >= info.totalItemsCount - 2)
        }.collect {
            followTail = it
            if (it) showNewCue = false
        }
    }
    LaunchedEffect(count, last?.id) {
        if (count == 0 || !followTail) return@LaunchedEffect
        // animateScrollToItem eases to the newest bubble; harmless when already
        // there, so it never fights the streaming height changes above it.
        listState.animateScrollToItem(count - 1)
    }
    // A new bubble landed while the reader is up the transcript: show the "new"
    // cue and give a light haptic. Keyed on the row id, not the body, so streaming
    // tokens never fire it; the first composition is skipped so opening a session
    // does not buzz. The haptic is disabled in tests.
    LaunchedEffect(count, last?.id) {
        val grew = count > prevCount
        prevCount = count
        if (!cueArmed) {
            cueArmed = true
            return@LaunchedEffect
        }
        if (!grew || count == 0 || followTail) return@LaunchedEffect
        showNewCue = true
        val kind = last?.kind
        if (hapticsActive && kind != null && kind != StepKind.YOU && kind != StepKind.THINKING) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Column(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        // Prism bloom: soft iridescent washes rather than a flat page.
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Bloom(colors, Modifier.matchParentSize())
            Column(Modifier.fillMaxSize()) {
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
                            color = colors.fg, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (count == 0) {
                        EmptyState(colors, Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(
                            state = listState,
                            flingBehavior = flingBehavior,
                            contentPadding = PaddingValues(start = 8.dp, end = 14.dp, top = 12.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize().testTag("timeline"),
                        ) {
                            itemsIndexed(display, key = { _, s -> s.id }) { index, step ->
                                MessageRow(
                                    step = step,
                                    colors = colors,
                                    pulse = pulse,
                                    open = forceOpenIndex == index,
                                    onExpandSubagent = onExpandSubagent,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                    // "new content below" cue: floats above the composer and
                    // scrolls to the newest bubble on tap; hides once at the tail.
                    if (showNewCue) {
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 10.dp),
                        ) {
                            NewCue(colors) {
                                showNewCue = false
                                scope.launch { listState.animateScrollToItem((count - 1).coerceAtLeast(0)) }
                            }
                        }
                    }
                }
            }
        }

        if (error != null) {
            ErrorNotice(error, colors, onEditKey)
        }
        if (changes.editCount > 0) {
            ChangesCard(changes, colors)
        }
        UsageMeter(usage, colors)
        Composer(input, busy, colors, onInput, onSend, onStop, onToggleTheme, onEditKey)
    }
}

/** Three soft spectral blooms so the page reads as an iridescent surface. */
@Composable
private fun Bloom(colors: LumenColors, modifier: Modifier = Modifier) {
    Box(
        modifier.drawBehind {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.bloomA.copy(alpha = if (colors.dark) 0.22f else 0.14f), Color.Transparent),
                    center = Offset(w * 0.12f, -h * 0.02f),
                    radius = w * 0.9f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.bloomB.copy(alpha = if (colors.dark) 0.16f else 0.10f), Color.Transparent),
                    center = Offset(w * 0.9f, h * 0.0f),
                    radius = w * 0.8f,
                ),
            )
        },
    )
}

/**
 * A small pill that floats above the composer when new content lands off-screen.
 * Tapping it eases to the newest bubble and dismisses the cue.
 */
@Composable
private fun NewCue(colors: LumenColors, onClick: () -> Unit) {
    val edge = colors.spectrum.getOrElse(2) { colors.water }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surface)
            .border(1.dp, edge.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag("new-cue"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "\u2193 new",
            color = colors.water, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.6.sp,
        )
    }
}

/**
 * One message as a bubble. Your turns align right; the agent's align left with a
 * spectral edge hairline. Reasoning folds into a pill; tools nest in a compact
 * pocket inside the agent bubble.
 */
@Composable
private fun MessageRow(
    step: UiStep,
    colors: LumenColors,
    pulse: Float,
    open: Boolean,
    onExpandSubagent: (UiStep) -> Unit = {},
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

    val isYou = step.kind == StepKind.YOU

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (isYou) Arrangement.End else Arrangement.Start,
    ) {
        val bubbleShape = if (isYou) {
            RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)
        } else {
            RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)
        }

        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .then(
                    if (isYou) {
                        Modifier
                            .background(colors.surface, bubbleShape)
                            .border(1.dp, colors.rule, bubbleShape)
                    } else {
                        Modifier
                            .background(colors.surface, bubbleShape)
                            .border(1.dp, colors.spectrum.getOrElse(2) { colors.water }.copy(alpha = 0.20f), bubbleShape)
                    },
                )
                .padding(start = if (isYou) 14.dp else 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        ) {
            // role stamp for agent turns (double-tap it to copy the body)
            if (!isYou) {
                Row(
                    Modifier.pointerInput(step.id) {
                        detectTapGestures(onDoubleTap = {
                            clipboard.setText(AnnotatedString(step.body))
                            copied = true
                        })
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (copied) "copied" else step.label.lowercase(),
                        color = if (copied) colors.water else colors.dim,
                        fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 1.2.sp,
                    )
                    if (step.tag.isNotEmpty() && !copied) {
                        Text(
                            "  ${step.tag}",
                            color = colors.faint, fontFamily = Mono, fontSize = 10.sp,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
            }

            if (step.think != null) {
                ThinkSection(step.think, colors)
                Spacer(Modifier.height(8.dp))
            }

            if (step.running && step.body.isBlank()) {
                Text("thinking…", color = colors.water.copy(alpha = 0.5f + 0.5f * pulse), fontFamily = Mono, fontSize = 14.sp)
            } else {
                val body = step.body.ifBlank { step.summary }
                if (body.length > LONG_BODY_CHARS) {
                    LongBody(
                        body = body,
                        header = step.label.lowercase(),
                        colors = colors,
                    )
                } else {
                    Text(
                        body,
                        color = colors.fg, fontFamily = Mono, fontSize = 14.sp, lineHeight = 21.sp,
                    )
                }
            }

            if (step.rows.isNotEmpty()) {
                if (step.kind == StepKind.SUBAGENT) {
                    SubagentBubble(step, colors, onExpandSubagent)
                } else {
                    ToolPocket(step, colors, pulse)
                }
            }
        }
    }
}

/** Above this many characters, a message gets its own scroll with a pinned header. */
private const val LONG_BODY_CHARS = 900

/**
 * A long message: its own scroll area with the label pinned to the top and the
 * text fading out under it, so scrolling a wall of text feels anchored instead
 * of pushing the whole timeline.
 */
@Composable
private fun LongBody(body: String, header: String, colors: LumenColors) {
    val scroll = rememberScrollState()
    val atTop by remember { derivedStateOf { scroll.value <= 2 } }
    val atBottom by remember {
        derivedStateOf { scroll.value >= scroll.maxValue - 2 }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 460.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bg.copy(alpha = 0.35f)),
    ) {
        Column(Modifier.verticalScroll(scroll)) {
            // Pinned label; stays legible over the text as it scrolls under it.
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.bg.copy(alpha = 0.95f), colors.bg.copy(alpha = 0f)),
                        ),
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            ) {
                Text(header, color = colors.dim, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 1.2.sp)
            }
            Text(
                body,
                color = colors.fg, fontFamily = Mono, fontSize = 14.sp, lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }
        // Top fade: the text dissolves as it slides under the pinned label.
        if (!atTop) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.surface, colors.surface.copy(alpha = 0f)),
                        ),
                    ),
            )
        }
        // Bottom fade: a gentle hint that more text is below.
        if (!atBottom) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.surface.copy(alpha = 0f), colors.surface),
                        ),
                    ),
            )
        }
    }
}

/**
 * A subagent as its own card inside the message bubble: the task, a live status
 * dot, and the child's steps. Tapping the header loads the full transcript on
 * demand (the collapsed form only carries the first few steps).
 */
@Composable
private fun SubagentBubble(step: UiStep, colors: LumenColors, onExpand: (UiStep) -> Unit) {
    val loaded = step.childSteps.isNotEmpty()
    Column(
        Modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .padding(top = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.water.copy(alpha = 0.10f))
                .border(1.dp, colors.water.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
                .clickable(enabled = step.childId != null) { onExpand(step) }
                .padding(horizontal = 11.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(WaterShapes.droplet(tail = 0.55f))
                    .background(
                        when {
                            step.failed -> colors.danger()
                            else -> colors.water
                        },
                    ),
            )
            Spacer(Modifier.width(9.dp))
            Text("subagent", color = colors.water, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(
                when {
                    step.childLoading -> "loading…"
                    step.running -> "running…"
                    else -> if (loaded) "collapse" else "open"
                },
                color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp,
            )
        }
        val steps: List<UiStep> = if (loaded) step.childSteps else {
            step.rows.map { (k, v) ->
                UiStep(id = "kid:$k:$v", kind = StepKind.TOOL, label = k, tag = "", summary = v, body = v)
            }
        }
        Column(Modifier.padding(start = 10.dp, top = 7.dp)) {
            for (kid in steps) {
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(5.dp).clip(WaterShapes.droplet(tail = 0.5f)).background(colors.faint))
                    Spacer(Modifier.width(9.dp))
                    Text(kid.label, color = colors.dim, fontFamily = Mono, fontSize = 11.5.sp)
                    if (kid.summary.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            kid.summary, color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/** A spectrum teardrop mark; a ring when [ring]. */
@Composable
private fun Droplet(
    modifier: Modifier,
    brush: Brush,
    ring: Boolean,
    pulse: Float,
    colors: LumenColors,
) {
    val shape = WaterShapes.droplet(tail = 0.55f)
    Box(
        modifier
            .then(
                if (ring) {
                    Modifier.border(2.dp, colors.water.copy(alpha = 0.35f + 0.65f * pulse), shape)
                } else {
                    Modifier.background(brush, shape)
                },
            ),
    )
}

/**
 * The tool calls for a message, as a compact pocket inside the bubble. Collapsed
 * it is a single "▸ 3 tools · read, edit, bash" line; tapping expands the calls
 * with tight padding and each output preview clamped to two lines.
 */
@Composable
private fun ToolPocket(step: UiStep, colors: LumenColors, pulse: Float) {
    var expanded by remember(step.id) { mutableStateOf(false) }
    val edge = colors.spectrum.getOrElse(2) { colors.water }
    val count = step.merged.coerceAtLeast(1)
    val names = remember(step.toolNames, step.rows, step.label) {
        step.toolNames.ifEmpty { step.rows.map { it.first.lowercase() } }
            .asSequence()
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
            .toList()
            .ifEmpty { listOf(step.label.lowercase()) }
    }
    val summary = buildString {
        append(if (expanded) "\u25be " else "\u25b8 ")
        append(if (count > 1) "$count tools" else "1 tool")
        if (names.isNotEmpty()) append(" · " + names.joinToString(", "))
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .drawBehind {
                drawLine(
                    color = edge.copy(alpha = 0.22f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                    ),
                )
            }
            .padding(top = 3.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 30.dp)
                .clip(RoundedCornerShape(9.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 9.dp, vertical = 4.dp)
                .testTag("tools-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolStateMark(step.failed, step.running, pulse, colors)
            Spacer(Modifier.width(8.dp))
            Text(
                summary,
                color = when {
                    step.failed -> colors.danger()
                    step.running -> colors.water.copy(alpha = 0.5f + 0.5f * pulse)
                    else -> colors.water
                },
                fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.6.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("tools-summary"),
            )
            if (step.running) {
                Spacer(Modifier.width(8.dp))
                Text("running", color = colors.faint, fontFamily = Mono, fontSize = 10.sp)
            }
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column(Modifier.padding(top = 2.dp)) {
                for ((k, r) in step.rows.withIndex()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.bg.copy(alpha = 0.35f))
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                            .testTag("tool-row-$k"),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            if (step.failed) "\u00d7" else "\u2713",
                            color = if (step.failed) colors.danger() else colors.water,
                            fontFamily = Mono, fontSize = 10.5.sp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.first, color = colors.fg, fontFamily = Mono, fontSize = 11.5.sp)
                            if (r.second.isNotEmpty()) {
                                Text(
                                    r.second, color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("tool-output-$k"),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A small state dot for a tool run: danger when failed, pulsing while running. */
@Composable
private fun ToolStateMark(failed: Boolean, running: Boolean, pulse: Float, colors: LumenColors) {
    val shape = WaterShapes.droplet(tail = 0.55f)
    Box(
        Modifier
            .size(9.dp)
            .then(
                when {
                    failed -> Modifier.background(colors.danger(), shape)
                    running -> Modifier.background(colors.water.copy(alpha = 0.35f + 0.5f * pulse), shape)
                    else -> Modifier.background(colors.water.copy(alpha = 0.85f), shape)
                },
            ),
    )
}

/**
 * The reasoning folded into a step: a one-line header that expands on tap. It
 * starts collapsed, so once the answer arrives the thinking tucks away.
 */
@Composable
private fun ThinkSection(think: String, colors: LumenColors) {
    var open by remember(think) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.water.copy(alpha = 0.10f))
            .clickable { open = !open }
            .padding(horizontal = 10.dp, vertical = 7.dp)
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
            .testTag("think-toggle"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "▾" else "▸", color = colors.dim, fontFamily = Mono, fontSize = 11.sp)
            Spacer(Modifier.width(7.dp))
            Text(
                "thinking · ${think.length} chars",
                color = colors.dim, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.5.sp,
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
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
        Droplet(Modifier.size(11.dp), Brush.verticalGradient(listOf(colors.bloomA, colors.bloomB, colors.bloomC)), ring = false, pulse = 1f, colors = colors)
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
        Box(Modifier.size(9.dp).clip(WaterShapes.droplet(tail = 0.55f)).background(colors.danger()))
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

/**
 * A compact summary of the files the agent changed this run, pinned above the
 * composer. Collapsed to one prism-edged line; tapping opens the files, and
 * each file opens to its unified diff in a monospace block.
 */
@Composable
private fun ChangesCard(changes: RunChanges, colors: LumenColors) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var openFile by remember { mutableStateOf<String?>(null) }
    val edge = colors.spectrum.getOrElse(2) { colors.water }
    val shape = RoundedCornerShape(12.dp)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, edge.copy(alpha = 0.22f), shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 9.dp)
                .testTag("changes-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(WaterShapes.droplet(tail = 0.55f)).background(edge))
            Spacer(Modifier.width(9.dp))
            Text(
                "${if (expanded) "▾" else "▸"} ${changes.fileCount} files · " +
                    "${changes.editCount} edits · +${changes.added} −${changes.removed}",
                color = colors.fg, fontFamily = Mono, fontSize = 11.5.sp, letterSpacing = 0.4.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("changes-summary"),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Column(
                Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                for ((path, edits) in changes.byFile()) {
                    val added = edits.sumOf { it.added }
                    val removed = edits.sumOf { it.removed }
                    val fileOpen = openFile == path
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { openFile = if (fileOpen) null else path }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (fileOpen) "▾" else "▸", color = colors.dim, fontFamily = Mono, fontSize = 10.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            path, color = colors.dim, fontFamily = Mono, fontSize = 11.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("+$added −$removed", color = colors.water, fontFamily = Mono, fontSize = 11.sp)
                    }
                    AnimatedVisibility(visible = fileOpen) {
                        val diff = edits.joinToString("\n") { it.unifiedDiff }.trim()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .background(colors.bg.copy(alpha = 0.4f))
                                .border(1.dp, colors.rule, RoundedCornerShape(9.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                                .testTag("changes-diff"),
                        ) {
                            Text(
                                diff.ifEmpty { "(no diff)" },
                                color = colors.dim, fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
                val all = changes.byFile().values.flatten().joinToString("\n") { it.unifiedDiff }.trim()
                if (all.isNotEmpty()) {
                    Text(
                        "copy diff",
                        color = colors.accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { clipboard.setText(AnnotatedString(all)) }
                            .padding(horizontal = 6.dp, vertical = 5.dp)
                            .testTag("copy-diff"),
                    )
                }
            }
        }
    }
}

/**
 * A thin usage footer: rolled-up context tokens and, when known, cost. Hidden
 * entirely until the session has spent tokens, so an idle chat stays clean.
 */
@Composable
private fun UsageMeter(usage: Usage, colors: LumenColors) {
    if (usage.totalTokens <= 0) return
    val tokens = String.format(Locale.US, "%.1fk tok", usage.totalTokens / 1000.0)
    val cost = if (usage.costUsd > 0.0) String.format(Locale.US, " · \$%.4f", usage.costUsd) else ""
    Text(
        tokens + cost,
        color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 3.dp, bottom = 1.dp)
            .testTag("usage-meter"),
    )
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
    val canSend = input.isNotBlank()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh),
        label = "send-scale",
    )
    Column {
        Box(
            Modifier.fillMaxWidth().height(1.dp).drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, colors.bloomA.copy(alpha = 0.5f), colors.bloomB.copy(alpha = 0.5f), Color.Transparent),
                    ),
                )
            },
        )
        Row(
            Modifier.fillMaxWidth().background(colors.bg)
                .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(colors.surface)
                    .border(1.dp, colors.rule, RoundedCornerShape(50))
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                if (input.isEmpty()) {
                    Text("ask the agent…", color = colors.faint, fontFamily = Mono, fontSize = 14.sp)
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInput,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.water),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    modifier = Modifier.fillMaxWidth().testTag("composer"),
                )
            }
            Spacer(Modifier.width(8.dp))
            if (onEditKey != null) {
                Text(
                    "key", color = colors.faint, fontFamily = Mono, fontSize = 12.sp,
                    modifier = Modifier.clickable { onEditKey() }.padding(horizontal = 6.dp).testTag("key"),
                )
            }
            if (onToggleTheme != null) {
                Text(
                    "◐", color = colors.faint, fontFamily = Mono, fontSize = 15.sp,
                    modifier = Modifier.clickable { onToggleTheme() }.padding(horizontal = 6.dp).testTag("theme"),
                )
            }
            Box(
                Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .size(42.dp)
                    .clip(RoundedCornerShape(50))
                    .background(
                        when {
                            busy -> Brush.linearGradient(listOf(colors.danger(), colors.danger()))
                            canSend -> Brush.linearGradient(listOf(colors.bloomA, colors.bloomB, colors.bloomC))
                            else -> Brush.linearGradient(listOf(colors.surface, colors.surface))
                        },
                    )
                    .then(if (!busy && !canSend) Modifier.border(1.dp, colors.rule, RoundedCornerShape(50)) else Modifier)
                    .clickable(
                        enabled = busy || canSend,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = { if (busy) onStop() else onSend() },
                    )
                    .testTag(if (busy) "stop" else "send"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (busy) "■" else "↑",
                    color = if (busy || canSend) colors.bg else colors.faint,
                    fontFamily = Mono, fontSize = if (busy) 13.sp else 18.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
