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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.SessionRow
import dev.spindle.core.store.SearchHit

private val Mono = FontFamily.Monospace

/** The home screen: recent chats, a new chat, and a way into settings. */
@Composable
fun HomeScreen(
    colors: LumenColors,
    sessions: List<SessionRow>,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onToggleTheme: (() -> Unit)? = null,
    /** Open the project files cockpit, when supplied. */
    onFiles: (() -> Unit)? = null,
    /** Open the interactive shell, when supplied. */
    onTerminal: (() -> Unit)? = null,
    /** Full-text hits; non-null while a search is active. */
    search: List<SearchHit>? = null,
    /** Current search text. */
    searchQuery: String = "",
    /** Update the search query (the view model runs the query). */
    onSearch: (String) -> Unit = {},
    /** Fork a session at its head into a new child chat. */
    onFork: (String) -> Unit = {},
    /** Pin/unpin a session; pinned rows float to the top. */
    onPin: (String, Boolean) -> Unit = { _, _ -> },
    /** Archive/unarchive a session; archived rows are hidden unless shown. */
    onArchive: (String, Boolean) -> Unit = { _, _ -> },
    /** Rename a session. */
    onRename: (String, String) -> Unit = { _, _ -> },
    /** Whether archived sessions are currently listed. */
    showArchived: Boolean = false,
    /** Toggle the listing of archived sessions. */
    onShowArchived: (Boolean) -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().background(colors.bg).imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("lumen", color = colors.fg, fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onToggleTheme != null) {
                    Text(
                        "◐",
                        color = colors.faint, fontFamily = Mono, fontSize = 16.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onToggleTheme() }
                            .padding(4.dp)
                            .testTag("theme"),
                    )
                }
                Spacer(Modifier.width(4.dp))
                if (onFiles != null) {
                    Text(
                        "files",
                        color = colors.dim, fontFamily = Mono, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onFiles() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("files"),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                if (onTerminal != null) {
                    Text(
                        ">_",
                        color = colors.dim, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { onTerminal() }
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .testTag("terminal"),
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    "settings",
                    color = colors.dim, fontFamily = Mono, fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSettings() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("settings"),
                )
            }
        }
        Spacer(Modifier.height(24.dp))

        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(colors.water)
                .clickable { onNewChat() }
                .padding(vertical = 14.dp)
                .testTag("new-chat"),
            contentAlignment = Alignment.Center,
        ) {
            Text("new chat", color = colors.bg, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(20.dp))

        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(colors.surface)
                .border(1.dp, colors.rule, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            if (searchQuery.isEmpty()) {
                Text("search chats…", color = colors.faint, fontFamily = Mono, fontSize = 13.sp)
            }
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearch,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 13.sp),
                cursorBrush = SolidColor(colors.water),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().testTag("search"),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                if (showArchived) "hide archived" else "archived",
                color = if (showArchived) colors.accent else colors.faint,
                fontFamily = Mono, fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onShowArchived(!showArchived) }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("show-archived"),
            )
        }
        Spacer(Modifier.height(8.dp))

        if (search != null) {
            if (search.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("no matches", color = colors.faint, fontFamily = Mono, fontSize = 13.sp)
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("search-results")) {
                    items(search, key = { it.sessionId.value + ":" + it.messageId }) { hit ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(hit.sessionId.value) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                                .testTag("search-hit-${hit.messageId}"),
                        ) {
                            Text(
                                hit.role.lowercase(), color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp,
                                letterSpacing = 1.2.sp,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                hit.snippet, color = colors.fg, fontFamily = Mono, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        } else if (sessions.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(10.dp).background(colors.faint, WaterShapes.droplet(tail = 0.55f)))
                    Spacer(Modifier.height(16.dp))
                    Text("no chats yet", color = colors.faint, fontFamily = Mono, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("sessions")) {
                items(sessions, key = { it.id }) { s ->
                    var expanded by remember { mutableStateOf(false) }
                    var renaming by remember { mutableStateOf(false) }
                    var draft by remember(s.title) { mutableStateOf(s.title) }
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpen(s.id) }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(8.dp).background(colors.water, WaterShapes.droplet(tail = 0.55f)))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (s.pinned) "★ ${s.title}" else s.title,
                                    color = colors.fg, fontFamily = Mono, fontSize = 14.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                if (s.preview.isNotEmpty()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(
                                        s.preview, color = colors.dim, fontFamily = Mono, fontSize = 12.sp,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(ago(s.updatedAt), color = colors.faint, fontFamily = Mono, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "⋯",
                                color = colors.dim, fontFamily = Mono, fontSize = 15.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { expanded = !expanded }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                    .testTag("more-${s.id}"),
                            )
                        }
                        if (expanded) {
                            if (renaming) {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = 32.dp, end = 12.dp, bottom = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(
                                        Modifier.weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .border(1.dp, colors.rule, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    ) {
                                        BasicTextField(
                                            value = draft,
                                            onValueChange = { draft = it },
                                            singleLine = true,
                                            textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 13.sp),
                                            cursorBrush = SolidColor(colors.water),
                                            modifier = Modifier.fillMaxWidth().testTag("rename-field-${s.id}"),
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    RowAction(colors, "save", "rename-save-${s.id}") {
                                        onRename(s.id, draft)
                                        renaming = false
                                        expanded = false
                                    }
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(start = 32.dp, end = 12.dp, bottom = 8.dp)
                                        .testTag("actions-${s.id}"),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    RowAction(colors, if (s.pinned) "unpin" else "pin", "pin-${s.id}") { onPin(s.id, !s.pinned) }
                                    RowAction(colors, if (s.archived) "unarchive" else "archive", "archive-${s.id}") { onArchive(s.id, !s.archived) }
                                    RowAction(colors, "rename", "rename-${s.id}") { draft = s.title; renaming = true }
                                    RowAction(colors, "fork", "fork-${s.id}") { onFork(s.id) }
                                    RowAction(colors, "delete", "delete-${s.id}") { onDelete(s.id) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A compact bordered action used in an expanded session row. */
@Composable
private fun RowAction(colors: LumenColors, label: String, tag: String, onClick: () -> Unit) {
    Text(
        label,
        color = colors.dim, fontFamily = Mono, fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .testTag(tag),
    )
}

private fun ago(ts: Long): String {
    val d = System.currentTimeMillis() - ts
    return when {
        d < 60_000 -> "just now"
        d < 3_600_000 -> "${d / 60_000}m"
        d < 86_400_000 -> "${d / 3_600_000}h"
        else -> "${d / 86_400_000}d"
    }
}
