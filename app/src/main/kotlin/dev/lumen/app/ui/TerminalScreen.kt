package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace

/**
 * The interactive terminal: a monospace, scrollable transcript with an input
 * row. Output is raw pipe text rather than a real PTY, so the screen is
 * deliberately plain — one joined text block that follows the tail only while
 * the reader is already at the bottom, so scrolling up is never yanked back.
 * Pure hoisted state; the caller wires every action to [dev.lumen.app.ChatViewModel].
 */
@Composable
fun TerminalScreen(
    colors: LumenColors,
    lines: List<String>,
    running: Boolean,
    error: String?,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Restart a shell that has exited; when null the field still stays usable. */
    onRestart: (() -> Unit)? = null,
) {
    val renderer = remember { TerminalRenderState() }
    val output = remember(lines) { renderer.render(lines) }
    val chunks = remember(output) { textChunks(output, TERMINAL_CHUNK_CHARS) }
    val scroll = rememberScrollState()

    // The "reader at bottom" idea, shared with the chat timeline: only follow
    // new output while the user has not scrolled away from the tail.
    val atBottom by remember { derivedStateOf { scroll.value >= scroll.maxValue - 24 } }
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(atBottom) { followTail = atBottom }
    LaunchedEffect(output) {
        if (!followTail) return@LaunchedEffect
        // Let the new text lay out before asking for its new maximum.
        withFrameNanos { }
        scroll.scrollTo(scroll.maxValue)
    }

    Box(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        Column(Modifier.fillMaxSize()) {
            LumenTopBar(
                colors = colors,
                title = "Terminal",
                onBack = onBack,
                backTag = "terminal-back",
                actions = {
                    StatusDot(colors, running)
                    Spacer(Modifier.width(10.dp))
                    LumenBarAction(colors, "Clear", "terminal-clear", onClear)
                },
            )

            if (lines.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    StateHint(
                        colors = colors,
                        text = if (running) "Waiting for output\u2026" else "Shell has exited",
                        detail = if (running) "Anything the command prints shows up here" else "Restart to open a fresh shell",
                        tag = "terminal-empty",
                    )
                }
            } else {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(scroll)
                        .padding(horizontal = LumenSpacing.page, vertical = 8.dp),
                ) {
                    Column(Modifier.fillMaxWidth().testTag("terminal-output")) {
                        for (chunk in chunks) {
                            Text(
                                chunk,
                                color = colors.fg,
                                fontFamily = Mono,
                                fontSize = LumenType.body,
                                lineHeight = LumenType.lineBody,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            if (error != null) {
                Text(
                    error,
                    color = alertOn(colors),
                    fontFamily = Mono,
                    fontSize = LumenType.caption,
                    modifier = Modifier
                        .padding(start = LumenSpacing.page, end = LumenSpacing.page, bottom = 2.dp)
                        .testTag("terminal-error"),
                )
            }

            InputRow(colors, running, onSend, onInterrupt, onRestart)
        }
    }
}

@Composable
private fun StatusDot(colors: LumenColors, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (running) colors.water else colors.dim),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            if (running) "running" else "stopped",
            color = if (running) colors.water else colors.dim,
            fontFamily = Mono,
            fontSize = LumenType.caption,
            letterSpacing = 0.8.sp,
        )
    }
}

@Composable
private fun InputRow(
    colors: LumenColors,
    running: Boolean,
    onSend: (String) -> Unit,
    onInterrupt: () -> Unit,
    onRestart: (() -> Unit)?,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    // The field stays usable after the shell exits, so the user is never locked
    // out; when it is not running the primary action restarts the shell instead.
    val canSend = draft.isNotBlank()
    val canRestart = !running && onRestart != null

    // Sending (or pressing the action) on a dead shell restarts it first, then
    // sends the queued command once it is live again.
    val submit: () -> Unit = {
        if (running) {
            if (draft.isNotBlank()) {
                onSend(draft)
                draft = ""
            }
        } else {
            onRestart?.invoke()
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = LumenSpacing.page - 6.dp, end = LumenSpacing.page - 6.dp, top = 8.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(LumenShapes.panel)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.panel)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (draft.isEmpty()) {
                Text(
                    if (running) "Type a command" else "Shell exited \u2014 restart",
                    color = colors.dim,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                enabled = true,
                textStyle = LocalTextStyle.current.copy(
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                ),
                cursorBrush = SolidColor(colors.water),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag("terminal-input"),
            )
        }
        Spacer(Modifier.width(8.dp))
        LumenBarAction(
            colors = colors,
            label = "Stop",
            tag = "terminal-ctrl-c",
            onClick = onInterrupt,
            enabled = running,
            contentDescription = "stop command",
        )
        Spacer(Modifier.width(4.dp))
        LumenBarAction(
            colors = colors,
            label = if (canRestart) "Restart" else "Send",
            tag = if (canRestart) "terminal-restart" else "terminal-send",
            onClick = submit,
            primary = true,
            enabled = canRestart || canSend,
        )
    }
}

