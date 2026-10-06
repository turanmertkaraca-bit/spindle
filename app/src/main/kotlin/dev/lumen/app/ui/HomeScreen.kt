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
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.graphics.Brush
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
            .size(LumenSize.touchComfort)
            .clip(LumenShapes.control)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.control)
            .clickable { onClick() }
            .semantics { contentDescription = "back" }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "\u2039",
            color = colors.dim,
            fontFamily = Mono,
            fontSize = 24.sp,
            fontWeight = FontWeight.Light,
        )
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
    val base = Modifier.heightIn(min = LumenSize.touchMin).clip(shape)
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
            fontFamily = Mono,
            fontSize = LumenType.caption,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
                    text = {
                        Text(
                            item.label,
                            color = colors.fg,
                            fontFamily = Mono,
                            fontSize = LumenType.body,
                        )
                    },
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
    titleSize: TextUnit = LumenType.heading,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            LumenBackButton(colors, backTag, onBack)
            Spacer(Modifier.width(4.dp))
        }
        Text(
            title,
            color = colors.fg,
            fontFamily = Mono,
            fontSize = titleSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    val menuItems = listOf(TopMenuAction("Settings", "settings", onSettings))

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 8.dp, bottom = 20.dp),
    ) {
        LumenTopBar(
            colors = colors,
            title = "lumen",
            titleSize = LumenType.hero,
            actions = {
                if (onToggleTheme != null) {
                    LumenBarAction(
                        colors = colors,
                        label = "\u25d0",
                        tag = "theme",
                        onClick = onToggleTheme,
                        contentDescription = "toggle theme",
                    )
                }
                LumenMenuButton(
                    colors = colors,
                    label = "\u22ef",
                    tag = "home-menu",
                    items = menuItems,
                    contentDescription = "more destinations",
                )
            },
        )

        Spacer(Modifier.height(LumenSpacing.md))
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
        Spacer(Modifier.height(LumenSpacing.lg))

        // The one obvious thing to do on this screen.
        val waterGradient = remember(colors.spectrum) {
            Brush.horizontalGradient(
                colors.spectrum.takeIf { it.size >= 2 } ?: listOf(colors.water, colors.accent),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = newChatScale; scaleY = newChatScale }
                .clip(LumenShapes.pill)
                .background(waterGradient)
                .clickable(
                    interactionSource = newChatInteraction,
                    indication = null,
                    onClick = onNewChat,
                )
                .padding(vertical = 15.dp)
                .testTag("new-chat"),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(colors.bg.copy(alpha = 0.9f), WaterShapes.droplet(tail = 0.55f)),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "New chat",
                    color = colors.bg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(LumenSpacing.lg))

        SearchField(
            colors = colors,
            query = searchQuery,
            onQuery = onSearch,
        )

        // Quick controls: the one toggle worth surfacing plus the two cockpits,
        // kept to a single quiet line right under the search.
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ask before tools",
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.caption,
            )
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
                CockpitPill(colors, "files", "files", onClick = open)
            }
            if (onFiles != null && onTerminal != null) Spacer(Modifier.width(8.dp))
            onTerminal?.let { open ->
                CockpitPill(colors, "terminal", "terminal", onClick = open)
            }
        }

        // Tag filter bar: every known tag is a toggle chip; a selected chip shows
        // an "×" so the active filter is removable. Hidden when there are none.
        val filterTags = (availableTags + tagFilter).distinct().sorted()
        if (filterTags.isNotEmpty()) {
            Spacer(Modifier.height(LumenSpacing.md))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "tags",
                    color = colors.faint,
                    fontFamily = Mono,
                    fontSize = LumenType.micro,
                    letterSpacing = 1.2.sp,
                )
                filterTags.forEach { tag ->
                    val selected = tag in tagFilter
                    TagChip(
                        colors = colors,
                        text = if (selected) "$tag \u00d7" else tag,
                        selected = selected,
                        tag = "tag-filter-$tag",
                        onClick = { onToggleTagFilter(tag) },
                    )
                }
                if (tagFilter.isNotEmpty()) {
                    Text(
                        "clear",
                        color = colors.accent,
                        fontFamily = Mono,
                        fontSize = LumenType.micro,
                        modifier = Modifier
                            .clip(LumenShapes.small)
                            .clickable { onClearTagFilters() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .testTag("clear-tags"),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${sessions.size} ${if (sessions.size == 1) "chat" else "chats"}",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.6.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (showArchived) "Hide archived" else "Show archived",
                color = if (showArchived) colors.dim else colors.faint.copy(alpha = 0.85f),
                fontFamily = Mono,
                fontSize = LumenType.micro,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onShowArchived(!showArchived) }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                    .testTag("show-archived"),
            )
        }
        Spacer(Modifier.height(6.dp))

        if (search != null) {
            if (search.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    StateHint(
                        colors = colors,
                        text = "No matches",
                        detail = "Try a different word",
                        tag = "search-empty",
                    )
                }
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("search-results"),
                    contentPadding = PaddingValues(vertical = 2.dp),
                ) {
                    items(
                        search.distinctBy { it.sessionId.value + ":" + it.messageId },
                        key = { it.sessionId.value + ":" + it.messageId },
                    ) { hit ->
                        SearchHitRow(colors, hit, onOpen)
                    }
                }
            }
        } else if (sessions.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StateHint(
                    colors = colors,
                    text = if (tagFilter.isNotEmpty()) "No chats with these tags" else "No chats yet",
                    detail = if (tagFilter.isNotEmpty()) "Clear the filters to see everything" else "Start a new chat to begin",
                    tag = "home-empty",
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("sessions"),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(sessions.distinctBy { it.id }, key = { it.id }) { s ->
                    SessionCard(
                        colors = colors,
                        session = s,
                        tagFilter = tagFilter,
                        onOpen = onOpen,
                        onToggleTagFilter = onToggleTagFilter,
                        onPin = onPin,
                        onArchive = onArchive,
                        onFork = onFork,
                        onRename = onRename,
                        onAddTag = onAddTag,
                        onRemoveTag = onRemoveTag,
                        onDelete = { deleteTarget = it },
                    )
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
            title = { Text("Delete chat?", fontFamily = Mono, fontSize = LumenType.heading, fontWeight = FontWeight.Medium) },
            text = {
                Text(
                    "\"${sessions.firstOrNull { it.id == id }?.title ?: "this chat"}\" and all of its messages will be removed. This cannot be undone.",
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                    lineHeight = LumenType.lineTight,
                )
            },
            confirmButton = {
                Text(
                    "Delete",
                    color = LumenAlert,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                    fontWeight = FontWeight.Medium,
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
                    "Cancel",
                    color = colors.dim,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
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

@Composable
private fun SearchField(
    colors: LumenColors,
    query: String,
    onQuery: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.pill)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "\u2315",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = 14.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Search chats",
                        color = colors.faint,
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = colors.fg,
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                    ),
                    cursorBrush = SolidColor(colors.water),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().testTag("search"),
                )
            }
        }
    }
}

