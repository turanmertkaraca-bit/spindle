package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
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
    val output = remember(lines) { lines.joinToString("") }
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
            TerminalHeader(colors, running, onBack, onClear)

            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .verticalScroll(scroll)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    output.ifEmpty { "…" },
                    color = colors.fg, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth().testTag("terminal-output"),
                )
            }

            if (error != null) {
                Text(
                    error, color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp)
                        .testTag("terminal-error"),
                )
            }

            InputRow(colors, running, onSend, onInterrupt, onRestart)
        }
    }
}

@Composable
private fun TerminalHeader(
    colors: LumenColors,
    running: Boolean,
    onBack: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 10.dp, end = 14.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "‹", color = colors.dim, fontFamily = Mono, fontSize = 20.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onBack() }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag("terminal-back"),
        )
        Spacer(Modifier.width(6.dp))
        Text("terminal", color = colors.fg, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(7.dp)
                .clip(WaterShapes.droplet(tail = 0.5f))
                .background(if (running) colors.water else colors.faint),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "clear", color = colors.accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onClear() }
                .padding(horizontal = 7.dp, vertical = 5.dp)
                .testTag("terminal-clear"),
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
    var draft by remember { mutableStateOf("") }
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
        Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            if (draft.isEmpty()) {
                Text(
                    if (running) "type a command" else "shell exited — restart",
                    color = colors.faint, fontFamily = Mono, fontSize = 13.sp,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                enabled = true,
                textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 13.sp),
                cursorBrush = SolidColor(colors.water),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.fillMaxWidth().testTag("terminal-input"),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "stop",
            color = if (running) colors.dim else colors.faint,
            fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = running) { onInterrupt() }
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .testTag("terminal-ctrl-c"),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (canRestart) "restart" else "send",
            color = if (canRestart || canSend) colors.accent else colors.faint,
            fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = canRestart || canSend) { submit() }
                .padding(horizontal = 7.dp, vertical = 6.dp)
                .testTag(if (canRestart) "terminal-restart" else "terminal-send"),
        )
    }
}
