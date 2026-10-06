package dev.lumen.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.SessionRow
import dev.spindle.core.store.SearchHit

private val Mono = FontFamily.Monospace

// --- Shared screen chrome ----------------------------------------------------
//
// One top-bar language for every non-chat screen: an optional back affordance
// with a real 44dp target, a truncating title, and a grouped row of actions.
// Primary actions fill with the spectral `water`; secondary ones stay quiet.
// Kept in this file (same package) so Home, Files, Terminal, Canvas, Settings,
// Storage and Diagnostics all read identically.

/** A single item in a [LumenMenuButton]. */
internal data class TopMenuAction(val label: String, val tag: String, val onClick: () -> Unit)

/**
 * A quiet, square back affordance. Sized for touch rather than decoration so it
 * never reads as the two-character `‹` it used to be.
 */
@Composable
internal fun LumenBackButton(colors: LumenColors, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(LumenShapes.control)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.control)
            .clickable { onClick() }
            .semantics { contentDescription = "back" }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text("\u2039", color = colors.dim, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Light)
    }
}

/**
 * A top-bar action. [primary] fills with `water` and reads as the screen's main
 * verb; otherwise it is a hairline surface pill. Both carry a >=40dp target.
 */
@Composable
internal fun LumenBarAction(
    colors: LumenColors,
    label: String,
    tag: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val shape = LumenShapes.pill
    val filled = primary && enabled
    val base = Modifier.heightIn(min = 40.dp).clip(shape)
    val surface = if (filled) {
        base.background(colors.water)
    } else {
        base.background(colors.surface).border(1.dp, colors.outline, shape)
    }
    var m = surface.clickable(enabled = enabled) { onClick() }.padding(horizontal = 14.dp)
    if (contentDescription != null) m = m.semantics { this.contentDescription = contentDescription }
    Box(m.testTag(tag), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = when {
                !enabled -> colors.faint
                filled -> colors.bg
                else -> colors.dim
            },
            fontFamily = Mono, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A top-bar button that opens a small menu of secondary actions, so a busy
 * header can tuck its less-common verbs away without hiding them.
 */
@Composable
internal fun LumenMenuButton(
    colors: LumenColors,
    label: String,
    tag: String,
    items: List<TopMenuAction>,
    primary: Boolean = false,
    contentDescription: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        LumenBarAction(
            colors = colors,
            label = label,
            tag = tag,
            onClick = { open = true },
            primary = primary,
            contentDescription = contentDescription,
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = colors.surface,
        ) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = colors.fg, fontFamily = Mono, fontSize = 13.sp) },
                    onClick = { open = false; item.onClick() },
                    modifier = Modifier.testTag(item.tag),
                )
            }
        }
    }
}

/**
 * The shared screen header. Pass [onBack] for a back affordance and fill
 * [actions] with [LumenBarAction]/[LumenMenuButton]s. The title always claims
 * the free space (and ellipsizes) so controls never crowd it.
 */