@Composable
private fun SearchHitRow(
    colors: LumenColors,
    hit: SearchHit,
    onOpen: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(LumenShapes.row)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.row)
            .clickable { onOpen(hit.sessionId.value) }
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("search-hit-${hit.messageId}"),
    ) {
        Text(
            hit.role.lowercase(),
            color = colors.faint,
            fontFamily = Mono,
            fontSize = LumenType.micro,
            letterSpacing = 1.2.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            hit.snippet,
            color = colors.fg,
            fontFamily = Mono,
            fontSize = LumenType.body,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionCard(
    colors: LumenColors,
    session: SessionRow,
    tagFilter: Set<String>,
    onOpen: (String) -> Unit,
    onToggleTagFilter: (String) -> Unit,
    onPin: (String, Boolean) -> Unit,
    onArchive: (String, Boolean) -> Unit,
    onFork: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onAddTag: (String, String) -> Unit,
    onRemoveTag: (String, String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var tagging by remember { mutableStateOf(false) }
    var draft by remember(session.title) { mutableStateOf(session.title) }
    var tagDraft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(LumenShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.card)
                .clickable { onOpen(session.id) }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(colors.water, WaterShapes.droplet(tail = 0.55f)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (session.pinned) "\u2605 ${session.title}" else session.title,
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.preview.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        session.preview,
                        color = colors.dim,
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (session.tags.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        session.tags.forEach { tag ->
                            TagChip(
                                colors = colors,
                                text = tag,
                                selected = tag in tagFilter,
                                tag = "session-tag-${session.id}-$tag",
                                onClick = { onToggleTagFilter(tag) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Text(
                ago(session.updatedAt),
                color = colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.micro,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
                    .clip(LumenShapes.small)
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 6.dp)
                    .testTag("more-${session.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\u22ef",
                    color = colors.dim,
                    fontFamily = Mono,
                    fontSize = 16.sp,
                )
            }
        }
        if (expanded) {
            if (renaming) {
                RenameEditor(
                    colors = colors,
                    draft = draft,
                    onDraft = { draft = it },
                    onSave = {
                        onRename(session.id, draft)
                        renaming = false
                        expanded = false
                    },
                    tag = session.id,
                )
            } else if (tagging) {
                TagEditor(
                    colors = colors,
                    session = session,
                    tagDraft = tagDraft,
                    onTagDraft = { tagDraft = it },
                    onAddTag = {
                        onAddTag(session.id, tagDraft)
                        tagDraft = ""
                    },
                    onRemoveTag = { onRemoveTag(session.id, it) },
                    onDone = { tagging = false },
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 36.dp, end = 12.dp, bottom = 10.dp)
                        .testTag("actions-${session.id}"),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    RowAction(
                        colors,
                        if (session.pinned) "unpin" else "pin",
                        "pin-${session.id}",
                    ) { onPin(session.id, !session.pinned) }
                    RowAction(
                        colors,
                        if (session.archived) "unarchive" else "archive",
                        "archive-${session.id}",
                    ) { onArchive(session.id, !session.archived) }
                    RowAction(colors, "rename", "rename-${session.id}") {
                        draft = session.title
                        renaming = true
                    }
                    RowAction(colors, "tag", "tag-${session.id}") {
                        tagDraft = ""
                        tagging = true
                    }
                    RowAction(colors, "fork", "fork-${session.id}") { onFork(session.id) }
                    RowAction(
                        colors,
                        "delete",
                        "delete-${session.id}",
                        destructive = true,
                    ) { onDelete(session.id) }
                }
            }
        }
    }
}

@Composable
private fun RenameEditor(
    colors: LumenColors,
    draft: String,
    onDraft: (String) -> Unit,
    onSave: () -> Unit,
    tag: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .weight(1f)
                .clip(LumenShapes.inset)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.inset)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = onDraft,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                ),
                cursorBrush = SolidColor(colors.water),
                modifier = Modifier.fillMaxWidth().testTag("rename-field-$tag"),
            )
        }
        Spacer(Modifier.width(8.dp))
        RowAction(colors, "save", "rename-save-$tag", onClick = onSave)
    }
}

