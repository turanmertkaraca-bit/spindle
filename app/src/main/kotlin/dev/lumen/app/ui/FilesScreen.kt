package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.EditorState
import dev.lumen.app.FileEntry
import dev.lumen.app.FilesState
import java.io.File
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

private val Mono = FontFamily.Monospace

/** What the "new" header actions are creating. */
private enum class NewKind { File, Folder }

/**
 * The project file cockpit: a breadcrumbed, searchable browser plus an in-place
 * editor, all scoped to the active project folder. Pure hoisted state — the
 * caller wires every action to [dev.lumen.app.ChatViewModel].
 *
 * Every horizontal edge uses [LumenSpacing.page] (the top bar's own 4dp inset is
 * compensated), so the header, breadcrumb, rows and editor body line up instead
 * of stepping in by a different amount per section.
 */
@Composable
fun FilesScreen(
    colors: LumenColors,
    files: FilesState?,
    editor: EditorState?,
    onOpenDir: (String) -> Unit,
    onUp: () -> Unit,
    onEnter: (FileEntry) -> Unit,
    onSaveFile: (String, String) -> Unit,
    onCloseEditor: () -> Unit,
    onCreateFile: (String, String) -> Unit,
    onCreateDir: (String, String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String, Boolean) -> Unit,
    onBack: () -> Unit,
    /** Open an `.html`/`.htm` file in the sandboxed canvas. */
    onOpenCanvas: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    onDraftChange: (String) -> Unit = {},
    /** Shown as the breadcrumb root so the user knows which project they see. */
    projectName: String = "project",
    /** Absolute project root; used only to locate the truncated-output spill dir. */
    workspacePath: String = "",
    /** Hand an image (or other opaque file) to an external viewer. */
    onOpenExternally: (String) -> Unit = {},
    /** Re-list the current folder on demand. */
    onRefresh: (() -> Unit)? = null,
) {
    val dir = files?.dir.orEmpty()
    val spillRoot = remember(workspacePath) {
        if (workspacePath.isBlank()) null else File(workspacePath, ".spindle/truncated")
    }

    var newKind by remember { mutableStateOf<NewKind?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }
    var query by remember(files?.dir) { mutableStateOf("") }

    // The editor is the detail half when a file is open, or null when none is.
    val openEditor = editor
    val detailSlot: (@Composable () -> Unit)? = if (openEditor != null) {
        {
            EditorOverlay(
                colors = colors,
                editor = openEditor,
                onCancel = onCloseEditor,
                onSave = { content, hadContents ->
                    if (hadContents) {
                        pendingSave = openEditor.path to content
                    } else {
                        onSaveFile(openEditor.path, content)
                    }
                },
                onDraftChange = onDraftChange,
                onOpenCanvas = onOpenCanvas,
            )
        }
    } else {
        null
    }

    Box(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        AdaptiveTwoPane(
            list = {
                Column(Modifier.fillMaxSize()) {
                    LumenTopBar(
                        colors = colors,
                        title = "Files",
                        onBack = onBack,
                        backTag = "files-back",
                        modifier = Modifier.padding(horizontal = LumenSpacing.page - 4.dp),
                        actions = {
                            if (onRefresh != null) {
                                LumenBarAction(
                                    colors = colors,
                                    label = "\u21bb",
                                    tag = "files-refresh",
                                    onClick = onRefresh,
                                    contentDescription = "refresh",
                                )
                            }
                            if (dir.isNotEmpty()) {
                                LumenBarAction(colors, "Up", "files-up", onUp)
                            }
                            LumenMenuButton(
                                colors = colors,
                                label = "New",
                                tag = "files-new",
                                primary = true,
                                contentDescription = "create new",
                                items = listOf(
                                    TopMenuAction("New file", "new-file") { newKind = NewKind.File },
                                    TopMenuAction("New folder", "new-folder") { newKind = NewKind.Folder },
                                ),
                            )
                        },
                    )

                    Breadcrumb(colors, projectName, dir, onOpenDir)

                    val listing = files
                    if (listing == null) {
                        CenteredHint(colors, "Loading files\u2026")
                    } else {
                        val shown = remember(listing.entries, query) {
                            val needle = query.trim()
                            if (needle.isEmpty()) {
                                listing.entries
                            } else {
                                listing.entries.filter { it.name.contains(needle, ignoreCase = true) }
                            }
                        }
                        val folders = shown.count { it.isDir }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = LumenSpacing.page, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (shown.isEmpty()) "empty" else "${shown.size} ${if (shown.size == 1) "item" else "items"} \u00b7 $folders ${if (folders == 1) "folder" else "folders"}",
                                color = colors.dim,
                                fontFamily = Mono,
                                fontSize = LumenType.micro,
                                letterSpacing = 0.4.sp,
                                modifier = Modifier.weight(1f).testTag("files-count"),
                            )
                        }
                        SearchField(
                            colors = colors,
                            query = query,
                            onQuery = { query = it },
                        )

                        val listingError = listing.error
                        if (listingError != null && listing.entries.isEmpty()) {
                            CenteredHint(colors, listingError, error = true)
                        } else if (shown.isEmpty()) {
                            CenteredHint(
                                colors = colors,
                                text = if (query.isBlank()) "This folder is empty" else "No matches",
                                detail = if (query.isBlank()) "Use New to add a file or folder" else "Nothing here matches \u201c$query\u201d",
                                tag = if (query.isBlank()) "files-empty" else "files-no-matches",
                            )
                        } else {
                            val spillCount = remember(listing.dir, listing.entries, spillRoot) {
                                if (listing.dir.isEmpty() && spillRoot != null) {
                                    runCatching { spillRoot.listFiles()?.count { it.isFile } ?: 0 }
                                        .getOrDefault(0)
                                } else {
                                    0
                                }
                            }
                            LazyColumn(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .testTag("files-list"),
                                contentPadding = PaddingValues(
                                    start = LumenSpacing.page,
                                    end = LumenSpacing.page,
                                    top = 4.dp,
                                    bottom = 14.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                if (listingError != null) {
                                    item(key = "__error") {
                                        Text(
                                            listingError,
                                            color = alertOn(colors),
                                            fontFamily = Mono,
                                            fontSize = LumenType.caption,
                                            modifier = Modifier
                                                .padding(horizontal = 2.dp, vertical = 4.dp)
                                                .testTag("files-error"),
                                        )
                                    }
                                }
                                if (listing.truncated) {
                                    item(key = "__truncated") {
                                        Text(
                                            "\u2026 showing the first ${listing.entries.size} entries \u2014 project has more",
                                            color = colors.dim,
                                            fontFamily = Mono,
                                            fontSize = LumenType.caption,
                                            modifier = Modifier
                                                .padding(horizontal = 2.dp, vertical = 4.dp)
                                                .testTag("files-truncated"),
                                        )
                                    }
                                }
                                if (spillCount > 0 && query.isBlank()) {
                                    item(key = "__spill") {
                                        Text(
                                            "\u2026 $spillCount saved output ${if (spillCount == 1) "file" else "files"} \u00b7 .spindle/truncated",
                                            color = colors.dim,
                                            fontFamily = Mono,
                                            fontSize = LumenType.caption,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 44.dp)
                                                .clip(LumenShapes.row)
                                                .background(colors.surface)
                                                .border(1.dp, colors.outline, LumenShapes.row)
                                                .clickable { onOpenDir(".spindle/truncated") }
                                                .padding(horizontal = 14.dp, vertical = 12.dp)
                                                .testTag("files-spill"),
                                        )
                                    }
                                }
                                items(shown, key = { it.path }) { entry ->
                                    EntryRow(
                                        colors = colors,
                                        entry = entry,
                                        onEnter = onEnter,
                                        onRename = { renameTarget = it },
                                        onDelete = { deleteTarget = it },
                                        onOpenCanvas = onOpenCanvas,
                                        onOpenExternally = onOpenExternally,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            detail = detailSlot,
            focusDetailOnNarrow = true,
        )
    }

    newKind?.let { kind ->
        NameDialog(
            colors = colors,
            title = if (kind == NewKind.File) "New file" else "New folder",
            confirm = "Create",
            onDismiss = { newKind = null },
            onConfirm = { name ->
                if (kind == NewKind.File) onCreateFile(dir, name) else onCreateDir(dir, name)
                newKind = null
            },
        )
    }

    renameTarget?.let { entry ->
        NameDialog(
            colors = colors,
            title = "Rename",
            confirm = "Rename",
            initial = entry.name,
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRename(entry.path, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { entry ->
        ConfirmDialog(
            colors = colors,
            title = if (entry.isDir) "Delete folder?" else "Delete file?",
            message = if (entry.isDir) {
                "\"${entry.name}\" and everything inside it will be removed. This cannot be undone."
            } else {
                "\"${entry.name}\" will be removed. This cannot be undone."
            },
            confirm = "Delete",
            destructive = true,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                onDelete(entry.path, entry.isDir)
                deleteTarget = null
            },
        )
    }

    pendingSave?.let { (path, content) ->
        ConfirmDialog(
            colors = colors,
            title = "Overwrite file?",
            message = "\"${path.substringAfterLast('/')}\" already has contents. Saving replaces them.",
            confirm = "Overwrite",
            onDismiss = { pendingSave = null },
            onConfirm = {
                onSaveFile(path, content)
                pendingSave = null
            },
        )
    }
}

@Composable
private fun Breadcrumb(
    colors: LumenColors,
    projectName: String,
    dir: String,
    onOpenDir: (String) -> Unit,
) {
    val segments = dir.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LumenSpacing.page, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            projectName.ifBlank { "project" },
            color = if (segments.isEmpty()) colors.fg else colors.water,
            fontFamily = Mono,
            fontSize = LumenType.body,
            modifier = Modifier
                .heightIn(min = 40.dp)
                .clip(LumenShapes.glyph)
                .clickable { onOpenDir("") }
                .padding(horizontal = 5.dp, vertical = 4.dp)
                .testTag("crumb-root"),
        )
        var prefix = ""
        for (seg in segments) {
            prefix = if (prefix.isEmpty()) seg else "$prefix/$seg"
            val target = prefix
            Text(
                "/",
                color = colors.outline,
                fontFamily = Mono,
                fontSize = LumenType.body,
                modifier = Modifier.padding(horizontal = 1.dp),
            )
            Text(
                seg,
                color = colors.water,
                fontFamily = Mono,
                fontSize = LumenType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .heightIn(min = 40.dp)
                    .clip(LumenShapes.glyph)
                    .clickable { onOpenDir(target) }
                    .padding(horizontal = 5.dp, vertical = 4.dp)
                    .testTag("crumb-$target"),
            )
        }
    }
}

@Composable
private fun SearchField(colors: LumenColors, query: String, onQuery: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = LumenSpacing.page, vertical = 4.dp)
            .clip(LumenShapes.inset)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.inset)
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
            .heightIn(min = 42.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("\u2315", color = colors.faint, fontFamily = Mono, fontSize = 13.sp)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    "filter this folder",
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 9.dp)
                    .testTag("files-search"),
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier
                    .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
                    .clip(LumenShapes.small)
                    .clickable { onQuery("") }
                    .testTag("files-search-clear"),
                contentAlignment = Alignment.Center,
            ) {
                Text("\u00d7", color = colors.dim, fontFamily = Mono, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun EntryRow(
    colors: LumenColors,
    entry: FileEntry,
    onEnter: (FileEntry) -> Unit,
    onRename: (FileEntry) -> Unit,
    onDelete: (FileEntry) -> Unit,
    onOpenCanvas: (String) -> Unit = {},
    onOpenExternally: (String) -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    val tap: () -> Unit = when {
        entry.isDir -> ({ onEnter(entry) })
        isHtml(entry.name) -> ({ onOpenCanvas(entry.path) })
        isImage(entry.name) -> ({ onOpenExternally(entry.path) })
        else -> ({ onEnter(entry) })
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(LumenShapes.row)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.row)
            .clickable(onClick = tap)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)
            .testTag("entry-${entry.path}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .sizeIn(minWidth = 30.dp, minHeight = 30.dp)
                .clip(LumenShapes.glyph)
                .background(if (entry.isDir) colors.water.copy(alpha = 0.16f) else colors.bg)
                .border(1.dp, colors.outline, LumenShapes.glyph),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (entry.isDir) "\u25b8" else fileGlyph(entry.name),
                color = if (entry.isDir) colors.water else colors.dim,
                fontFamily = Mono,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Text(
                if (entry.isDir) "folder" else humanSize(entry.size),
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.3.sp,
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .sizeIn(minWidth = LumenSize.touchMin, minHeight = LumenSize.touchMin)
                    .semantics { contentDescription = "file actions" }
                    .clip(LumenShapes.small)
                    .clickable { menu = true }
                    .padding(horizontal = 8.dp)
                    .testTag("menu-${entry.path}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\u22ef",
                    color = colors.dim,
                    fontFamily = Mono,
                    fontSize = 16.sp,
                )
            }
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = colors.surface,
            ) {
                if (entry.isDir) {
                    MenuItem(colors, "Open", "open-${entry.path}") {
                        menu = false
                        onEnter(entry)
                    }
                } else {
                    MenuItem(colors, "Edit", "edit-${entry.path}") {
                        menu = false
                        onEnter(entry)
                    }
                    if (isHtml(entry.name)) {
                        MenuItem(colors, "Preview page", "preview-${entry.path}") {
                            menu = false
                            onOpenCanvas(entry.path)
                        }
                    }
                    if (isImage(entry.name)) {
                        MenuItem(colors, "Open externally", "external-${entry.path}") {
                            menu = false
                            onOpenExternally(entry.path)
                        }
                    }
                }
                MenuItem(colors, "Rename", "rename-${entry.path}") {
                    menu = false
                    onRename(entry)
                }
                MenuItem(colors, "Delete", "delete-${entry.path}", destructive = true) {
                    menu = false
                    onDelete(entry)
                }
            }
        }
    }
}

@Composable
private fun MenuItem(
    colors: LumenColors,
    label: String,
    tag: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (destructive) alertOn(colors) else colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.body,
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(tag),
    )
}

/**
 * The full-screen editor: monospace body, Save and Cancel. A file truncated at
 * the line cap notes it and disables Save so a partial read can never overwrite
 * the whole file. An unsaved draft asks before it is discarded.
 */
@Composable
private fun EditorOverlay(
    colors: LumenColors,
    editor: EditorState,
    onCancel: () -> Unit,
    onSave: (String, Boolean) -> Unit,
    onDraftChange: (String) -> Unit,
    onOpenCanvas: (String) -> Unit = {},
) {
    val binary = editor.readOnly
    val failed = editor.error != null && !binary
    val revision = remember(editor.path, editor.lines, editor.truncated) {
        editor.path + "\u0000" + editor.truncated + "\u0000" + editor.lines.hashCode()
    }
    val source = editor.draft ?: editor.lines.joinToString("\n")
    var localDraft by remember(editor.path) { mutableStateOf<String?>(null) }
    var localDirty by remember(editor.path) { mutableStateOf(false) }
    var baseline by remember(revision) { mutableStateOf(source) }
    var lastDraft by remember(editor.path) { mutableStateOf(editor.draft) }
    var wasDirty by remember(editor.path) { mutableStateOf(editor.dirty) }
    var discardAsk by remember(editor.path) { mutableStateOf(false) }
    val draft = localDraft ?: source
    val dirty = editor.dirty || localDirty || draft != baseline

    LaunchedEffect(revision) {
        if (!editor.dirty && !localDirty) {
            localDraft = null
            localDirty = false
            baseline = editor.draft ?: editor.lines.joinToString("\n")
        }
    }
    LaunchedEffect(editor.draft, editor.dirty) {
        val external = editor.draft
        val justCleared = external == null && lastDraft != null && localDirty
        val saved = (wasDirty && !editor.dirty) || justCleared
        if (external != null) lastDraft = external
        wasDirty = editor.dirty
        if (saved) {
            val content = localDraft ?: lastDraft ?: editor.lines.joinToString("\n")
            localDraft = content
            localDirty = false
            baseline = content
        }
    }

    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val highlight = editor.highlight
    var scrolledFor by remember(editor.path) { mutableStateOf<IntRange?>(null) }

    LaunchedEffect(highlight) {
        val h = highlight ?: return@LaunchedEffect
        if (scrolledFor == h) return@LaunchedEffect
        val l = snapshotFlow { layout }.filterNotNull().first()
        scrolledFor = h
        if (l.lineCount == 0) return@LaunchedEffect
        val line = (h.first - 1).coerceIn(0, l.lineCount - 1)
        scroll.scrollTo(l.getLineTop(line).toInt())
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        LumenTopBar(
            colors = colors,
            title = editor.path,
            titleTag = "editor-path",
            titleSize = 13.sp,
            modifier = Modifier.padding(horizontal = LumenSpacing.page - 4.dp),
            actions = {
                if (isHtml(editor.path)) {
                    LumenBarAction(colors, "View", "editor-view", { onOpenCanvas(editor.path) })
                }
                LumenBarAction(
                    colors = colors,
                    label = "Cancel",
                    tag = "editor-cancel",
                    onClick = { if (dirty) discardAsk = true else onCancel() },
                )
                val canSave = !binary && !editor.truncated && !failed && dirty
                LumenBarAction(
                    colors = colors,
                    label = when {
                        binary -> "Read only"
                        editor.truncated -> "Truncated"
                        else -> "Save"
                    },
                    tag = "editor-save",
                    onClick = { onSave(draft, baseline.isNotEmpty()) },
                    primary = true,
                    enabled = canSave,
                )
            },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = LumenSpacing.page, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${editor.lines.size}${if (editor.truncated) "+" else ""} lines \u00b7 ${draft.length} chars" +
                    if (dirty) " \u00b7 edited" else "",
                color = if (dirty) colors.water else colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.4.sp,
                modifier = Modifier.weight(1f).testTag("editor-meta"),
            )
        }

        // A quiet, testable confirmation that something pointed us at a line
        // range. Absent for a freshly created or untouched file.
        highlight?.let { h ->
            val first = h.first.coerceAtLeast(1)
            val last = h.last.coerceAtLeast(first)
            Text(
                "edited L$first\u2013L$last",
                color = colors.water,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 6.dp)
                    .clip(LumenShapes.small)
                    .background(colors.water.copy(alpha = 0.12f))
                    .border(1.dp, colors.outline, LumenShapes.small)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
                    .testTag("editor-highlight"),
            )
        }

        if (failed) {
            Text(
                editor.error.orEmpty(),
                color = alertOn(colors),
                fontFamily = Mono,
                fontSize = LumenType.body,
                modifier = Modifier.padding(horizontal = LumenSpacing.page, vertical = 10.dp),
            )
            return@Column
        }
        if (binary) {
            Text(
                "binary file \u00b7 read only",
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 6.dp)
                    .testTag("editor-binary"),
            )
        }
        if (editor.truncated) {
            Text(
                "File is too long to edit safely \u2014 saving is off",
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.4.sp,
                modifier = Modifier
                    .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 6.dp)
                    .testTag("editor-truncated"),
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scroll)
                .padding(horizontal = LumenSpacing.page, vertical = 10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = {
                    localDraft = it
                    localDirty = true
                    onDraftChange(it)
                },
                readOnly = editor.readOnly,
                textStyle = LocalTextStyle.current.copy(
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                    lineHeight = LumenType.lineBody,
                ),
                cursorBrush = SolidColor(colors.water),
                onTextLayout = { layout = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val l = layout ?: return@drawBehind
                        val h = highlight ?: return@drawBehind
                        if (l.lineCount == 0) return@drawBehind
                        val start = (h.first - 1).coerceIn(0, l.lineCount - 1)
                        val end = (h.last - 1).coerceIn(0, l.lineCount - 1)
                        if (end < start) return@drawBehind
                        val top = l.getLineTop(start)
                        val bottom = l.getLineBottom(end)
                        drawRect(
                            color = colors.water.copy(alpha = 0.14f),
                            topLeft = Offset(0f, top),
                            size = Size(size.width, bottom - top),
                        )
                    }
                    .testTag("editor-body"),
            )
        }
    }

    if (discardAsk) {
        ConfirmDialog(
            colors = colors,
            title = "Discard changes?",
            message = "This file has edits that were not saved.",
            confirm = "Discard",
            destructive = true,
            yesTag = "discard-yes",
            noTag = "discard-no",
            onDismiss = { discardAsk = false },
            onConfirm = {
                discardAsk = false
                onCancel()
            },
        )
    }
}

