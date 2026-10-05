package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.EditorState
import dev.lumen.app.FileEntry
import dev.lumen.app.FilesState

private val Mono = FontFamily.Monospace

/** What the "new" header actions are creating. */
private enum class NewKind { File, Folder }

/**
 * The project file cockpit: a breadcrumbed browser plus an in-place editor, all
 * scoped to the session workspace. Pure hoisted state — the caller wires every
 * action to [dev.lumen.app.ChatViewModel].
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
) {
    val dir = files?.dir.orEmpty()

    var newKind by remember { mutableStateOf<NewKind?>(null) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var deleteTarget by remember { mutableStateOf<FileEntry?>(null) }
    var pendingSave by remember { mutableStateOf<Pair<String, String>?>(null) }

    // The editor is the detail half when a file is open, or null when none is.
    val openEditor = editor
    val detailSlot: (@Composable () -> Unit)? = if (openEditor != null) {
        {
            EditorOverlay(
                colors = colors,
                editor = openEditor,
                onCancel = onCloseEditor,
                onSave = { content -> pendingSave = openEditor.path to content },
            )
        }
    } else {
        null
    }

    Box(modifier.fillMaxSize().background(colors.bg).imePadding()) {
        // On wide screens the selected file's editor renders beside the browser;
        // on phones the same editor stacks over it, exactly as before. The list
        // stays composed underneath in both cases, so its scroll position lives on.
        AdaptiveTwoPane(
            list = {
                Column(Modifier.fillMaxSize()) {
                    LumenTopBar(
                        colors = colors,
                        title = "Files",
                        onBack = onBack,
                        backTag = "files-back",
                        actions = {
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
                                    TopMenuAction("New file", "new-file") { onNew(NewKind.File) },
                                    TopMenuAction("New folder", "new-folder") { onNew(NewKind.Folder) },
                                ),
                            )
                        },
                    )

                    Breadcrumb(colors, dir, onOpenDir)

                    val listing = files
                    if (listing == null) {
                        CenteredHint(colors, "Loading files…")
                    } else {
                        val listingError = listing.error
                        if (listingError != null && listing.entries.isEmpty()) {
                            CenteredHint(colors, listingError, error = true)
                        } else if (listing.entries.isEmpty()) {
                            CenteredHint(
                                colors,
                                "This folder is empty",
                                detail = "Use New to add a file or folder",
                                tag = "files-empty",
                            )
                        } else {
                            LazyColumn(
                                Modifier.fillMaxWidth().weight(1f).testTag("files-list"),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) {
                                if (listingError != null) {
                                    item(key = "__error") {
                                        Text(
                                            listingError, color = LumenAlert, fontFamily = Mono, fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp).testTag("files-error"),
                                        )
                                    }
                                }
                                items(listing.entries, key = { it.path }) { entry ->
                                    EntryRow(
                                        colors = colors,
                                        entry = entry,
                                        onEnter = onEnter,
                                        onRename = { renameTarget = it },
                                        onDelete = { deleteTarget = it },
                                        onOpenCanvas = onOpenCanvas,
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
private fun Breadcrumb(colors: LumenColors, dir: String, onOpenDir: (String) -> Unit) {
    val segments = dir.split('/').filter { it.isNotEmpty() }
    Row(
        Modifier.fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "workspace",
            color = if (segments.isEmpty()) colors.fg else colors.water,
            fontFamily = Mono, fontSize = 12.sp,
            modifier = Modifier
                .clip(LumenShapes.glyph)
                .clickable { onOpenDir("") }
                .padding(horizontal = 4.dp, vertical = 3.dp)
                .testTag("crumb-root"),
        )
        var prefix = ""
        for (seg in segments) {
            prefix = if (prefix.isEmpty()) seg else "$prefix/$seg"
            val target = prefix
            Text("/", color = colors.faint, fontFamily = Mono, fontSize = 12.sp)
            Text(
                seg,
                color = colors.water,
                fontFamily = Mono, fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .clip(LumenShapes.glyph)
                    .clickable { onOpenDir(target) }
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .testTag("crumb-$target"),
            )
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
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 4.dp)
            .clip(LumenShapes.inset)
            .clickable { onEnter(entry) }
            .padding(start = 12.dp, end = 6.dp, top = 11.dp, bottom = 11.dp)
            .testTag("entry-${entry.path}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (entry.isDir) "\u25b8" else "\u00b7",
            color = if (entry.isDir) colors.water else colors.faint,
            fontFamily = Mono, fontSize = 13.sp,
            modifier = Modifier.width(18.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            entry.name,
            color = colors.fg, fontFamily = Mono, fontSize = 13.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!entry.isDir) {
            Spacer(Modifier.width(8.dp))
            Text(humanSize(entry.size), color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp)
            if (isHtml(entry.name)) {
                Spacer(Modifier.width(8.dp))
                Text(
                    "\u25b6 View",
                    color = colors.water, fontFamily = Mono, fontSize = 10.5.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .background(colors.surface)
                        .clickable { onOpenCanvas(entry.path) }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                        .testTag("view-${entry.path}"),
                )
            }
        }
        Box {
            Text(
                "⋯", color = colors.faint, fontFamily = Mono, fontSize = 15.sp,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { menu = true }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("menu-${entry.path}"),
            )
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { menu = false },
                containerColor = colors.surface,
            ) {
                DropdownMenuItem(
                    text = { Text("Rename", color = colors.fg, fontFamily = Mono, fontSize = 13.sp) },
                    onClick = { menu = false; onRename(entry) },
                    modifier = Modifier.testTag("rename-${entry.path}"),
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = LumenAlert, fontFamily = Mono, fontSize = 13.sp) },
                    onClick = { menu = false; onDelete(entry) },
                    modifier = Modifier.testTag("delete-${entry.path}"),
                )
            }
        }
    }
}

/**
 * The full-screen editor: monospace body, Save and Cancel. A file truncated at
 * the line cap notes it and disables Save so a partial read can never overwrite
 * the whole file.
 */