@Composable
private fun TagEditor(
    colors: LumenColors,
    session: SessionRow,
    tagDraft: String,
    onTagDraft: (String) -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: (String) -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 12.dp, bottom = 10.dp)
            .testTag("tag-editor-${session.id}"),
    ) {
        if (session.tags.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                session.tags.forEach { tag ->
                    RowAction(
                        colors,
                        "$tag \u00d7",
                        "remove-tag-${session.id}-$tag",
                    ) { onRemoveTag(tag) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(LumenShapes.inset)
                    .background(colors.surface)
                    .border(1.dp, colors.outline, LumenShapes.inset)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (tagDraft.isEmpty()) {
                    Text(
                        "Add a tag",
                        color = colors.faint,
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                    )
                }
                BasicTextField(
                    value = tagDraft,
                    onValueChange = onTagDraft,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = colors.fg,
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                    ),
                    cursorBrush = SolidColor(colors.water),
                    modifier = Modifier.fillMaxWidth().testTag("tag-field-${session.id}"),
                )
            }
            Spacer(Modifier.width(8.dp))
            RowAction(colors, "add", "tag-save-${session.id}", onClick = onAddTag)
            Spacer(Modifier.width(6.dp))
            RowAction(colors, "done", "tag-done-${session.id}", onClick = onDone)
        }
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
        fontFamily = Mono,
        fontSize = LumenType.caption,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(LumenShapes.small)
            .background(colors.surface)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
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
        fontFamily = Mono,
        fontSize = LumenType.micro,
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
            .padding(horizontal = 7.dp, vertical = 3.dp)
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
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusPill(colors, "\u25c8", modelLabel, "status-provider", onSettings)
        StatusPill(colors, "\u25a0", sandboxLabel.ifBlank { "sandbox" }, "status-sandbox", onDiagnostics)
        StatusPill(colors, "\u0024", budgetLabel(maxCostUsd), "status-budget", onSettings)
        StatusPill(
            colors,
            "\u263a",
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
    glyph: String,
    text: String,
    tag: String,
    onClick: (() -> Unit)?,
) {
    val shell = Modifier
        .clip(LumenShapes.pill)
        .background(colors.surface)
        .border(1.dp, colors.outline, LumenShapes.pill)
    val interactive = if (onClick != null) shell.clickable { onClick() } else shell
    Row(
        modifier = interactive
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            glyph,
            color = colors.water,
            fontFamily = Mono,
            fontSize = 9.sp,
            modifier = Modifier.padding(end = 5.dp),
        )
        Text(
            text,
            color = colors.dim,
            fontFamily = Mono,
            fontSize = LumenType.micro,
            maxLines = 1,
        )
    }
}

@Composable
private fun CockpitPill(
    colors: LumenColors,
    label: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(LumenShapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.pill)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 7.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(6.dp)
                .background(colors.water, WaterShapes.droplet(tail = 0.5f)),
        )
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            color = colors.dim,
            fontFamily = Mono,
            fontSize = LumenType.caption,
            fontWeight = FontWeight.Medium,
        )
    }
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
