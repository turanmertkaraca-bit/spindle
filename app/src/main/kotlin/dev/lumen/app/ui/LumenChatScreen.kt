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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
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

/** The "failed" colour — an explicit red, independent of the spectrum. */
private fun LumenColors.danger(): Color = if (dark) Color(0xFFFB7185) else Color(0xFFBE123C)

/**
 * The prism palette: near-black (or near-white) base with an iridescent
 * violet→azure→cyan→mint spectrum used as light — bubble edges, droplets, the
 * send button — never as a flat fill. Contrast was checked against the base
 * (foreground >16:1, spectrum >7:1 dark / >5:1 light).
 */
data class LumenColors(
    val bg: Color,
    val surface: Color,
    val fg: Color,
    val dim: Color,
    val faint: Color,
    val rule: Color,
    val accent: Color,
    /** Primary spectral hue (cyan); kept for the spine, cursor and focus glow. */
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
            accent = Color(0xFF6D28D9),
            water = Color(0xFF0E7490),
            spectrum = listOf(
                Color(0xFF6D28D9), Color(0xFF1D4ED8), Color(0xFF0E7490), Color(0xFF047857),
            ),
            dark = false,
        )
        val Dark = LumenColors(
            bg = Color(0xFF08070D), surface = Color(0xFF141221), fg = Color(0xFFF2EFFB),
            dim = Color(0xFFA49FC4), faint = Color(0xFF5B5675), rule = Color(0x17FFFFFF),
            accent = Color(0xFFA78BFA),
            water = Color(0xFF22D3EE),
            spectrum = listOf(
                Color(0xFFA78BFA), Color(0xFF60A5FA), Color(0xFF22D3EE), Color(0xFF34D399),
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
 * A bubble chat with a spine down the left edge. Your turns sit right, the
 * agent's left, and each agent bubble carries a spectrum droplet on the spine.
 * Reasoning collapses into a pill; tool calls nest in a pocket inside the
 * bubble. This is the render layer only — the data model is unchanged.
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
    val listState = rememberLazyListState()
    val flingBehavior = remember { calmFling() }

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

    // Follow the tail while streaming; stop if the reader scrolled up.
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(count, display.lastOrNull()?.id) {
        if (count == 0) return@LaunchedEffect
        if (followTail) listState.animateScrollToItem(count - 1)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                followTail = last >= count - 2
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
                            contentPadding = PaddingValues(start = 10.dp, end = 14.dp, top = 10.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                            modifier = Modifier.fillMaxSize().testTag("timeline"),
                        ) {
                            itemsIndexed(display, key = { _, s -> s.id }) { index, step ->
                                MessageRow(
                                    step = step,
                                    colors = colors,
                                    pulse = pulse,
                                    open = forceOpenIndex == index,
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }
        }

        if (error != null) {
            ErrorNotice(error, colors, onEditKey)
        }
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
 * One message as a bubble on the left spine. Your turns align right; the agent's
 * align left with a spectrum droplet on the spine and a spectral edge hairline.
 * Reasoning folds into a pill; tools nest in a pocket inside the agent bubble.
 */
@Composable
private fun MessageRow(
    step: UiStep,
    colors: LumenColors,
    pulse: Float,
    open: Boolean,
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
    val isTool = step.kind == StepKind.TOOL || step.kind == StepKind.SUBAGENT

    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (isYou) Arrangement.End else Arrangement.Start,
    ) {
        if (!isYou) {
            // The spine droplet: a spectrum teardrop at the bubble's leading edge.
            Spacer(Modifier.width(14.dp))
            Droplet(
                modifier = Modifier.padding(top = 14.dp).size(9.dp),
                brush = Brush.verticalGradient(listOf(colors.bloomA, colors.water)),
                ring = step.running,
                pulse = pulse,
                colors = colors,
            )
            Spacer(Modifier.width(9.dp))
        }

        val bubbleShape = if (isYou) {
            RoundedCornerShape(20.dp, 20.dp, 7.dp, 20.dp)
        } else {
            RoundedCornerShape(20.dp, 20.dp, 20.dp, 7.dp)
        }

        Column(
            Modifier
                .widthIn(max = 300.dp)
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                )
                .clip(bubbleShape)
                .then(
                    if (isYou) {
                        Modifier
                            .background(colors.surface, bubbleShape)
                            .border(1.dp, colors.rule, bubbleShape)
                    } else {
                        Modifier
                            .background(colors.surface, bubbleShape)
                            .border(1.dp, colors.spectrum.getOrElse(2) { colors.water }.copy(alpha = 0.22f), bubbleShape)
                            // A spectral hairline down the leading edge — the prism.
                            .drawBehind {
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        listOf(colors.bloomA, colors.bloomB, colors.bloomC),
                                    ),
                                    topLeft = Offset(0f, 12.dp.toPx()),
                                    size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height - 24.dp.toPx()),
                                )
                            }
                    },
                )
                .padding(start = if (isYou) 14.dp else 16.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        ) {
            // role stamp for agent turns
            if (!isYou) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                Text(
                    step.body.ifBlank { step.summary },
                    color = colors.fg, fontFamily = Mono, fontSize = 14.sp, lineHeight = 21.sp,
                    modifier = Modifier.pointerInput(step.id) {
                        detectTapGestures(onDoubleTap = {
                            clipboard.setText(AnnotatedString(step.body))
                            copied = true
                        })
                    },
                )
            }

            if (step.rows.isNotEmpty()) {
                ToolPocket(step, colors)
            }

            if (open && isTool && step.body.isNotBlank()) {
                // (force-open path already shows the body above)
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

/** The tool calls for a message, as a pocket inside the bubble. */
@Composable
private fun ToolPocket(step: UiStep, colors: LumenColors) {
    var expanded by remember(step.id) { mutableStateOf<Int?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .drawBehind {
                drawLine(
                    color = colors.spectrum.getOrElse(2) { colors.water }.copy(alpha = 0.22f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 4.dp.toPx()),
                    ),
                )
            }
            .padding(top = 9.dp),
    ) {
        Text(
            if (step.merged > 1) "${step.label} ×${step.merged}" else step.label,
            color = colors.water, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.sp,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        for ((k, r) in step.rows.withIndex()) {
            val isOpen = expanded == k
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.bg.copy(alpha = 0.4f))
                    .border(1.dp, colors.rule, RoundedCornerShape(9.dp))
                    .clickable { expanded = if (isOpen) null else k }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("✓", color = colors.water, fontFamily = Mono, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(r.first, color = colors.fg, fontFamily = Mono, fontSize = 12.sp)
                if (r.second.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        r.second, color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
            }
            if (isOpen && r.second.isNotEmpty()) {
                Text(
                    r.second,
                    color = colors.dim, fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 17.sp,
                    modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 6.dp, bottom = 4.dp),
                )
            }
        }
    }
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
        if (open) {
            Spacer(Modifier.height(6.dp))
            Text(
                think,
                color = colors.dim, fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.testTag("think-body"),
            )
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