@Composable
internal fun LumenTopBar(
    colors: LumenColors,
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backTag: String = "back",
    titleTag: String? = null,
    titleSize: TextUnit = 16.sp,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier.fillMaxWidth().padding(start = 6.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            LumenBackButton(colors, backTag, onBack)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            color = colors.fg, fontFamily = Mono, fontSize = titleSize, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (titleTag != null) Modifier.weight(1f).testTag(titleTag) else Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

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
    /**
     * Active tag filters. A session matches when it carries ANY of these tags
     * (OR), so tapping more chips widens the list rather than narrowing it.
     */
    tagFilter: Set<String> = emptySet(),
    /** Every tag present on a loaded session, for the filter bar. */
    availableTags: List<String> = emptyList(),
    /** Toggle a tag in the active filter. */
    onToggleTagFilter: (String) -> Unit = {},
    /** Clear every active tag filter. */
    onClearTagFilters: () -> Unit = {},
    /** Add a tag to a session. */
    onAddTag: (String, String) -> Unit = { _, _ -> },
    /** Remove a tag from a session. */
    onRemoveTag: (String, String) -> Unit = { _, _ -> },
    /** Active provider id, shown in the home status strip. */
    provider: String = "",
    /** Active model id, shown in the home status strip. */
    model: String = "",
    /** Spend cap in USD; <= 0 means the cap is off. */
    maxCostUsd: Double = 0.0,
    /** A quiet label for the sandbox environment. */
    sandboxLabel: String = "",
    /** Connected GitHub login, when there is one. */
    githubLogin: String = "",
    /** Whether tools ask for confirmation before running. */
    askBeforeTools: Boolean = false,
    /** Toggle the ask-before-tools preference. */
    onAskBeforeTools: (Boolean) -> Unit = {},
    /** Open diagnostics, when the host wires it. */
    onDiagnostics: (() -> Unit)? = null,
    /** Open storage management, when the host wires it. */
    onStorage: (() -> Unit)? = null,
    /** Open GitHub settings, when the host wires it. */
    onGitHub: (() -> Unit)? = null,
) {
    // Destructive delete needs a confirmation; holds the id pending deletion.
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    // A whisper of press feedback on the one primary action; cheap and calm.
    val newChatInteraction = remember { MutableInteractionSource() }
    val newChatPressed by newChatInteraction.collectIsPressedAsState()
    val newChatScale by animateFloatAsState(
        targetValue = if (newChatPressed) 0.98f else 1f,
        animationSpec = LumenMotion.press,
        label = "new-chat-scale",
    )

    // Secondary destinations tuck into one quiet menu so the header stays calm.
    // Files and Terminal are promoted to the quick-controls row; Settings stays
    // here as the one rarer verb.
    val menuItems = listOf(TopMenuAction("Settings", "settings", onSettings))

    Column(
        modifier.fillMaxSize().background(colors.bg).imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 20.dp),
    ) {
        LumenTopBar(
            colors = colors,
            title = "lumen",
            titleSize = 26.sp,
            actions = {
                if (onToggleTheme != null) {
                    LumenBarAction(
                        colors = colors, label = "\u25d0", tag = "theme", onClick = onToggleTheme,
                        contentDescription = "toggle theme",
                    )
                }
                LumenMenuButton(
                    colors = colors, label = "\u22ef", tag = "home-menu", items = menuItems,
                    contentDescription = "more destinations",
                )
            },
        )
        Spacer(Modifier.height(12.dp))
        StatusStrip(
            colors = colors,
            provider = provider,
            model = model,
            maxCostUsd = maxCostUsd,
            sandboxLabel = sandboxLabel,
            githubLogin = githubLogin,
            onSettings = onSettings,
            onDiagnostics = onDiagnostics,
            onGitHub = onGitHub,
        )
        Spacer(Modifier.height(16.dp))

        // The one obvious thing to do on this screen.
        Box(
            Modifier.fillMaxWidth()
                .graphicsLayer { scaleX = newChatScale; scaleY = newChatScale }
                .clip(LumenShapes.pill)
                .background(colors.water)
                .clickable(
                    interactionSource = newChatInteraction,
                    indication = null,
                    onClick = onNewChat,
                )
                .padding(vertical = 14.dp)
                .testTag("new-chat"),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(colors.bg.copy(alpha = 0.85f), WaterShapes.droplet(tail = 0.55f)))
                Spacer(Modifier.width(10.dp))
                Text("New chat", color = colors.bg, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(18.dp))

        Box(
            Modifier.fillMaxWidth()
                .clip(LumenShapes.pill)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.pill)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) {
            if (searchQuery.isEmpty()) {
                Text("Search chats", color = colors.faint, fontFamily = Mono, fontSize = 13.sp)
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

        // Quick controls: the one toggle worth surfacing plus the two cockpits,
        // kept to a single quiet line right under the search.
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("ask before tools", color = colors.dim, fontFamily = Mono, fontSize = 11.sp)
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = askBeforeTools,
                onCheckedChange = onAskBeforeTools,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.bg,
                    checkedTrackColor = colors.water,
                    uncheckedThumbColor = colors.dim,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.outline,
                ),
                modifier = Modifier.testTag("home-ask-before-tools"),
            )
            Spacer(Modifier.weight(1f))
            onFiles?.let { open ->
                RowAction(colors, "files", "files", onClick = open)
            }
            if (onFiles != null && onTerminal != null) Spacer(Modifier.width(6.dp))
            onTerminal?.let { open ->
                RowAction(colors, "terminal", "terminal", onClick = open)
            }
        }

        // Tag filter bar: every known tag is a toggle chip; a selected chip shows
        // an "×" so the active filter is removable. Hidden when there are none.
        val filterTags = (availableTags + tagFilter).distinct().sorted()
        if (filterTags.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("tags", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 1.2.sp)
                filterTags.forEach { tag ->
                    val selected = tag in tagFilter
                    TagChip(
                        colors = colors,
                        text = if (selected) "$tag ×" else tag,
                        selected = selected,
                        tag = "tag-filter-$tag",
                        onClick = { onToggleTagFilter(tag) },
                    )
                }
                if (tagFilter.isNotEmpty()) {
                    Text(
                        "clear",
                        color = colors.accent, fontFamily = Mono, fontSize = 11.sp,
                        modifier = Modifier
                            .clip(LumenShapes.small)
                            .clickable { onClearTagFilters() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("clear-tags"),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${sessions.size} ${if (sessions.size == 1) "chat" else "chats"}",
                color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.6.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (showArchived) "Hide archived" else "Show archived",
                color = if (showArchived) colors.dim else colors.faint.copy(alpha = 0.85f),
                fontFamily = Mono, fontSize = 10.5.sp,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onShowArchived(!showArchived) }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("show-archived"),
            )
        }
        Spacer(Modifier.height(4.dp))

        if (search != null) {
            if (search.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    StateHint(colors, "No matches", detail = "Try a different word", tag = "search-empty")
                }
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("search-results")) {
                    items(search.distinctBy { it.sessionId.value + ":" + it.messageId }, key = { it.sessionId.value + ":" + it.messageId }) { hit ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(LumenShapes.row)
                                .background(colors.surface)
                                .border(1.dp, colors.outline, LumenShapes.row)
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
                StateHint(
                    colors = colors,
                    text = if (tagFilter.isNotEmpty()) "No chats with these tags" else "No chats yet",
                    detail = if (tagFilter.isNotEmpty()) "Clear the filters to see everything" else "Start a new chat to begin",
                    tag = "home-empty",
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("sessions")) {
                items(sessions.distinctBy { it.id }, key = { it.id }) { s ->
                    var expanded by remember { mutableStateOf(false) }
                    var renaming by remember { mutableStateOf(false) }
                    var tagging by remember { mutableStateOf(false) }
                    var draft by remember(s.title) { mutableStateOf(s.title) }
                    var tagDraft by remember { mutableStateOf("") }
                    Column(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .clip(LumenShapes.row)
                                .background(colors.surface)
                                .border(1.dp, colors.outline, LumenShapes.row)
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
                                if (s.tags.isNotEmpty()) {
                                    Spacer(Modifier.height(5.dp))
                                    Row(
                                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        s.tags.forEach { tag ->
                                            TagChip(
                                                colors = colors,
                                                text = tag,
                                                selected = tag in tagFilter,
                                                tag = "session-tag-${s.id}-$tag",
                                                onClick = { onToggleTagFilter(tag) },
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(ago(s.updatedAt), color = colors.faint, fontFamily = Mono, fontSize = 11.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "⋯",
                                color = colors.dim, fontFamily = Mono, fontSize = 15.sp,
                                modifier = Modifier
                                    .clip(LumenShapes.small)
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
                                            .clip(LumenShapes.inset)
                                            .background(colors.surface)
                                            .border(1.dp, colors.outline, LumenShapes.inset)
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
                            } else if (tagging) {
                                Column(
                                    Modifier.fillMaxWidth()
                                        .padding(start = 32.dp, end = 12.dp, bottom = 8.dp)
                                        .testTag("tag-editor-${s.id}"),
                                ) {
                                    if (s.tags.isNotEmpty()) {
                                        Row(
                                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            s.tags.forEach { tag ->
                                                RowAction(colors, "$tag ×", "remove-tag-${s.id}-$tag") {
                                                    onRemoveTag(s.id, tag)
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.weight(1f)
                                                .clip(LumenShapes.inset)
                                                .background(colors.surface)
                                                .border(1.dp, colors.outline, LumenShapes.inset)
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                        ) {
                                            if (tagDraft.isEmpty()) {
                                                Text("Add a tag", color = colors.faint, fontFamily = Mono, fontSize = 13.sp)
                                            }
                                            BasicTextField(
                                                value = tagDraft,
                                                onValueChange = { tagDraft = it },
                                                singleLine = true,
                                                textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 13.sp),
                                                cursorBrush = SolidColor(colors.water),
                                                modifier = Modifier.fillMaxWidth().testTag("tag-field-${s.id}"),
                                            )
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        RowAction(colors, "add", "tag-save-${s.id}") {
                                            onAddTag(s.id, tagDraft)
                                            tagDraft = ""
                                        }
                                        Spacer(Modifier.width(6.dp))
                                        RowAction(colors, "done", "tag-done-${s.id}") { tagging = false }
                                    }
                                }
                            } else {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .padding(start = 32.dp, end = 12.dp, bottom = 8.dp)
                                        .testTag("actions-${s.id}"),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    RowAction(colors, if (s.pinned) "unpin" else "pin", "pin-${s.id}") { onPin(s.id, !s.pinned) }
                                    RowAction(colors, if (s.archived) "unarchive" else "archive", "archive-${s.id}") { onArchive(s.id, !s.archived) }
                                    RowAction(colors, "rename", "rename-${s.id}") { draft = s.title; renaming = true }
                                    RowAction(colors, "tag", "tag-${s.id}") { tagDraft = ""; tagging = true }
                                    RowAction(colors, "fork", "fork-${s.id}") { onFork(s.id) }
                                    RowAction(colors, "delete", "delete-${s.id}", destructive = true) { deleteTarget = s.id }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirm before a destructive delete; dismissing leaves the session intact.
    deleteTarget?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            containerColor = colors.surface,
            titleContentColor = colors.fg,
            textContentColor = colors.dim,
            title = { Text("Delete chat?", fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium) },
            text = {
                Text(
                    "\"${sessions.firstOrNull { it.id == id }?.title ?: "this chat"}\" and all of its messages will be removed. This cannot be undone.",
                    fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                Text(
                    "Delete", color = LumenAlert, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .clickable {
                            onDelete(id)
                            deleteTarget = null
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("delete-confirm"),
                )
            },
            dismissButton = {
                Text(
                    "Cancel", color = colors.dim, fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .clickable { deleteTarget = null }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("delete-cancel"),
                )
            },
        )
    }
}

/**
 * A quiet action used in an expanded session row. Borderless so a row of six
 * reads as a calm strip of words rather than a wall of boxes.
 */
@Composable
private fun RowAction(
    colors: LumenColors,
    label: String,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (destructive) LumenAlert else colors.dim,
        fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(LumenShapes.small)
            .background(colors.surface)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp)
            .testTag(tag),
    )
}

/** A small tag pill. Tapping it toggles the tag as a Home filter. */
@Composable
private fun TagChip(
    colors: LumenColors,
    text: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (selected) colors.fg else colors.dim,
        fontFamily = Mono, fontSize = 10.sp,
        maxLines = 1,
        modifier = Modifier
            .clip(LumenShapes.small)
            .background(if (selected) colors.water.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) colors.water.copy(alpha = 0.45f) else colors.outline,
                LumenShapes.small,
            )
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(tag),
    )
}

/**
 * A compact, horizontally-scrollable strip of quiet status pills so the home
 * screen reports the current provider/model, sandbox, budget and GitHub state
 * without becoming a second Settings page.
 */
@Composable
private fun StatusStrip(
    colors: LumenColors,
    provider: String,
    model: String,
    maxCostUsd: Double,
    sandboxLabel: String,
    githubLogin: String,
    onSettings: () -> Unit,
    onDiagnostics: (() -> Unit)?,
    onGitHub: (() -> Unit)?,
) {
    val modelLabel = listOf(provider, model).filter { it.isNotBlank() }
        .joinToString(" \u00b7 ")
        .ifBlank { "no model" }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(colors, modelLabel, "status-provider", onSettings)
        StatusPill(colors, sandboxLabel.ifBlank { "sandbox" }, "status-sandbox", onDiagnostics)
        StatusPill(colors, budgetLabel(maxCostUsd), "status-budget", onSettings)
        StatusPill(
            colors,
            if (githubLogin.isNotBlank()) "@$githubLogin" else "connect github",
            "status-github",
            onGitHub,
        )
    }
}

/** A tiny status pill. Tapping it is a no-op when [onClick] is null. */
@Composable
private fun StatusPill(
    colors: LumenColors,
    text: String,
    tag: String,
    onClick: (() -> Unit)?,
) {
    val shell = Modifier
        .clip(LumenShapes.pill)
        .background(colors.surface)
        .border(1.dp, colors.outline, LumenShapes.pill)
    val interactive = if (onClick != null) shell.clickable { onClick() } else shell
    Text(
        text,
        color = colors.dim, fontFamily = Mono, fontSize = 10.5.sp,
        maxLines = 1,
        modifier = interactive.padding(horizontal = 9.dp, vertical = 5.dp).testTag(tag),
    )
}

/** "budget off" when the cap is disabled, otherwise a compact "$N cap". */
private fun budgetLabel(maxCostUsd: Double): String {
    if (maxCostUsd <= 0.0) return "budget off"
    val amount = if (maxCostUsd == maxCostUsd.toLong().toDouble()) {
        maxCostUsd.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.2f", maxCostUsd)
    }
    return "\$$amount cap"
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