@Composable
private fun NameDialog(
    colors: LumenColors,
    title: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
) {
    var value by remember { mutableStateOf(initial) }
    val valid = value.isNotBlank() && value != "." && value != ".." &&
        !value.contains('/') && !value.contains('\\')
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.fg,
        textContentColor = colors.dim,
        title = { Text(title, fontFamily = Mono, fontSize = LumenType.heading, fontWeight = FontWeight.Medium) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.fg, fontFamily = Mono, fontSize = LumenType.bodyLarge),
                cursorBrush = SolidColor(colors.water),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(LumenShapes.inset)
                    .background(colors.bg)
                    .border(1.dp, colors.outline, LumenShapes.inset)
                    .padding(10.dp)
                    .testTag("name-input"),
            )
        },
        confirmButton = {
            Text(
                confirm,
                color = if (valid) colors.accent else colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable(enabled = valid) { onConfirm(value.trim()) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("name-confirm"),
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
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("name-cancel"),
            )
        },
    )
}

@Composable
private fun ConfirmDialog(
    colors: LumenColors,
    title: String,
    message: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    destructive: Boolean = false,
    yesTag: String = "confirm-yes",
    noTag: String = "confirm-no",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.fg,
        textContentColor = colors.dim,
        title = { Text(title, fontFamily = Mono, fontSize = LumenType.heading, fontWeight = FontWeight.Medium) },
        text = { Text(message, fontFamily = Mono, fontSize = LumenType.body, lineHeight = LumenType.lineTight) },
        confirmButton = {
            Text(
                confirm,
                color = if (destructive) alertOn(colors) else colors.accent,
                fontFamily = Mono,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onConfirm() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag(yesTag),
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
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag(noTag),
            )
        },
    )
}

@Composable
private fun ColumnScope.CenteredHint(
    colors: LumenColors,
    text: String,
    detail: String? = null,
    tag: String? = null,
    error: Boolean = false,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        StateHint(
            colors = colors,
            text = text,
            detail = detail,
            tag = tag,
            tint = if (error) alertOn(colors) else colors.water,
        )
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

/** A one-glyph hint of the file kind, so rows scan without reading names. */
private fun fileGlyph(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "\u25c9"
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "\u25a3"
    "md", "txt" -> "\u2261"
    "kt", "java", "py", "js", "ts", "sh" -> "\u2039\u203a"
    "json", "yml", "yaml", "toml" -> "{}"
    else -> "\u00b7"
}

private fun isHtml(name: String): Boolean =
    name.endsWith(".html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)

private fun isImage(name: String): Boolean = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> true
    else -> false
}