private enum class EscapeState { None, Esc, Csi, Osc, OscEsc, Intermediate }

private const val TERMINAL_MAX_CHARS = 200_000
private const val TERMINAL_CHUNK_CHARS = 4_000
private const val EARLIER_OUTPUT_DROPPED = "\u2026[earlier output dropped]\u2026\n"

private class TerminalRenderState {
    private val text = StringBuilder()
    private val chunks = ArrayList<String>()
    private var cursor = 0
    private var lineStart = 0
    private var dropped = 0L
    private var escape = EscapeState.None

    fun render(source: List<String>): String {
        val offset = overlap(source)
        if (offset == null) {
            reset()
            for (chunk in source) feed(chunk)
            return display()
        }
        var i = chunks.size - offset
        while (i < source.size) {
            feed(source[i])
            i++
        }
        if (offset > 0) {
            chunks.clear()
            chunks.addAll(source)
        }
        return display()
    }

    private fun overlap(source: List<String>): Int? {
        val size = chunks.size
        if (size == 0) return 0
        val first = source.firstOrNull()
        var offset = 0
        while (offset < size) {
            val length = size - offset
            if (length <= source.size && chunks[offset] == first) {
                var i = offset
                while (i < size && chunks[i] == source[i - offset]) i++
                if (i == size) return offset
            }
            offset++
        }
        return null
    }

    private fun reset() {
        text.clear()
        chunks.clear()
        cursor = 0
        lineStart = 0
        dropped = 0L
        escape = EscapeState.None
    }

    private fun feed(chunk: String) {
        var i = 0
        while (i < chunk.length) {
            consume(chunk[i])
            i++
        }
        chunks.add(chunk)
        trim()
    }

    private fun consume(c: Char) {
        when (escape) {
            EscapeState.Csi -> if (c in '\u0040'..'\u007e') escape = EscapeState.None
            EscapeState.Osc -> when (c) {
                '\u0007' -> escape = EscapeState.None
                '\u001b' -> escape = EscapeState.OscEsc
            }
            EscapeState.OscEsc -> escape = when {
                c == '\\' || c == '\u0007' -> EscapeState.None
                c == '\u001b' -> EscapeState.OscEsc
                else -> EscapeState.Osc
            }
            EscapeState.Intermediate -> if (c !in '\u0020'..'\u002f') escape = EscapeState.None
            EscapeState.Esc -> escape = when {
                c == '[' -> EscapeState.Csi
                c == ']' -> EscapeState.Osc
                c in '\u0020'..'\u002f' -> EscapeState.Intermediate
                else -> EscapeState.None
            }
            EscapeState.None -> when {
                c == '\u001b' -> escape = EscapeState.Esc
                c == '\r' -> cursor = lineStart
                c == '\n' -> {
                    text.append('\n')
                    cursor = text.length
                    lineStart = cursor
                }
                c == '\b' -> backspace()
                c == '\t' -> write(c)
                c < ' ' || c == '\u007f' || c in '\u0080'..'\u009f' -> Unit
                else -> write(c)
            }
        }
    }

    private fun write(c: Char) {
        if (cursor < text.length) {
            text.setCharAt(cursor, c)
        } else {
            text.append(c)
        }
        cursor++
    }

    private fun backspace() {
        if (cursor <= lineStart) return
        cursor--
        if (cursor == text.length - 1) text.deleteCharAt(cursor)
    }

    private fun trim() {
        if (text.length <= TERMINAL_MAX_CHARS) return
        var cut = text.length - TERMINAL_MAX_CHARS
        val nextLine = text.indexOf("\n", cut)
        if (nextLine in cut until text.length) cut = nextLine + 1
        text.delete(0, cut)
        dropped += cut.toLong()
        cursor = (cursor - cut).coerceAtLeast(0)
        lineStart = (lineStart - cut).coerceAtLeast(0)
        if (cursor < lineStart) cursor = lineStart
    }

    private fun display(): String = when {
        text.isEmpty() -> ""
        dropped > 0L -> EARLIER_OUTPUT_DROPPED + text
        else -> text.toString()
    }
}
