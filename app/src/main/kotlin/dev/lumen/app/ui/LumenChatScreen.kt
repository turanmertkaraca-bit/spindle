package dev.lumen.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.Backlink
import dev.lumen.app.FilePeek
import dev.lumen.app.PendingAsk
import dev.lumen.app.PendingImage
import dev.lumen.app.data.ProviderCatalogue
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.StepMapper
import dev.lumen.app.ui.model.UiImage
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.model.Usage
import dev.spindle.core.refs.FileKind
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Mono = FontFamily.Monospace

/** Fling friction: higher bleeds energy faster, so a flick never races away. */
private const val FLING_FRICTION = 3.0f

/**
 * Layout bounds for reasoning and literal bodies. A multi-hundred-KB string laid
 * out as one Text allocates and measures for hundreds of ms per frame; these cap
 * what any single layout sees. The opened reasoning window and the opt-in full
 * view share [textChunks] so no single Text is ever unbounded.
 */
private const val THINK_WINDOW_CHARS = 6_000
private const val TEXT_CHUNK_CHARS = 4_000
private const val LITERAL_MAX_CHARS = 8_000

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

/**
 * Keeps a nested scroll local. After the inner scrollable has done all it can,
 * the leftover delta is consumed here, so hitting the top (or bottom) of the
 * bounded tool output never chains a drag into the transcript behind it.
 */