@Composable
private fun EditorOverlay(
    colors: LumenColors,
    editor: EditorState,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    val failed = editor.error != null
    // Key on the CONTENT revision, not just the path, so re-reading the same path
    // refreshes the draft. A dirty (unsaved) draft is protected: we keep the
    // user's edits rather than clobbering them with a background re-read.
    val revision = remember(editor.path, editor.lines, editor.truncated) {
        editor.path + "\u0000" + editor.truncated + "\u0000" + editor.lines.hashCode()
    }
    var dirty by remember(editor.path) { mutableStateOf(false) }
    var draft by remember(editor.path) { mutableStateOf(editor.lines.joinToString("\n")) }
    LaunchedEffect(revision) {
        if (!dirty) draft = editor.lines.joinToString("\n")
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        LumenTopBar(
            colors = colors,
            title = editor.path,
            titleTag = "editor-path",
            titleSize = 13.sp,
            actions = {
                LumenBarAction(colors, "Cancel", "editor-cancel", onCancel)
                val canSave = !editor.truncated && !failed
                LumenBarAction(
                    colors = colors,
                    label = if (editor.truncated) "Truncated" else "Save",
                    tag = "editor-save",
                    onClick = { onSave(draft) },
                    primary = true,
                    enabled = canSave,
                )
            },
        )

        if (failed) {
            Text(
                editor.error.orEmpty(), color = LumenAlert, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
            return@Column
        }
        if (editor.truncated) {
            Text(
                "File is too long to edit safely — saving is off",
                color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.4.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp).testTag("editor-truncated"),
            )
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    dirty = true
                },
                textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 13.sp, lineHeight = 19.sp),
                cursorBrush = SolidColor(colors.water),
                modifier = Modifier.fillMaxWidth().testTag("editor-body"),
            )
        }
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
        title = { Text(title, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = TextStyle(color = colors.fg, fontFamily = Mono, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.water),
                modifier = Modifier.fillMaxWidth()
                    .clip(LumenShapes.inset)
                    .background(colors.bg)
                    .padding(10.dp)
                    .testTag("name-input"),
            )
        },
        confirmButton = {
            Text(
                confirm,
                color = if (valid) colors.accent else colors.faint,
                fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable(enabled = valid) { onConfirm(value.trim()) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("name-confirm"),
            )
        },
        dismissButton = {
            Text(
                "Cancel", color = colors.dim, fontFamily = Mono, fontSize = 13.sp,
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
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        titleContentColor = colors.fg,
        textContentColor = colors.dim,
        title = { Text(title, fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium) },
        text = { Text(message, fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp) },
        confirmButton = {
            Text(
                confirm,
                color = if (destructive) LumenAlert else colors.accent,
                fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onConfirm() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("confirm-yes"),
            )
        },
        dismissButton = {
            Text(
                "Cancel", color = colors.dim, fontFamily = Mono, fontSize = 13.sp,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onDismiss() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("confirm-no"),
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
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        StateHint(colors, text, detail = detail, tag = tag, tint = if (error) LumenAlert else colors.water)
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes}B"
    bytes < 1024 * 1024 -> "${bytes / 1024}K"
    else -> "${bytes / (1024 * 1024)}M"
}

/** True for a self-contained page the canvas can render. */
private fun isHtml(name: String): Boolean =
    name.endsWith(".html", ignoreCase = true) || name.endsWith(".htm", ignoreCase = true)