private val BlockScrollChaining = object : NestedScrollConnection {
    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
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
    /** Opaque element boundary: the clean hairline drawn on cards and chips. */
    val outline: Color,
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
            bg = Color(0xFFF1F1F4), surface = Color(0xFFFFFFFF), fg = Color(0xFF1A1730),
            dim = Color(0xFF6B6588), faint = Color(0xFFA9A4C0), rule = Color(0x1A140A32),
            outline = Color(0xFFD5D5DC),
            accent = Color(0xFF6D3FC9),
            water = Color(0xFF16708A),
            spectrum = listOf(
                Color(0xFF7147C4), Color(0xFF335FB4), Color(0xFF186F82), Color(0xFF107455),
            ),
            dark = false,
        )
        val Dark = LumenColors(
            bg = Color(0xFF000000), surface = Color(0xFF141418), fg = Color(0xFFF2EFFB),
            dim = Color(0xFFA49FC4), faint = Color(0xFF5B5675), rule = Color(0x17FFFFFF),
            outline = Color(0xFF2E2E36),
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
    /** Open the project files cockpit from the chat top bar, when supplied. */
    onFiles: (() -> Unit)? = null,
    /** The interactive terminal from the chat top bar, when supplied. */
    onTerminal: (() -> Unit)? = null,
    /** Fork this chat at its head; null hides the action. */
    onFork: (() -> Unit)? = null,
    /** Rewind the active session to a store message id (from an assistant row). */
    onRewind: (String) -> Unit = {},
    /** Active primary agent: "build" | "plan". */
    agentMode: String = "build",
    /** Switch the primary agent. */
    onAgentMode: (String) -> Unit = {},
    /** Active model id; its basename shows in the composer's model chip. */
    model: String = "",
    /** Open/cycle the model picker; null hides the model chip. */
    onModel: (() -> Unit)? = null,
    onInput: (String) -> Unit = {},
    onSend: () -> Unit = {},
    onStop: () -> Unit = {},
    onToggleTheme: (() -> Unit)? = null,
    onEditKey: (() -> Unit)? = null,
    onExpandSubagent: (UiStep) -> Unit = {},
    ambient: Boolean = true,
    /** Rolled-up session token/cost totals; the meter hides until non-zero. */
    usage: Usage = Usage(),
    /** Per-session cost ceiling in USD; 0 means no limit (meter shows spend only). */
    budgetUsd: Double = 0.0,
    /** The model's context window in tokens; 0 keeps the classic usage line. */
    contextWindow: Int = 0,
    /** Estimated cost of the next turn in USD; negative hides it. */
    nextCostUsd: Double = -1.0,
    /** Tokens added since the last usage roll-up, for the compact meter. */
    newTokens: Int = 0,
    /** Structured file changes for this run; the card hides when empty. */
    changes: RunChanges = RunChanges.EMPTY,
    /** Live todo list for the current session; the board hides when empty. */
    todos: List<TodoItem> = emptyList(),
    /** The file currently shown in the peek sheet, or null when closed. */
    peek: FilePeek? = null,
    /** Dismiss the peek sheet (tap outside or the close affordance). */
    onClosePeek: () -> Unit = {},
    /** Open a referenced file in the peek sheet, optionally at a 1-based line. */
    onOpenFile: (String, Int?) -> Unit = { _, _ -> },
    /** Route assistant mention taps here when supplied (e.g. the Files cockpit). */
    onOpenMention: ((String, Int?) -> Unit)? = null,
    /** Revert a changed file to its pre-edit snapshot. */
    onRevert: (FileEdit) -> Unit = {},
    /** Workspace root handed to the pure [dev.spindle.core.refs.ReferenceResolver]. */
    cwd: String = "",
    /** Existence gate handed to the resolver: cwd-relative path -> is a file. */
    exists: (String) -> Boolean = { false },
    /** Paths changed this run; references to them get a stronger style. */
    touchedPaths: Set<String> = emptySet(),
    /** Workspace revision; a bump re-resolves mentions so new files become tappable. */
    fileRevision: Int = 0,
    /** Kind gate: when supplied, directory mentions link too; null keeps files only. */
    fileKind: ((String) -> FileKind?)? = null,
    /** Steps in the current session that reference the peeked file. */
    onBacklinks: (String) -> List<Backlink> = { emptyList() },
    /** Best-effort jump of the timeline to a step index (a backlink was tapped). */
    onJumpToStep: (Int) -> Unit = {},
    /** Workspace-relative paths matching a trailing `@` token, for completion. */
    onCompleteFiles: (String) -> List<String> = { emptyList() },
    /** A permission or question prompt pinned above the composer, or null. */
    ask: PendingAsk? = null,
    /** Answer the permission card: allow/deny, and whether to remember it. */
    onAnswerPermission: (Boolean, Boolean) -> Unit = { _, _ -> },
    /** Answer the question card with the selected options. */
    onAnswerQuestion: (List<String>) -> Unit = {},
    /** Skip the question card (first option or an empty selection). */
    onSkipQuestion: () -> Unit = {},
    /** Images queued to send with the next prompt, shown as chips above the composer. */
    attachments: List<PendingImage> = emptyList(),
    /** Drop the queued attachment at this index. */
    onRemoveAttachment: (Int) -> Unit = {},
    /** A soft notice rendered above the composer (e.g. model has no vision). */
    hint: String? = null,
    /** Open the sandboxed canvas on an `.html` page found in a message. */
    onOpenCanvas: (String) -> Unit = {},
    /** Queue an image for vision; when null the attach affordance is hidden. */
    onAttachImage: ((String, String, String) -> Unit)? = null,
    /** Off in tests/CI so the "answer landed" haptic never fires there. */
    haptics: Boolean = true,
    /** Show the lightweight in-chat quick settings sheet over the transcript. */
    quickSettings: Boolean = false,
    /** Open the quick settings sheet (composer / top-bar affordances). */
    onQuickSettings: (() -> Unit)? = null,
    /** Dismiss the quick settings sheet (scrim tap or close). */
    onCloseQuickSettings: () -> Unit = {},
    /** Active provider id, shown as chips in the quick settings sheet. */
    provider: String = "",
    /** Switch provider from the quick settings sheet. */
    onProvider: ((String) -> Unit)? = null,
    /** Active theme: "system" | "light" | "dark". */
    theme: String = "system",
    /** Switch theme from the quick settings sheet. */
    onTheme: ((String) -> Unit)? = null,
    /** Whether every tool asks for confirmation first. */
    askBeforeTools: Boolean = false,
    /** Toggle ask-before-tools from the quick settings sheet. */
    onAskBeforeTools: ((Boolean) -> Unit)? = null,
    /** Set the per-session cost ceiling from the quick settings sheet. */
    onMaxCost: ((Double) -> Unit)? = null,
    /** Open the full Settings screen from the quick settings footer. */
    onOpenFullSettings: (() -> Unit)? = null,
    /** Select a model ref (`provider/id`) from the quick settings sheet. */
    onModelSelect: ((String) -> Unit)? = null,
) {
    val listState = rememberLazyListState()
    val flingBehavior = remember { calmFling() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val hapticsActive = haptics && !LocalInspectionMode.current

    // The photo/document picker: bytes are read once and handed up as base64,
    // so the view model stays free of Android content URIs.
    val context = LocalContext.current
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && onAttachImage != null) {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: "image/*"
            val bytes = runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) {
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "image"
                onAttachImage(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
    }

    // A slow spectrum breathe while something is running; off when idle or in
    // tests. Kept as a lambda so the parent body never READS the animated value:
    // rows re-read it only inside their own draw phase, confining recomposition
    // to the glyphs instead of every timeline row every frame.
    val running = busy || steps.any { it.running }
    val pulse: () -> Float = if (ambient && running) {
        val t = rememberInfiniteTransition(label = "pulse")
        val value = t.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = LumenMotion.pulse,
            label = "pulse",
        )
        val read: () -> Float = { value.value }
        read
    } else {
        { 1f }
    }

    // Fold each think block into the answer/tool that follows, so it collapses
    // once the block has a real body. The raw rows stay intact upstream.
    val grouped = remember(steps) { StepMapper.groupSteps(steps) }

    // A working bubble between turns so the screen is never blank.
    val pending = busy && grouped.none { it.running } &&
        (grouped.isEmpty() || grouped.last().kind == StepKind.YOU)
    val display = remember(grouped, pending) {
        StepMapper.linkRuns(
            StepMapper.dedupeById(if (pending) grouped + WorkingStep else grouped),
        )
    }
    val count = display.size
    val last = display.lastOrNull()

    // `forceOpenIndex` indexes the RAW step list, but the timeline renders the
    // grouped/deduped `display` list; map raw → display by id so a folded row
    // still opens. Fall back to -1 (nothing open) when it cannot be mapped.
    val forceOpenId = remember(forceOpenIndex, steps, display) {
        forceOpenIndex?.let { steps.getOrNull(it)?.id }
    }

    // Follow the tail while streaming, gently. We re-anchor when content actually
    // grows and the reader is already at the bottom. Keying on the last row's
    // body LENGTH (not just its id) is what makes a streaming answer follow: a
    // single assistant Part only grows its body, so id/count alone never change.
    var followTail by remember { mutableStateOf(true) }
    var showNewCue by remember { mutableStateOf(false) }
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
    LaunchedEffect(count, last?.id, last?.body?.length) {
        if (count == 0 || !followTail) return@LaunchedEffect
        // Pin the last row to the tail whenever it (or a new one) grows; scrollBy
        // of the height delta keeps a streaming bubble glued to the bottom without
        // fighting the height changes above it.
        val info = listState.layoutInfo
        val lastVisible = info.visibleItemsInfo.lastOrNull { it.index == count - 1 }
        if (lastVisible == null) {
            // The last row is not composed yet: jump so it lays out, then pin next.
            listState.scrollToItem(count - 1)
        } else {
            val delta = (info.viewportEndOffset - info.afterContentPadding) - (lastVisible.offset + lastVisible.size)
            if (delta > 0) listState.scrollBy(delta.toFloat())
        }
    }
    // The viewport can also SHRINK without any content change: the usage meter,
    // the todo/changes/ask cards or the IME appear and steal height, leaving the
    // tail clipped below the fold. The growth-keyed effect above never fires then,
    // so observe layout directly and re-pin the last row whenever a gap opens
    // under it. Guarded by followTail and not-while-flinging so a deliberate
    // scroll-up is never yanked back.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }
            .collect { info ->
                if (!followTail || listState.isScrollInProgress) return@collect
                val lastItem = info.visibleItemsInfo
                    .lastOrNull { it.index == info.totalItemsCount - 1 } ?: return@collect
                val gap = (info.viewportEndOffset - info.afterContentPadding) -
                    (lastItem.offset + lastItem.size)
                if (gap > 0) listState.scrollBy(gap.toFloat())
            }
    }
    // The `↓ new` cue fires when the content grows off-screen. Growth is detected
    // from the DATA (count OR last body length), because a streaming row scrolled
    // far up may not be composed at all and so has no layout entry to observe.
    // Layout clears the cue the moment the reader is back at the tail. `cueArmed`
    // skips the first emission so opening a session never flashes the cue.
    var cueArmed by remember { mutableStateOf(false) }
    LaunchedEffect(count, last?.id, last?.body?.length) {
        if (!cueArmed) {
            cueArmed = true
            return@LaunchedEffect
        }
        if (count == 0 || followTail) return@LaunchedEffect
        showNewCue = true
        val kind = last?.kind
        if (hapticsActive && kind != null && kind != StepKind.YOU && kind != StepKind.THINKING) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    // Clear the cue as soon as the last row is fully visible again (or the list is
    // short). This runs only on layout changes, so it never re-triggers growth.
    LaunchedEffect(listState, count) {
        snapshotFlow {
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.lastOrNull { it.index == count - 1 }
            val lastVertex = info.visibleItemsInfo.lastOrNull()
            val tailVisible = item != null &&
                item.offset + item.size <= info.viewportEndOffset - info.afterContentPadding + 1
            val atTail = info.totalItemsCount == 0 ||
                (lastVertex != null && lastVertex.index >= info.totalItemsCount - 1)
            tailVisible || atTail
        }.collect { settled -> if (settled) showNewCue = false }
    }

    Box(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            // Prism bloom: soft iridescent washes rather than a flat page.
            Box(Modifier.fillMaxWidth().weight(1f)) {
                Bloom(colors, Modifier.matchParentSize())
                Column(Modifier.fillMaxSize()) {
                    if (onHome != null) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BarControl(
                                label = "\u2039", tag = "home", colors = colors,
                                tint = colors.dim, fontSize = 17.sp, onClick = onHome,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                title.ifBlank { "chat" },
                                color = colors.fg, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            // A fixed row of equally-treated controls, so a long
                            // title ellipsizes instead of crowding the buttons.
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (onFork != null) {
                                    BarControl("fork", "fork", colors, colors.accent, onClick = onFork)
                                }
                                if (onFiles != null) {
                                    BarControl("files", "open-files", colors, colors.accent, onClick = onFiles)
                                }
                                if (onTerminal != null) {
                                    BarControl("shell", "open-terminal", colors, colors.accent, onClick = onTerminal)
                                }
                                if (onQuickSettings != null) {
                                    BarControl("\u2699", "chat-settings-top", colors, colors.accent, onClick = onQuickSettings)
                                }
                            }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (count == 0) {
                            EmptyState(colors, Modifier.align(Alignment.Center))
                        } else {
                            LazyColumn(
                                state = listState,
                                flingBehavior = flingBehavior,
                                contentPadding = PaddingValues(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
                                modifier = Modifier.fillMaxSize().testTag("timeline"),
                            ) {
                                itemsIndexed(display, key = { _, s -> s.id }) { index, step ->
                                    // Rows in a tool run sit tight (3dp) and draw a
                                    // thin rail joining them; independent rows keep
                                    // the old 10dp breath. Top padding, not the
                                    // LazyColumn arrangement, so a linked row can
                                    // tuck right under the one above it.
                                    val topPad = when {
                                        index == 0 -> 0.dp
                                        step.linkedAbove -> 3.dp
                                        else -> 10.dp
                                    }
                                    MessageRow(
                                        step = step,
                                        colors = colors,
                                        pulse = pulse,
                                        open = forceOpenId != null && step.id == forceOpenId,
                                        agentMode = agentMode,
                                        onExpandSubagent = onExpandSubagent,
                                        cwd = cwd,
                                        exists = exists,
                                        touchedPaths = touchedPaths,
                                        onOpenFile = onOpenFile,
                                        onOpenMention = onOpenMention,
                                        fileRevision = fileRevision,
                                        fileKind = fileKind,
                                        onRewind = onRewind,
                                        onOpenCanvas = onOpenCanvas,
                                        modifier = Modifier
                                            .animateItem()
                                            .then(
                                                if (step.linkedAbove) {
                                                    Modifier.drawBehind {
                                                        val x = 1.dp.toPx()
                                                        drawLine(
                                                            color = colors.outline,
                                                            start = Offset(x, 0f),
                                                            end = Offset(x, size.height),
                                                            strokeWidth = 1.dp.toPx(),
                                                        )
                                                    }
                                                } else {
                                                    Modifier
                                                },
                                            )
                                            .padding(top = topPad)
                                            .testTag(
                                                if (step.linkedAbove) "run-link-${step.id}" else "run-${step.id}",
                                            ),
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
            if (hint != null) {
                HintNotice(hint, colors)
            }
            if (changes.editCount > 0) {
                ChangesCard(changes, colors, onRevert, onOpenFile)
            }
            TodoBoard(todos, colors, pulse)
            UsageMeter(usage, budgetUsd, colors, contextWindow, nextCostUsd, newTokens)
            ask?.let {
                AskCard(it, colors, onAnswerPermission, onAnswerQuestion, onSkipQuestion)
            }
            Composer(
                input, busy, colors, onInput, onSend, onStop, onToggleTheme, onEditKey, agentMode, onAgentMode,
                model = model,
                onModel = onModel,
                onQuickSettings = onQuickSettings,
                attachments = attachments,
                onRemoveAttachment = onRemoveAttachment,
                onAttach = onAttachImage?.let { { pickImage.launch("image/*") } },
                onCompleteFiles = onCompleteFiles,
            )
        }
        peek?.let { p ->
            val backlinks = remember(p.path, steps, touchedPaths) { onBacklinks(p.path) }
            FilePeekOverlay(
                peek = p,
                colors = colors,
                onClose = onClosePeek,
                backlinks = backlinks,
                onJumpToStep = { index ->
                    onClosePeek()
                    // Map the raw step index onto the grouped display list by id;
                    // a folded/merged row may not be addressable, so fall back to
                    // the nearest index (best-effort).
                    val id = steps.getOrNull(index)?.id
                    val mapped = if (id != null) display.indexOfFirst { it.id == id } else -1
                    val slot = if (mapped >= 0) mapped else index.coerceIn(0, (count - 1).coerceAtLeast(0))
                    scope.launch { listState.animateScrollToItem(slot) }
                    onJumpToStep(index)
                },
            )
        }
        if (quickSettings) {
            QuickSettingsSheet(
                colors = colors,
                provider = provider,
                model = model,
                theme = theme,
                askBeforeTools = askBeforeTools,
                budgetUsd = budgetUsd,
                onProvider = onProvider,
                onModel = onModelSelect,
                onTheme = onTheme,
                onMaxCost = onMaxCost,
                onAskBeforeTools = onAskBeforeTools,
                onEditKey = onEditKey,
                onOpenFullSettings = onOpenFullSettings,
                onClose = onCloseQuickSettings,
            )
        }
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
    Row(
        Modifier
            .shadow(LumenElevation.floating, LumenShapes.pill)
            .clip(LumenShapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.pill)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 5.dp)
            .testTag("new-cue"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "\u2193 latest",
            color = colors.water, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.6.sp,
        )
    }
}

/**
 * A top-bar affordance with a real touch target. Bare monospace text was easy to
 * miss; this boxes every action (back, fork, files, shell) in the same surface
 * pill with a >=44dp target so all four read and tap identically.
 */
@Composable
private fun BarControl(
    label: String,
    tag: String,
    colors: LumenColors,
    tint: Color,
    fontSize: TextUnit = 12.sp,
    onClick: () -> Unit,
) {
    val shape = LumenShapes.control
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .clickable { onClick() }
            .padding(horizontal = 11.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tint, fontFamily = Mono, fontSize = fontSize, fontWeight = FontWeight.Medium)
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
    pulse: () -> Float,
    open: Boolean,
    agentMode: String = "build",
    onExpandSubagent: (UiStep) -> Unit = {},
    cwd: String = "",
    exists: (String) -> Boolean = { false },
    touchedPaths: Set<String> = emptySet(),
    onOpenFile: (String, Int?) -> Unit = { _, _ -> },
    onOpenMention: ((String, Int?) -> Unit)? = null,
    fileRevision: Int = 0,
    fileKind: ((String) -> FileKind?)? = null,
    onRewind: (String) -> Unit = {},
    onOpenCanvas: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(step.id) { mutableStateOf(false) }
    var rewindArmed by remember(step.id) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1200)
            copied = false
        }
    }

    val isYou = step.kind == StepKind.YOU
    val isTool = step.kind == StepKind.TOOL || step.kind == StepKind.SUBAGENT
    val htmlPath = remember(step.id, step.body, step.summary, step.rows) {
        if (isYou || isTool) null else firstHtmlPath(step)
    }

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = if (isYou) Arrangement.End else Arrangement.Start,
    ) {
        if (isTool) {
            // Tools and subagents are their own inset card, not an answer bubble:
            // a leading accent stripe, a compact header and a collapsible body.
            ToolCard(
                step = step,
                colors = colors,
                pulse = pulse,
                startExpanded = open,
                onExpandSubagent = onExpandSubagent,
                modifier = Modifier.widthIn(max = 328.dp),
            )
        } else {
            val bubbleShape = if (isYou) {
                LumenShapes.bubbleYou
            } else {
                LumenShapes.bubbleAgent
            }

            Column(
                Modifier
                    .widthIn(max = 328.dp)
                    .clip(bubbleShape)
                    .background(colors.surface, bubbleShape)
                    .border(1.dp, colors.outline, bubbleShape)
                    .padding(start = if (isYou) 12.dp else 13.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            ) {
                // role stamp for agent turns (double-tap it to copy the body)
                if (!isYou) {
                    Row(
                        Modifier.pointerInput(step.id) {
                            detectTapGestures(
                                onDoubleTap = {
                                    clipboard.setText(AnnotatedString(step.body))
                                    copied = true
                                },
                                onLongPress = {
                                    if (step.messageId != null) rewindArmed = !rewindArmed
                                },
                            )
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // A friendly role label, never the internal row tag (the
                        // opaque `174c78` hash). Agent turns speak as the active
                        // agent ("build"/"plan") so a first-time reader knows who
                        // is talking.
                        Text(
                            if (copied) "copied" else agentLabel(step, agentMode),
                            color = if (copied) colors.water else colors.dim,
                            fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 1.2.sp,
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                }

                if (step.think != null) {
                    ThinkSection(step.think, colors)
                    Spacer(Modifier.height(6.dp))
                }

                if (isYou && step.images.isNotEmpty()) {
                    // A sent image must be visible in the bubble, not only as a
                    // chip above the composer before sending.
                    UserImages(step.images, colors)
                    if (step.body.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        WindowedText(step.body, colors)
                    }
                } else if (step.running && step.body.isBlank()) {
                    Text("thinking…", color = colors.water.copy(alpha = 0.5f + 0.5f * pulse()), fontFamily = Mono, fontSize = 14.sp)
                } else {
                    val body = step.body.ifBlank { step.summary }
                    // Assistant prose is markdown; the user's own words and every
                    // other row stay literal, so "what I typed is what I see".
                    // The literal path is still windowed, so a pathological paste
                    // cannot lay out an unbounded string.
                    if (step.kind == StepKind.ASSISTANT) {
                        MarkdownBody(
                            markdown = body,
                            colors = colors,
                            streaming = step.running,
                            cwd = cwd,
                            exists = exists,
                            touchedPaths = touchedPaths,
                            onOpenFile = onOpenMention ?: onOpenFile,
                            fileRevision = fileRevision,
                            fileKind = fileKind,
                        )
                    } else {
                        WindowedText(body, colors)
                    }
                }

                val mid = step.messageId
                if (htmlPath != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "\u25b6 view",
                        color = colors.water, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .testTag("canvas-view-${step.id}")
                            .clip(LumenShapes.small)
                            .border(1.dp, colors.water.copy(alpha = 0.35f), LumenShapes.small)
                            .clickable { onOpenCanvas(htmlPath) }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                if (rewindArmed && mid != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "rewind to here",
                        color = colors.water, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(LumenShapes.small)
                            .border(1.dp, colors.water.copy(alpha = 0.35f), LumenShapes.small)
                            .clickable {
                                onRewind(mid)
                                rewindArmed = false
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                            .testTag("rewind-${step.id}"),
                    )
                }
            }
        }
    }
}

/**
 * The human role label for a bubble: the active agent name for an answer (so the
 * reader sees "build"/"plan" rather than an id), the step's own word otherwise.
 * The internal [UiStep.tag] hash is deliberately never shown.
 */
private fun agentLabel(step: UiStep, agentMode: String): String = when (step.kind) {
    StepKind.ASSISTANT -> agentMode.lowercase().ifBlank { "agent" }
    else -> step.label.lowercase().ifBlank { "agent" }
}

/**
 * The images attached to a user turn, drawn inside the bubble. Each decodes its
 * base64 to a thumbnail OFF the main thread, downsampled to the display size and
 * cached, so a large photo cannot OOM or jank the frame; when the bytes cannot be
 * decoded it falls back to a labelled chip so the attachment is never invisible.
 */
@Composable
private fun UserImages(images: List<UiImage>, colors: LumenColors) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (image in images) {
            val key = remember(image.base64) { hashKey(image.base64) }
            val bytes = remember(image.base64) {
                runCatching { Base64.decode(image.base64, Base64.DEFAULT) }.getOrDefault(ByteArray(0))
            }
            val bitmap by produceState<Bitmap?>(initialValue = ImageThumbCache.get(key), key1 = key) {
                if (value == null) {
                    value = withContext(Dispatchers.Default) {
                        val decoded = decodeThumbnail(
                            bytes,
                            reqW = THUMBNAIL_WIDTH_PX,
                            reqH = THUMBNAIL_HEIGHT_PX,
                        )
                        if (decoded != null) ImageThumbCache.put(key, decoded)
                        decoded
                    }
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!.asImageBitmap(),
                    contentDescription = image.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(min = 120.dp, max = 240.dp)
                        .heightIn(max = 200.dp)
                        .clip(LumenShapes.panel)
                        .border(1.dp, colors.outline, LumenShapes.panel)
                        .testTag("msg-image"),
                )
            } else {
                Row(
                    Modifier
                        .widthIn(max = 260.dp)
                        .clip(LumenShapes.panel)
                        .background(colors.bg.copy(alpha = 0.4f))
                        .border(1.dp, colors.outline, LumenShapes.panel)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                        .testTag("msg-image"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(20.dp)
                            .clip(LumenShapes.glyph)
                            .background(colors.water.copy(alpha = 0.16f))
                            .border(1.dp, colors.water.copy(alpha = 0.45f), LumenShapes.glyph),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("img", color = colors.water, fontFamily = Mono, fontSize = 7.5.sp)
                    }
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            image.name,
                            color = colors.fg, fontFamily = Mono, fontSize = 11.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(image.mime, color = colors.faint, fontFamily = Mono, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** Target thumbnail size in pixels; matches the 240dp max bubble width at ~mdpi. */
private const val THUMBNAIL_WIDTH_PX = 480
private const val THUMBNAIL_HEIGHT_PX = 400

/** A content hash for a base64 payload, used as the thumbnail cache key. */
internal fun hashKey(base64: String): String {
    var h = 1125899906842597L
    // Hash a bounded prefix: the payload identity is stable and we avoid scanning
    // megabytes of base64 on every composition of the row.
    val n = minOf(base64.length, 256)
    for (i in 0 until n) h = 31 * h + base64[i].code
    return "$n:$h:${base64.length}"
}

/**
 * Pure, JVM-testable sampling math: pick the largest power-of-two [inSampleSize]
 * that keeps the decoded bitmap at least [reqW] x [reqH] (i.e. never smaller than
 * requested), then decode with it. Returns null when the bytes are empty or not
 * a decodable image. The bounds decode is cheap (headers only); the full decode
 * happens once with the chosen sample size, off the caller's thread.
 */
internal fun decodeThumbnail(bytes: ByteArray, reqW: Int, reqH: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    if (!looksLikeImage(bytes)) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val w = reqW.coerceAtLeast(1)
    val h = reqH.coerceAtLeast(1)
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, w, h)
    }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) }.getOrNull()
}

/**
 * Cheap magic-byte check for the image formats [BitmapFactory] can decode. This
 * keeps garbage from reaching the platform decoder (whose JVM shadow is more
 * permissive than a real device) and is also correct on device.
 */
internal fun looksLikeImage(bytes: ByteArray): Boolean {
    fun startsWith(vararg sig: Int): Boolean {
        if (bytes.size < sig.size) return false
        for (i in sig.indices) if ((bytes[i].toInt() and 0xff) != sig[i]) return false
        return true
    }
    // PNG, JPEG, GIF, WEBP (RIFF....WEBP), BMP.
    if (startsWith(0x89, 0x50, 0x4e, 0x47)) return true
    if (startsWith(0xff, 0xd8, 0xff)) return true
    if (startsWith(0x47, 0x49, 0x46, 0x38)) return true
    if (startsWith(0x52, 0x49, 0x46, 0x46) && bytes.size >= 12 &&
        (bytes[8].toInt() and 0xff) == 0x57 && (bytes[9].toInt() and 0xff) == 0x45 &&
        (bytes[10].toInt() and 0xff) == 0x42 && (bytes[11].toInt() and 0xff) == 0x50
    ) {
        return true
    }
    if (startsWith(0x42, 0x4d)) return true
    return false
}

/**
 * The power-of-two downsample factor that keeps the image at least [reqW]x[reqH]
 * while shrinking it as much as possible. Pure and directly unit-tested.
 */
internal fun sampleSizeFor(srcW: Int, srcH: Int, reqW: Int, reqH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || reqW <= 0 || reqH <= 0) return 1
    var sample = 1
    while (srcW / (sample * 2) >= reqW && srcH / (sample * 2) >= reqH) {
        sample *= 2
    }
    return sample
}

/** A small bounded cache of decoded message thumbnails, keyed by content hash. */
private object ImageThumbCache {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun get(key: String): Bitmap? = cache.get(key)

    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
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
 * A tool or subagent call as its own inset card — deliberately unlike an answer
 * bubble: a leading accent stripe, a compact monospace header (glyph, name, a
 * one-line summary, a status pill) and a body that stays hidden until tapped.
 * The expanded body is the only place raw output, rows or a child transcript
 * show; it is bounded and scrolls in place.
 */
@Composable
private fun ToolCard(
    step: UiStep,
    colors: LumenColors,
    pulse: () -> Float,
    startExpanded: Boolean,
    onExpandSubagent: (UiStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(step.id, startExpanded) { mutableStateOf(startExpanded) }
    val isSub = step.kind == StepKind.SUBAGENT

    // Read the animated `pulse` only inside draw so a running card's stripe does
    // not recompose the card (and thus every row) each frame.
    if (step.running) {
        val accentBase = colors.water
        ToolCardFrame(
            step = step,
            colors = colors,
            isSub = isSub,
            expanded = expanded,
            onToggle = {
                if (!expanded && isSub && step.childId != null && step.childSteps.isEmpty()) {
                    onExpandSubagent(step)
                }
                expanded = !expanded
            },
            accentForDraw = { accentBase.copy(alpha = 0.5f + 0.5f * pulse()) },
            accentForStatic = accentBase.copy(alpha = 0.75f),
            bodyPulse = pulse,
            modifier = modifier,
            shape = LumenShapes.card,
        )
        return
    }
    val accent = when {
        step.failed -> colors.danger()
        else -> colors.spectrum.getOrElse(2) { colors.water }
    }
    ToolCardFrame(
        step = step,
        colors = colors,
        isSub = isSub,
        expanded = expanded,
        onToggle = {
            if (!expanded && isSub && step.childId != null && step.childSteps.isEmpty()) {
                onExpandSubagent(step)
            }
            expanded = !expanded
        },
        accentForDraw = { accent },
        accentForStatic = accent,
        bodyPulse = pulse,
        modifier = modifier,
        shape = LumenShapes.card,
    )
}

/**
 * The tool card's chrome (stripe, header, collapsible body). Split out so the
 * running accent can be read inside its own draw callback via [accentForDraw]
 * while the border uses the non-animated [accentForStatic].
 */
@Composable
private fun ToolCardFrame(
    step: UiStep,
    colors: LumenColors,
    isSub: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    accentForDraw: () -> Color,
    accentForStatic: Color,
    bodyPulse: () -> Float,
    modifier: Modifier,
    shape: RoundedCornerShape,
) {
    val count = step.merged.coerceAtLeast(1)
    val names = remember(step.toolNames, step.rows, step.label, isSub) {
        step.toolNames.ifEmpty { if (isSub) emptyList() else step.rows.map { it.first.lowercase() } }
            .asSequence()
            .map { it.lowercase() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(4)
            .toList()
    }
    val summary = remember(count, names, step.summary, step.label, isSub) {
        when {
            count > 1 && names.isNotEmpty() -> "$count tools · " + names.joinToString(", ")
            count > 1 -> "$count tools"
            isSub -> step.summary.ifBlank { "subagent" }
            step.summary.isNotBlank() -> step.summary
            else -> step.label.lowercase()
        }
    }
    val title = (if (isSub) step.label.ifBlank { "subagent" } else step.label.ifBlank { "tool" }).lowercase()
    // One plain-language failure line, never the raw transport (`× [timeout]
    // Command timed out after 30ms`). The metadata/body it reads is bounded and
    // parsed once per change.
    val errorLine = remember(step.failed, step.toolMetadata, step.body) {
        if (step.failed) StepMapper.failureLine(step) else null
    }

    Column(
        modifier
            .shadow(LumenElevation.card, shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .drawBehind {
                val accent = accentForDraw()
                val x = 1.dp.toPx()
                drawLine(
                    color = accent,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .testTag("tool-card"),
    ) {
        if (step.think != null) {
            Box(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp)) {
                ThinkSection(step.think, colors)
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(start = 10.dp, end = 9.dp, top = 8.dp, bottom = if (errorLine != null) 3.dp else 8.dp)
                .testTag("tools-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolGlyph(accentForStatic, step.failed, step.running)
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = colors.fg, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                summary,
                color = colors.dim,
                fontFamily = Mono, fontSize = 10.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("tools-summary"),
            )
            Spacer(Modifier.width(8.dp))
            ToolStatusPill(step.failed, step.running, step.childLoading, colors)
        }
        if (errorLine != null) {
            Text(
                errorLine,
                color = colors.danger(),
                fontFamily = Mono, fontSize = 10.5.sp, lineHeight = 14.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 10.dp, end = 9.dp, bottom = 8.dp)
                    .testTag("tool-error"),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = LumenMotion.expand) + fadeIn(animationSpec = LumenMotion.fade),
            exit = shrinkVertically(animationSpec = LumenMotion.expand) + fadeOut(animationSpec = LumenMotion.fade),
        ) {
            ToolCardBody(step, colors, isSub, bodyPulse)
        }
    }
}

/**
 * The expanded tool/subagent body: a bounded scroll region holding the parsed
 * rows (or the child transcript for a subagent), falling back to the raw output
 * when there are no rows. This is the only place raw output is drawn.
 */
@Composable
private fun ToolCardBody(step: UiStep, colors: LumenColors, isSub: Boolean, pulse: () -> Float) {
    val toolScroll = rememberScrollState()
    val atBottom by remember { derivedStateOf { toolScroll.value >= toolScroll.maxValue - 2 } }
    val showTree = isSub && (step.childSteps.isNotEmpty() || step.childLoading)
    val entries: List<Pair<String, String>> = if (isSub && step.childSteps.isNotEmpty()) {
        step.childSteps.map { it.label to it.summary }
    } else {
        step.rows
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .nestedScroll(BlockScrollChaining),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(toolScroll)
                .padding(start = 12.dp, end = 10.dp, top = 2.dp, bottom = 10.dp),
        ) {
            // The structured inspector sits above the raw output: a compact,
            // labelled summary of what the tool reported (exit code, duration,
            // path, counts…). Rows render as today after it.
            val inspector = remember(step.toolMetadata) { toolInspector(step.toolMetadata) }
            if (inspector.isNotEmpty()) {
                InspectorBlock(inspector, colors)
                Spacer(Modifier.height(7.dp))
            }
            when {
                // A subagent's child transcript renders as an indented call tree;
                // while it is being read, a small spinner row stands in.
                step.childLoading -> ChildLoadingRow(colors, pulse)
                showTree -> step.childSteps.forEach { child ->
                    ChildTreeRow(child, depth = 0, colors = colors, pulse = pulse)
                }
                entries.isEmpty() -> RawOutputText(
                    step.body.ifBlank { step.summary },
                    colors = colors,
                    modifier = Modifier,
                    tag = "tool-output-0",
                )
                else -> {
                    for ((k, r) in entries.withIndex()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(LumenShapes.row)
                                .background(colors.bg.copy(alpha = 0.35f))
                                .border(1.dp, colors.outline, LumenShapes.row)
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
                                if (r.second.isNotEmpty() && r.second != r.first) {
                                    Text(
                                        r.second, color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                        modifier = Modifier.testTag("tool-output-$k"),
                                    )
                                }
                            }
                        }
                        if (k != entries.lastIndex) Spacer(Modifier.height(3.dp))
                    }
                    // The structured rows only cover the first few output lines;
                    // render the rest of the raw output underneath so a 50-line
                    // result is fully readable inside the same bounded scroll area.
                    val extra = remember(step.body, entries.size) {
                        StepMapper.toolExtraLines(step.body, entries.size)
                    }
                    if (extra.isNotEmpty()) {
                        Spacer(Modifier.height(7.dp))
                        RawOutputText(
                            extra.joinToString("\n"),
                            colors = colors,
                            modifier = Modifier.fillMaxWidth(),
                            tag = "tool-output-rest",
                        )
                    }
                }
            }
        }
        // Gentle hint that the bounded output scrolls further.
        if (!atBottom) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
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

/** Friendly labels for the metadata keys worth surfacing in a card. */
private val METADATA_LABELS = mapOf(
    "exitCode" to "exit",
    "durationMs" to "duration",
    "path" to "path",
    "replacements" to "replacements",
    "files" to "files",
    "count" to "count",
    "lines" to "lines",
)

/**
 * The short, plain-language facts worth showing above a tool's output. Only known
 * keys with a real value survive: transport flags (`timeoutMs`), counters that
 * merely restate the body (`bytes`, `totalLines`) and `truncated=false` are noise
 * and are dropped. A genuine truncation becomes a single "output truncated" note.
 */
private fun toolInspector(metadata: Map<String, String>): List<Pair<String, String>> {
    if (metadata.isEmpty()) return emptyList()
    val out = ArrayList<Pair<String, String>>(METADATA_LABELS.size)
    for ((key, label) in METADATA_LABELS) {
        val raw = metadata[key] ?: continue
        if (raw.isBlank()) continue
        out += label to if (key == "durationMs") formatDuration(raw) else raw
    }
    if (metadata["truncated"] == "true") out += "output" to "truncated"
    return out
}

private fun formatDuration(raw: String): String {
    val ms = raw.toLongOrNull() ?: return raw
    val tenths = (ms + 50) / 100
    return "${tenths / 10}.${tenths % 10}s"
}

/** A compact, monospace metadata block drawn above a tool's raw output. */
@Composable
private fun InspectorBlock(rows: List<Pair<String, String>>, colors: LumenColors) {
    Column(Modifier.fillMaxWidth().testTag("tool-inspector")) {
        for ((i, row) in rows.withIndex()) {
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.dim)) { append(row.first) }
                    append(" ")
                    withStyle(SpanStyle(color = colors.fg)) { append(row.second) }
                },
                fontFamily = Mono, fontSize = 10.5.sp, lineHeight = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .testTag("tool-inspector-$i"),
            )
        }
    }
}

/** The deepest nesting the subagent call tree draws before it flattens. */
private const val MAX_CHILD_DEPTH = 3

/**
 * One row of a subagent's call tree: a kind glyph, the step's label and its
 * one-line summary, indented by [depth] and joined to its parent by a thin
 * connector. Children recurse until [MAX_CHILD_DEPTH], so a deep agent call
 * chain never marches off the right edge.
 */
@Composable
private fun ChildTreeRow(child: UiStep, depth: Int, colors: LumenColors, pulse: () -> Float) {
    val indent = (depth * 14).dp
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = indent)
            .drawBehind {
                if (depth > 0) {
                    val x = 1.dp.toPx()
                    drawLine(colors.outline, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
            }
            .padding(start = 11.dp, top = 3.dp, bottom = 3.dp, end = 2.dp)
            .testTag("child-row-$depth"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            childGlyph(child.kind),
            color = childTint(child, colors),
            fontFamily = Mono, fontSize = 10.5.sp,
            modifier = Modifier.widthIn(min = 12.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            child.label.lowercase().ifBlank { childKindName(child.kind) },
            color = colors.fg, fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            child.summary.ifBlank { child.body }.replace(Regex("\\s+"), " ").trim(),
            color = if (child.failed) colors.danger() else colors.faint,
            fontFamily = Mono, fontSize = 10.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (child.failed) {
            Spacer(Modifier.width(6.dp))
            Text("!", color = colors.danger(), fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (depth < MAX_CHILD_DEPTH) {
        child.childSteps.forEach { grand -> ChildTreeRow(grand, depth + 1, colors, pulse) }
    }
}

/** A tiny arc spinner (driven by the ambient [pulse]) for a loading child read. */
@Composable
private fun ChildLoadingRow(colors: LumenColors, pulse: () -> Float) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 11.dp, top = 4.dp, bottom = 4.dp)
            .testTag("child-loading"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .drawBehind {
                    val w = 1.5.dp.toPx()
                    drawArc(
                        color = colors.water.copy(alpha = 0.22f),
                        startAngle = 0f, sweepAngle = 360f, useCenter = false,
                        style = Stroke(width = w),
                    )
                    drawArc(
                        color = colors.water.copy(alpha = 0.4f + 0.6f * pulse()),
                        startAngle = -90f + 360f * pulse(), sweepAngle = 90f, useCenter = false,
                        style = Stroke(width = w, cap = StrokeCap.Round),
                    )
                },
        )
        Spacer(Modifier.width(8.dp))
        Text("loading subagent…", color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp)
    }
}

/** A compact step-kind glyph for a tree row (no emoji; box/mono marks only). */
private fun childGlyph(kind: StepKind): String = when (kind) {
    StepKind.YOU -> "\u25b8"
    StepKind.THINKING -> "~"
    StepKind.TOOL -> "\u203a"
    StepKind.SUBAGENT -> "\u00bb"
    StepKind.QUESTION -> "?"
    StepKind.ASSISTANT -> "\u2261"
}

/** The lower-case word used when a tree row has no label. */
private fun childKindName(kind: StepKind): String = when (kind) {
    StepKind.YOU -> "you"
    StepKind.THINKING -> "thinking"
    StepKind.TOOL -> "tool"
    StepKind.SUBAGENT -> "subagent"
    StepKind.QUESTION -> "question"
    StepKind.ASSISTANT -> "answer"
}

/** Tint a tree row by its step kind, drawn from the prism spectrum. */
private fun childTint(child: UiStep, colors: LumenColors): Color = when {
    child.failed -> colors.danger()
    child.running -> colors.water
    child.kind == StepKind.SUBAGENT -> colors.spectrum.getOrElse(0) { colors.accent }
    child.kind == StepKind.TOOL -> colors.water
    child.kind == StepKind.ASSISTANT -> colors.spectrum.getOrElse(3) { colors.water }
    else -> colors.faint
}

/**
 * The live todo board, pinned above the composer next to the changes card. It
 * stays hidden until the session has a list, shows the done/total count on one
 * line, and expands to a checklist with a per-status glyph. Pure hoisted state.
 */
@Composable
private fun TodoBoard(todos: List<TodoItem>, colors: LumenColors, pulse: () -> Float) {
    if (todos.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val done = todos.count { it.status == TodoStatus.DONE }
    val edge = colors.spectrum.getOrElse(3) { colors.water }
    val shape = LumenShapes.card

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .shadow(LumenElevation.card, shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 7.dp)
                .testTag("todo-toggle"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(WaterShapes.droplet(tail = 0.55f)).background(edge))
            Spacer(Modifier.width(9.dp))
            Text(
                "${if (expanded) "\u25be" else "\u25b8"} $done/${todos.size} tasks",
                color = colors.fg, fontFamily = Mono, fontSize = 11.5.sp, letterSpacing = 0.4.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("todo-summary"),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(animationSpec = LumenMotion.expand) + fadeIn(animationSpec = LumenMotion.fade),
            exit = shrinkVertically(animationSpec = LumenMotion.expand) + fadeOut(animationSpec = LumenMotion.fade),
        ) {
            Column(
                Modifier
                    .heightIn(max = 220.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
                todos.forEachIndexed { i, todo ->
                    val (glyph, tint) = when (todo.status) {
                        TodoStatus.PENDING -> "\u25cb" to colors.faint
                        TodoStatus.IN_PROGRESS -> "\u25d0" to colors.water.copy(alpha = 0.35f + 0.65f * pulse())
                        TodoStatus.DONE -> "\u2713" to colors.spectrum.getOrElse(3) { colors.water }
                        TodoStatus.CANCELLED -> "\u2715" to colors.faint
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .testTag("todo-item-$i"),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(glyph, color = tint, fontFamily = Mono, fontSize = 12.sp)
                        Spacer(Modifier.width(9.dp))
                        Text(
                            todo.content,
                            color = when (todo.status) {
                                TodoStatus.DONE -> colors.dim
                                TodoStatus.CANCELLED -> colors.faint
                                else -> colors.fg
                            },
                            fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 16.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** A small rounded tool glyph: a mono chevron, tinted by state. */
@Composable
private fun ToolGlyph(accent: Color, failed: Boolean, running: Boolean) {
    Box(
        Modifier
            .size(16.dp)
            .clip(LumenShapes.glyph)
            .background(accent.copy(alpha = if (running) 0.14f else 0.12f))
            .border(1.dp, accent.copy(alpha = 0.50f), LumenShapes.glyph),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (failed) "!" else "\u203a",
            color = accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        )
    }
}

/** A compact status pill: failed (danger), running (water) or ok (mint). */
@Composable
private fun ToolStatusPill(failed: Boolean, running: Boolean, loading: Boolean, colors: LumenColors) {
    val (label, tint) = when {
        failed -> "failed" to colors.danger()
        running -> "running" to colors.water
        loading -> "loading" to colors.faint
        else -> "ok" to colors.spectrum.getOrElse(3) { colors.water }
    }
    Text(
        label,
        color = tint.copy(alpha = 0.9f), fontFamily = Mono, fontSize = 9.sp, letterSpacing = 0.4.sp,
        modifier = Modifier
            .clip(LumenShapes.pill)
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 6.dp, vertical = 1.5.dp)
            .testTag("tool-status"),
    )
}

/**
 * The reasoning folded into a step: a one-line header that expands on tap. It
 * starts collapsed, so once the answer arrives the thinking tucks away.
 *
 * Layout is bounded at every step: collapsed shows only the header and char
 * count, the opened body lays out at most [THINK_WINDOW_CHARS] and offers a
 * "show all" opt-in, and that full view renders the string as [TEXT_CHUNK_CHARS]
 * pieces inside a height-bounded scroll box. Crucially there is NO
 * `animateContentSize`: a growing, unbounded text under it re-measured the whole
 * string every frame, which is the jitter + ANR from the field report.
 */
@Composable
private fun ThinkSection(think: String, colors: LumenColors) {
    var open by remember(think) { mutableStateOf(false) }
    var showAll by remember(think) { mutableStateOf(false) }
    // A single low-contrast line: `thinking` faint, the character count fainter
    // still, so the row reads as ambient context rather than a headline. No filled
    // panel — the reasoning recedes until the reader taps it open.
    val header = remember(think.length, colors) {
        buildAnnotatedString {
            withStyle(SpanStyle(color = colors.faint)) { append("thinking") }
            withStyle(SpanStyle(color = colors.faint.copy(alpha = 0.5f))) {
                append(" \u00b7 ${think.length} chars")
            }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 3.dp)
            .testTag("think-toggle"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "\u25be" else "\u25b8", color = colors.faint, fontFamily = Mono, fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
            Text(header, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 0.4.sp)
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = LumenMotion.expand) + fadeIn(animationSpec = LumenMotion.fade),
            exit = shrinkVertically(animationSpec = LumenMotion.expand) + fadeOut(animationSpec = LumenMotion.fade),
        ) {
            Column(Modifier.padding(start = 16.dp, top = 6.dp).testTag("think-body")) {
                if (showAll) {
                    // Explicit opt-in: the whole reasoning, chunked so each
                    // StaticLayout stays small, inside a bounded scroll box.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Column {
                            for (chunk in textChunks(think, TEXT_CHUNK_CHARS)) {
                                Text(
                                    chunk,
                                    color = colors.dim, fontSize = 13.5.sp, lineHeight = 20.sp,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "show less",
                        color = colors.accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(LumenShapes.small)
                                .border(1.dp, colors.outline, LumenShapes.small)
                                .clickable { showAll = false }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("think-show-less"),
                    )
                } else {
                    Text(
                        think.take(THINK_WINDOW_CHARS),
                        color = colors.dim, fontSize = 13.5.sp, lineHeight = 20.sp,
                    )
                    if (think.length > THINK_WINDOW_CHARS) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "\u2026[${think.length - THINK_WINDOW_CHARS} chars hidden] \u00b7 show all",
                            color = colors.accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(LumenShapes.small)
                                .border(1.dp, colors.outline, LumenShapes.small)
                                .clickable { showAll = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .testTag("think-show-all"),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A literal (non-markdown) body, bounded the same way: short text renders as a
 * plain [Text], a pathological long one windows to a prefix with an explicit
 * "show all" that lays the whole thing out in a height-bounded scroll box.
 * Used for the user's own words and any non-assistant, non-tool body.
 */
@Composable
private fun WindowedText(
    text: String,
    colors: LumenColors,
    modifier: Modifier = Modifier,
    color: Color = colors.fg,
    fontSize: TextUnit = 14.sp,
    lineHeight: TextUnit = 21.sp,
) {
    var showAll by remember(text) { mutableStateOf(false) }
    if (text.length <= LITERAL_MAX_CHARS) {
        Text(
            text,
            color = color, fontFamily = FontFamily.Default, fontSize = fontSize, lineHeight = lineHeight,
            modifier = modifier,
        )
        return
    }
    Column(modifier) {
        if (showAll) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Column {
                    for (chunk in textChunks(text, TEXT_CHUNK_CHARS)) {
                        Text(chunk, color = color, fontFamily = FontFamily.Default, fontSize = fontSize, lineHeight = lineHeight)
                    }
                }
            }
            Text(
                "show less",
                color = colors.accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .border(1.dp, colors.outline, LumenShapes.small)
                    .clickable { showAll = false }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("literal-show-less"),
            )
        } else {
            Text(
                text.take(LITERAL_MAX_CHARS),
                color = color, fontFamily = FontFamily.Default, fontSize = fontSize, lineHeight = lineHeight,
            )
            Text(
                "\u2026[${text.length - LITERAL_MAX_CHARS} chars hidden] \u00b7 show all",
                color = colors.accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .border(1.dp, colors.outline, LumenShapes.small)
                    .clickable { showAll = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("literal-show-all"),
            )
        }
    }
}

/**
 * A monospace tool-output block. Short output stays a single Text (tag on the
 * Text itself, preserving hit-testing and scroll-to); a pathological result is
 * chunked so no single layout is unbounded. The card around it is already
 * height-bounded, so this only trims the per-layout cost.
 */
@Composable
private fun RawOutputText(text: String, colors: LumenColors, modifier: Modifier, tag: String) {
    if (text.length <= LITERAL_MAX_CHARS) {
        Text(
            text,
            color = colors.dim, fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = modifier.testTag(tag),
        )
        return
    }
    Column(modifier.testTag(tag)) {
        for (chunk in textChunks(text, TEXT_CHUNK_CHARS)) {
            Text(chunk, color = colors.dim, fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp)
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
            .clip(LumenShapes.panel)
            .background(colors.rule)
            .border(1.dp, colors.danger().copy(alpha = 0.45f), LumenShapes.panel)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).clip(WaterShapes.droplet(tail = 0.55f)).background(colors.danger()))
        Spacer(Modifier.width(10.dp))
        Text(
            text = if (auth) "Authentication failed — check your API key." else oneLine(message),
            color = colors.fg, fontFamily = FontFamily.Default, fontSize = 12.5.sp, lineHeight = 17.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (auth && onEditKey != null) {
            Spacer(Modifier.width(10.dp))
            Text(
                "update key",
                color = colors.accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
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
private fun ChangesCard(
    changes: RunChanges,
    colors: LumenColors,
    onRevert: (FileEdit) -> Unit = {},
    onOpenFile: (String, Int?) -> Unit = { _, _ -> },
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember { mutableStateOf(false) }
    var openFile by remember { mutableStateOf<String?>(null) }
    val edge = colors.spectrum.getOrElse(2) { colors.water }
    val shape = LumenShapes.card

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .shadow(LumenElevation.card, shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 7.dp)
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
            enter = expandVertically(animationSpec = LumenMotion.expand) + fadeIn(animationSpec = LumenMotion.fade),
            exit = shrinkVertically(animationSpec = LumenMotion.expand) + fadeOut(animationSpec = LumenMotion.fade),
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
                            .clip(LumenShapes.inset)
                            .clickable { openFile = if (fileOpen) null else path }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (fileOpen) "▾" else "▸", color = colors.dim, fontFamily = Mono, fontSize = 10.sp)
                        Spacer(Modifier.width(7.dp))
                        Text(
                            path, color = colors.dim, fontFamily = Mono, fontSize = 11.5.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clip(LumenShapes.small)
                                .clickable { onOpenFile(path, null) }
                                .padding(vertical = 2.dp)
                                .testTag("changes-file-$path"),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("+$added −$removed", color = colors.water, fontFamily = Mono, fontSize = 11.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "revert",
                            color = colors.accent, fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(LumenShapes.small)
                                .clickable { edits.lastOrNull()?.let(onRevert) }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .testTag("changes-revert-$path"),
                        )
                    }
                    AnimatedVisibility(
                        visible = fileOpen,
                        enter = expandVertically(animationSpec = LumenMotion.expand) + fadeIn(animationSpec = LumenMotion.fade),
                        exit = shrinkVertically(animationSpec = LumenMotion.expand) + fadeOut(animationSpec = LumenMotion.fade),
                    ) {
                        val diff = edits.joinToString("\n") { it.unifiedDiff }.trim()
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(LumenShapes.inset)
                                .background(colors.bg.copy(alpha = 0.4f))
                                .border(1.dp, colors.outline, LumenShapes.inset)
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
                            .clip(LumenShapes.small)
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
 * A thin usage footer. With a known context window it reads forward: tokens the
 * last turn added, the estimated cost of the next one, and the window size.
 * Otherwise it falls back to the rolled-up tokens/cost and — when a budget is
 * set — spend against the ceiling. Hidden entirely until the session has spent
 * tokens, so an idle chat stays clean.
 */
@Composable
private fun UsageMeter(
    usage: Usage,
    budgetUsd: Double,
    colors: LumenColors,
    contextWindow: Int = 0,
    nextCostUsd: Double = -1.0,
    newTokens: Int = 0,
) {
    if (usage.totalTokens <= 0) return
    // With a live context window the meter is compact and forward-looking: how
    // much this turn added, what the next turn should cost, and the ceiling.
    // Otherwise it keeps the classic rolled-up token/cost/budget line.
    val label = if (contextWindow > 0) {
        val next = if (nextCostUsd >= 0.0) String.format(Locale.US, " \u00b7 next \$%.4f", nextCostUsd) else ""
        String.format(Locale.US, "\u2248 %d new", newTokens) + next +
            String.format(Locale.US, " \u00b7 ctx %d", contextWindow)
    } else {
        val tokens = String.format(Locale.US, "%.1fk tok", usage.totalTokens / 1000.0)
        val cost = if (usage.costUsd > 0.0) String.format(Locale.US, " \u00b7 \$%.4f", usage.costUsd) else ""
        val cap = if (budgetUsd > 0.0) {
            val pct = (usage.costUsd / budgetUsd * 100).coerceIn(0.0, 999.0)
            String.format(Locale.US, " / \$%.2f (%.0f%%)", budgetUsd, pct)
        } else {
            ""
        }
        tokens + cost + cap
    }
    val over = budgetUsd > 0.0 && usage.costUsd >= budgetUsd
    Text(
        label,
        color = if (over) colors.accent else colors.faint,
        fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 3.dp, bottom = 1.dp)
            .testTag("usage-meter"),
    )
}

/**
 * A prompt pinned above the composer. Permission asks render the tool and its
 * detail with allow-once / always-allow / deny; questions render tappable
 * option chips (single-select answers on tap, multi-select adds an Answer).
 * Pure hoisted state — all wiring comes from the caller.
 */
@Composable
private fun AskCard(
    ask: PendingAsk,
    colors: LumenColors,
    onAnswerPermission: (Boolean, Boolean) -> Unit,
    onAnswerQuestion: (List<String>) -> Unit,
    onSkipQuestion: () -> Unit,
) {
    val shape = LumenShapes.card
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .shadow(LumenElevation.card, shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .padding(12.dp)
            .testTag("ask-card"),
    ) {
        when (ask) {
            is PendingAsk.Permission -> PermissionAsk(ask, colors, onAnswerPermission)
            is PendingAsk.Question -> QuestionAsk(ask, colors, onAnswerQuestion, onSkipQuestion)
        }
    }
}

@Composable
private fun PermissionAsk(
    ask: PendingAsk.Permission,
    colors: LumenColors,
    onAnswer: (Boolean, Boolean) -> Unit,
) {
    Text("permission", color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
    Text(ask.tool, color = colors.fg, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    if (ask.detail.isNotBlank()) {
        Spacer(Modifier.height(4.dp))
        Text(
            ask.detail, color = colors.dim, fontFamily = FontFamily.Default, fontSize = 12.sp, lineHeight = 17.sp,
            maxLines = 4, overflow = TextOverflow.Ellipsis,
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AskAction(colors, "allow once", "ask-allow", accent = true) { onAnswer(true, false) }
        AskAction(colors, "always allow", "ask-always", accent = false) { onAnswer(true, true) }
        AskAction(colors, "deny", "ask-deny", accent = false) { onAnswer(false, false) }
    }
}

@Composable
private fun QuestionAsk(
    ask: PendingAsk.Question,
    colors: LumenColors,
    onAnswer: (List<String>) -> Unit,
    onSkip: () -> Unit,
) {
    var selected by remember(ask.id) { mutableStateOf(emptySet<String>()) }
    Text("question", color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 2.sp)
    Spacer(Modifier.height(6.dp))
    Text(ask.question, color = colors.fg, fontFamily = FontFamily.Default, fontSize = 13.sp, lineHeight = 18.sp)
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ask.options.forEachIndexed { index, option ->
            val isSelected = option in selected
            Text(
                option,
                color = if (isSelected) colors.fg else colors.dim,
                fontFamily = FontFamily.Default, fontSize = 12.5.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LumenShapes.inset)
                    .border(1.dp, if (isSelected) colors.water else colors.outline, LumenShapes.inset)
                    .background(if (isSelected) colors.bg.copy(alpha = 0.35f) else Color.Transparent)
                    .clickable {
                        if (ask.multiple) {
                            selected = if (isSelected) selected - option else selected + option
                        } else {
                            onAnswer(listOf(option))
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 9.dp)
                    .testTag("ask-option-$index"),
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ask.multiple) {
            AskAction(colors, "answer", "ask-answer", accent = true) { onAnswer(selected.toList()) }
        }
        AskAction(colors, "skip", "ask-skip", accent = false) { onSkip() }
    }
}

@Composable
private fun AskAction(
    colors: LumenColors,
    label: String,
    tag: String,
    accent: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (accent) colors.bg else colors.fg,
        fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(LumenShapes.pill)
            .background(if (accent) colors.water else colors.surface)
            .border(1.dp, if (accent) colors.water else colors.outline, LumenShapes.pill)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .testTag(tag),
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
    agentMode: String,
    onAgentMode: (String) -> Unit,
    model: String = "",
    onModel: (() -> Unit)? = null,
    onQuickSettings: (() -> Unit)? = null,
    attachments: List<PendingImage> = emptyList(),
    onRemoveAttachment: (Int) -> Unit = {},
    onAttach: (() -> Unit)? = null,
    onCompleteFiles: (String) -> List<String> = { emptyList() },
) {
    val canSend = input.isNotBlank() || attachments.isNotEmpty()
    // The field owns its focus; the placeholder hides while focused so the caret
    // and its first glyph can never overlap the hint.
    val fieldInteraction = remember { MutableInteractionSource() }
    val inputFocused by fieldInteraction.collectIsFocusedAsState()
    val atToken = remember(input) { AtToken.find(input)?.groupValues?.get(1) }
    // The completion walk can recurse the whole workspace, so it must never run
    // during composition. Hoist it into a LaunchedEffect keyed on the stable
    // token string (not the unstable callback) and run it on the IO dispatcher.
    val complete = rememberUpdatedState(onCompleteFiles)
    var suggestions by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(atToken) {
        suggestions = if (atToken == null) {
            emptyList()
        } else {
            withContext(Dispatchers.IO) { complete.value(atToken).take(MAX_SUGGESTIONS) }
        }
    }
    val pickSuggestion: (String) -> Unit = { path ->
        val match = AtToken.find(input)
        if (match != null) onInput(input.substring(0, match.range.first) + "@" + path + " ")
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = LumenMotion.press,
        label = "send-scale",
    )
    Column {
        if (attachments.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().background(colors.bg)
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 14.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                attachments.forEachIndexed { index, image ->
                    AttachmentChip(colors, image, index, onRemoveAttachment)
                    if (index != attachments.lastIndex) Spacer(Modifier.width(8.dp))
                }
            }
        }
        FileSuggestions(suggestions, colors, pickSuggestion)
        // One calm rounded card: the input on top, its small controls beneath.
        // The focus hairline lives on the card, so the whole composer reads as a
        // single active surface rather than a field plus a toolbar.
        val cardShape = LumenShapes.composer
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp)
                .clip(cardShape)
                .background(colors.surface)
                .border(1.dp, if (inputFocused) colors.water else colors.outline, cardShape)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                // A real placeholder: shown only while empty and unfocused, at the
                // exact content origin of the field, so it never overlaps the caret.
                if (input.isEmpty() && !inputFocused) {
                    Text(
                        "ask the agent…", color = colors.faint, fontFamily = Mono, fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 6.dp).testTag("composer-hint"),
                    )
                }
                BasicTextField(
                    value = input,
                    onValueChange = onInput,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 14.sp),
                    cursorBrush = SolidColor(colors.water),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    // Never send while busy: the send affordance is a stop button
                    // then, and the IME action must not race it into a double-send.
                    keyboardActions = KeyboardActions(onSend = { if (canSend && !busy) onSend() }),
                    interactionSource = fieldInteraction,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("composer"),
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onAttach != null) {
                    // Secondary affordances, dim by design, so the send control is
                    // unmistakably the card's one primary action. The eye reuses
                    // the same picker: an attached image IS the vision path.
                    ComposerControl("+", "attach-image", colors.dim, onAttach)
                    Spacer(Modifier.width(2.dp))
                    ComposerControl("\u25c9", "attach-vision", colors.faint, onAttach)
                    Spacer(Modifier.width(6.dp))
                }
                AgentModeChip("build", "agent-build", agentMode == "build", colors.water, colors, onAgentMode)
                Spacer(Modifier.width(5.dp))
                AgentModeChip("plan", "agent-plan", agentMode == "plan", colors.accent, colors, onAgentMode)
                val modelLabel = model.substringAfterLast('/')
                if (onModel != null && modelLabel.isNotBlank()) {
                    Spacer(Modifier.width(5.dp))
                    // Tapping the model opens the picker (kept for compatibility)
                    // and the lightweight quick settings sheet over the chat.
                    ModelChip(modelLabel, colors) {
                        onModel?.invoke()
                        onQuickSettings?.invoke()
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onEditKey != null) {
                    Text(
                        "key", color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp,
                        modifier = Modifier
                            .clip(LumenShapes.small)
                            .clickable { onEditKey() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("key"),
                    )
                }
                if (onQuickSettings != null) {
                    Text(
                        "\u2699", color = colors.faint, fontFamily = Mono, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(LumenShapes.small)
                            .clickable { onQuickSettings() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                            .testTag("chat-settings"),
                    )
                }
                if (onToggleTheme != null) {
                    Text(
                        "◐", color = colors.faint, fontFamily = Mono, fontSize = 13.sp,
                        modifier = Modifier
                            .clip(LumenShapes.small)
                            .clickable { onToggleTheme() }
                            .padding(horizontal = 4.dp)
                            .testTag("theme"),
                    )
                }
                Spacer(Modifier.width(4.dp))
                // The primary action: a filled circular target. Idle-with-text is
                // a bright spectral send; busy is a solid danger stop; blank-and-idle
                // is a dim, bordered, disabled send so the control never shifts.
                val actionShape = LumenShapes.pill
                Box(
                    Modifier
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .size(40.dp)
                        .clip(actionShape)
                        .background(
                            when {
                                busy -> SolidColor(colors.danger())
                                canSend -> Brush.linearGradient(listOf(colors.bloomA, colors.bloomB, colors.bloomC))
                                else -> SolidColor(colors.surface)
                            },
                        )
                        .then(if (!busy && !canSend) Modifier.border(1.dp, colors.outline, actionShape) else Modifier)
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
                        fontFamily = Mono, fontSize = if (busy) 12.sp else 17.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/** A tiny monospace glyph control for the composer card's bottom row. */
@Composable
private fun ComposerControl(
    glyph: String,
    tag: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Text(
        glyph,
        color = tint, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(LumenShapes.small)
            .clickable { onClick() }
            .padding(horizontal = 5.dp, vertical = 2.dp)
            .testTag(tag),
    )
}

/**
 * The active model's basename as a small chip; tapping invokes [onClick] (the
 * picker). Kept dim so the send circle stays the loudest control in the row.
 */
@Composable
private fun ModelChip(label: String, colors: LumenColors, onClick: () -> Unit) {
    Text(
        label,
        color = colors.dim, fontFamily = Mono, fontSize = 9.5.sp, fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .widthIn(max = 96.dp)
            .clip(LumenShapes.pill)
            .background(colors.water.copy(alpha = 0.10f))
            .border(1.dp, colors.outline, LumenShapes.pill)
            .clickable { onClick() }
            .padding(horizontal = 7.dp, vertical = 2.dp)
            .testTag("model-chip"),
    )
}

/** The trailing `@path` token the composer completes, if any. */
private val AtToken = Regex("""@([^\s]*)$""")

/** Most completion rows the popup shows at once. */
private const val MAX_SUGGESTIONS = 6

/**
 * A small list of `@` completions drawn just above the composer field. Tapping
 * a row replaces the trailing token with `@<path> `. Minimal and non-modal: it
 * disappears as soon as the input stops ending in a token.
 */
@Composable
private fun FileSuggestions(
    suggestions: List<String>,
    colors: LumenColors,
    onPick: (String) -> Unit,
) {
    if (suggestions.isEmpty()) return
    val shape = LumenShapes.card
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 14.dp, bottom = 6.dp)
            .shadow(LumenElevation.floating, shape)
            .clip(shape)
            .background(colors.surface)
            .border(1.dp, colors.outline, shape)
            .heightIn(max = 208.dp)
            .verticalScroll(rememberScrollState())
            .testTag("file-suggestions"),
    ) {
        suggestions.forEachIndexed { index, path ->
            Text(
                path,
                color = colors.dim, fontFamily = Mono, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(path) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("file-suggestion-$index"),
            )
        }
    }
}

/**
 * A queued image as a removable pill above the composer. The name is clamped so
 * a long path cannot stretch the row.
 */
@Composable
private fun AttachmentChip(
    colors: LumenColors,
    image: PendingImage,
    index: Int,
    onRemove: (Int) -> Unit,
) {
    Row(
        Modifier
            .clip(LumenShapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.pill)
            .padding(start = 9.dp, end = 4.dp, top = 3.dp, bottom = 3.dp)
            .testTag("attachment-$index"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            image.name,
            color = colors.dim, fontFamily = Mono, fontSize = 10.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            "\u00d7",
            color = colors.faint, fontFamily = Mono, fontSize = 14.sp,
            modifier = Modifier
                .clip(LumenShapes.pill)
                .clickable { onRemove(index) }
                .padding(horizontal = 5.dp, vertical = 1.dp)
                .testTag("attachment-remove-$index"),
        )
    }
}

/** A soft, neutral notice above the composer — never the danger styling. */
@Composable
private fun HintNotice(message: String, colors: LumenColors) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(WaterShapes.droplet(tail = 0.55f)).background(colors.faint))
        Spacer(Modifier.width(8.dp))
        Text(
            message, color = colors.faint, fontFamily = FontFamily.Default, fontSize = 11.5.sp,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("composer-notice"),
        )
    }
}

/** A path-like `*.html`/`*.htm` token, e.g. `pages/demo.html`. */
private val HtmlPath = Regex("[A-Za-z0-9_./-]+\\.html?\\b")

/**
 * The first self-contained HTML path a step mentions or produced, or null.
 * External URLs are skipped: only workspace-relative files can open in canvas.
 */
private fun firstHtmlPath(step: UiStep): String? {
    val hay = buildString {
        append(step.body)
        append('\n').append(step.summary)
        for ((_, value) in step.rows) append('\n').append(value)
    }
    for (match in HtmlPath.findAll(hay)) {
        if (match.value.startsWith("//") || match.value.startsWith("http")) continue
        val path = match.value.trimStart('.', '/')
        if (path.isBlank()) continue
        return path
    }
    return null
}

/** A compact build/plan selector; plan tints with the accent so it reads distinct. */
@Composable
private fun AgentModeChip(
    label: String,
    tag: String,
    selected: Boolean,
    tint: Color,
    colors: LumenColors,
    onSelect: (String) -> Unit,
) {
    Text(
        label,
        color = if (selected) tint else colors.faint,
        fontFamily = Mono, fontSize = 10.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(LumenShapes.pill)
            .background(if (selected) tint.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (selected) tint.copy(alpha = 0.45f) else colors.outline, LumenShapes.pill)
            .clickable { onSelect(label) }
            .padding(horizontal = 9.dp, vertical = 3.dp)
            .testTag(tag),
    )
}

/**
 * A dismissible file peek: a bottom sheet showing the referenced path, a
 * scrollable monospace body with 1-based line numbers, and the highlighted line
 * tinted with the accent. Tapping the scrim or "close" dismisses it; the capped
 * body notes truncation.
 */
@Composable
private fun FilePeekOverlay(
    peek: FilePeek,
    colors: LumenColors,
    onClose: () -> Unit,
    backlinks: List<Backlink> = emptyList(),
    onJumpToStep: (Int) -> Unit = {},
) {
    val listState = rememberLazyListState()
    val highlight = peek.highlight
    LaunchedEffect(peek.path, highlight) {
        if (highlight != null && highlight in 1..peek.lines.size) {
            listState.scrollToItem(highlight - 1)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (colors.dark) 0.62f else 0.38f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
            .testTag("file-peek"),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(LumenElevation.floating, LumenShapes.sheet)
                .clip(LumenShapes.sheet)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.sheet)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .padding(bottom = 10.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    peek.path,
                    color = colors.fg, fontFamily = Mono, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag("peek-path"),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "close",
                    color = colors.accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .clickable { onClose() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("peek-close"),
                )
            }
            if (peek.truncated) {
                Text(
                    "truncated at ${peek.lines.size} lines",
                    color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.4.sp,
                    modifier = Modifier
                        .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
                        .testTag("peek-truncated"),
                )
            }
            if (backlinks.isNotEmpty()) {
                PeekBacklinks(backlinks, colors, onJumpToStep)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).testTag("peek-body"),
            ) {
                itemsIndexed(peek.lines) { index, line ->
                    val n = index + 1
                    val hl = n == peek.highlight
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (hl) colors.accent.copy(alpha = 0.16f) else Color.Transparent)
                            .then(if (hl) Modifier.testTag("peek-highlight") else Modifier)
                            .padding(horizontal = 16.dp, vertical = 1.dp),
                    ) {
                        Text(
                            n.toString().padStart(4),
                            color = if (hl) colors.accent else colors.faint,
                            fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp,
                            modifier = Modifier.width(42.dp),
                        )
                        Text(
                            line.ifEmpty { " " },
                            color = if (hl) colors.fg else colors.dim,
                            fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The "backlinks" list inside the peek sheet: the earlier steps that reference
 * the open file. Tapping one dismisses the peek and jumps the timeline to it;
 * a touched step (the file changed there) is tinted with the water accent.
 */
@Composable
private fun PeekBacklinks(
    backlinks: List<Backlink>,
    colors: LumenColors,
    onJumpToStep: (Int) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 6.dp)
            .testTag("peek-backlinks"),
    ) {
        Text(
            "backlinks",
            color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(4.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 132.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            backlinks.forEachIndexed { index, link ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(LumenShapes.inset)
                        .clickable { onJumpToStep(link.stepIndex) }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("backlink-$index"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        link.label.lowercase().ifBlank { "step" },
                        color = if (link.touched) colors.water else colors.dim,
                        fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        link.summary,
                        color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * The lightweight in-chat settings surface: provider, model, theme, budget and the
 * ask-before-tools switch, plus a footer that reaches the key screen and the full
 * Settings page. Rendered over the transcript like [FilePeekOverlay] — a `surface`
 * sheet with an [LumenColors.outline] hairline — so tuning a chat never yanks the
 * reader out to the full Settings screen.
 */
@Composable
private fun QuickSettingsSheet(
    colors: LumenColors,
    provider: String,
    model: String,
    theme: String,
    askBeforeTools: Boolean,
    budgetUsd: Double,
    onProvider: ((String) -> Unit)?,
    onModel: ((String) -> Unit)?,
    onTheme: ((String) -> Unit)?,
    onMaxCost: ((Double) -> Unit)?,
    onAskBeforeTools: ((Boolean) -> Unit)?,
    onEditKey: (() -> Unit)?,
    onOpenFullSettings: (() -> Unit)?,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = if (colors.dark) 0.62f else 0.38f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClose,
            )
            .testTag("quick-settings"),
    ) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .shadow(LumenElevation.floating, LumenShapes.sheet)
                .clip(LumenShapes.sheet)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.sheet)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) {}
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 18.dp),
        ) {
            Text(
                "quick settings",
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.heading,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.4.sp,
            )
            Spacer(Modifier.height(16.dp))

            QuickLabel("provider", colors)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for ((id, label) in ProviderCatalogue.choices) {
                    QuickChip(label, provider == id, "quick-provider-$id", colors) { onProvider?.invoke(id) }
                }
            }

            Spacer(Modifier.height(18.dp))
            QuickLabel("model", colors)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in ProviderCatalogue.defaultModels(provider)) {
                    val ref = "$provider/${m.id}"
                    val selected = model == ref
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(LumenShapes.row)
                            .background(if (selected) colors.bg.copy(alpha = 0.35f) else Color.Transparent)
                            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.row)
                            .clickable { onModel?.invoke(ref) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("quick-model-${m.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(7.dp)
                                .background(
                                    if (selected) colors.water else Color.Transparent,
                                    WaterShapes.droplet(tail = 0.5f),
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            m.label ?: m.id,
                            color = if (selected) colors.fg else colors.dim,
                            fontFamily = Mono,
                            fontSize = LumenType.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${m.contextWindow / 1000}k",
                            color = colors.faint,
                            fontFamily = Mono,
                            fontSize = LumenType.micro,
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            QuickLabel("theme", colors)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (value in listOf("system", "light", "dark")) {
                    QuickChip(value, theme == value, "quick-theme-$value", colors) { onTheme?.invoke(value) }
                }
            }

            Spacer(Modifier.height(18.dp))
            QuickLabel("budget", colors)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("off", budgetUsd <= 0.0, "quick-budget-off", colors) { onMaxCost?.invoke(0.0) }
                QuickChip("\$0.50", budgetUsd == 0.5, "quick-budget-050", colors) { onMaxCost?.invoke(0.5) }
                QuickChip("\$2", budgetUsd == 2.0, "quick-budget-2", colors) { onMaxCost?.invoke(2.0) }
                QuickChip("\$5", budgetUsd == 5.0, "quick-budget-5", colors) { onMaxCost?.invoke(5.0) }
            }

            Spacer(Modifier.height(18.dp))
            QuickLabel("tools", colors)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(LumenShapes.row)
                    .background(colors.surface.copy(alpha = 0.25f))
                    .border(1.dp, colors.outline, LumenShapes.row)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "ask before tools",
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                )
                Switch(
                    checked = askBeforeTools,
                    onCheckedChange = { onAskBeforeTools?.invoke(it) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.bg,
                        checkedTrackColor = colors.water,
                        uncheckedThumbColor = colors.dim,
                        uncheckedTrackColor = colors.surface,
                        uncheckedBorderColor = colors.outline,
                    ),
                    modifier = Modifier.testTag("quick-ask-before-tools"),
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                QuickAction("API key", "quick-edit-key", colors) { onEditKey?.invoke() }
                QuickAction("All settings", "quick-full-settings", colors) { onOpenFullSettings?.invoke() }
                Spacer(Modifier.weight(1f))
                QuickAction("close", "quick-close", colors) { onClose() }
            }
        }
    }
}

/** A faint, spaced caption that heads a quick-settings section. */
@Composable
private fun QuickLabel(text: String, colors: LumenColors) {
    Text(
        text,
        color = colors.faint,
        fontFamily = Mono,
        fontSize = LumenType.caption,
        letterSpacing = 2.sp,
    )
    Spacer(Modifier.height(8.dp))
}

/** A selectable pill chip in the quick-settings sheet. */
@Composable
private fun QuickChip(
    text: String,
    selected: Boolean,
    tag: String,
    colors: LumenColors,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (selected) colors.fg else colors.dim,
        fontFamily = Mono,
        fontSize = LumenType.body,
        maxLines = 1,
        modifier = Modifier
            .clip(LumenShapes.inset)
            .background(if (selected) colors.bg.copy(alpha = 0.35f) else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.inset)
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 8.dp)
            .testTag(tag),
    )
}

/** A quiet text action in the quick-settings footer. */
@Composable
private fun QuickAction(
    label: String,
    tag: String,
    colors: LumenColors,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = colors.accent,
        fontFamily = Mono,
        fontSize = LumenType.body,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(LumenShapes.small)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
    )
}
