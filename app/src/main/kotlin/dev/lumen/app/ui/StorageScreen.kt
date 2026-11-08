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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.StorageCategory
import dev.lumen.app.StorageDrill
import dev.lumen.app.StorageReport
import dev.lumen.app.data.StorageChild
import java.util.Locale

private val Mono = FontFamily.Monospace
private const val LOGS_CATEGORY = "logs"

/**
 * The storage manager: a big total, a categorized breakdown and a safe clear
 * per clearable area. Pure hoisted state — the caller wires every action to
 * [dev.lumen.app.ChatViewModel] and triggers the scan on open.
 */
@Composable
fun StorageScreen(
    colors: LumenColors,
    storage: StorageReport?,
    onRescan: () -> Unit,
    onClear: (String) -> Unit,
    onClearAll: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** The open drill-down, or null for the category list. */
    drill: StorageDrill? = null,
    /** Descend into a category (or a folder). */
    onOpenCategory: (String) -> Unit = {},
    onEnterFolder: (StorageChild) -> Unit = {},
    onDrillUp: () -> Unit = {},
    onCloseDrill: () -> Unit = {},
    /** Delete one folder/file precisely, after the confirm dialog. */
    onDeleteEntry: (StorageChild) -> Unit = {},
) {
    if (drill != null) {
        StorageDrillView(
            colors = colors,
            drill = drill,
            onUp = onDrillUp,
            onClose = onCloseDrill,
            onEnter = onEnterFolder,
            onDelete = onDeleteEntry,
            modifier = modifier,
        )
        return
    }
    var confirmClearAll by remember { mutableStateOf(false) }
    val scanning = storage?.scanning == true

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 8.dp, bottom = 24.dp),
    ) {
        LumenTopBar(
            colors = colors,
            title = "Storage",
            titleSize = LumenType.title,
            onBack = onBack,
            backTag = "storage-back",
            actions = {
                LumenBarAction(
                    colors = colors,
                    label = if (scanning) "Scanning\u2026" else "Rescan",
                    tag = "storage-rescan",
                    onClick = onRescan,
                    enabled = !scanning,
                )
            },
        )
        Spacer(Modifier.height(LumenSpacing.xl))

        Text(
            storage?.let { humanBytes(it.total) } ?: "\u2026",
            color = colors.fg,
            fontFamily = Mono,
            fontSize = 36.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("storage-total"),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            when {
                storage == null -> "Calculating\u2026"
                storage.scanning -> "Scanning\u2026"
                storage.categories.isEmpty() -> "Nothing measured"
                else -> "${storage.categories.size} areas \u00b7 largest first"
            },
            color = colors.dim,
            fontFamily = Mono,
            fontSize = LumenType.body,
        )
        Spacer(Modifier.height(LumenSpacing.lg))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.pill)
                .background(LumenAlert.copy(alpha = if (scanning) 0.04f else 0.08f))
                .border(1.dp, LumenAlert.copy(alpha = if (scanning) 0.25f else 0.55f), LumenShapes.pill)
                .clickable(enabled = !scanning) { confirmClearAll = true }
                .padding(vertical = 14.dp)
                .testTag("storage-clear-all"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (scanning) "Scanning\u2026" else "Clear all caches",
                color = if (scanning) colors.dim else alertOn(colors),
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        val cleared = storage?.lastCleared
        val freed = storage?.lastFreed ?: 0L
        if (cleared != null && !scanning) {
            Spacer(Modifier.height(LumenSpacing.sm))
            Text(
                if (freed > 0) "freed ${humanBytes(freed)} \u00b7 $cleared" else "nothing left to reclaim \u00b7 $cleared",
                color = if (freed > 0) colors.water else colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.caption,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .testTag("storage-cleared"),
            )
        }
        Spacer(Modifier.height(LumenSpacing.lg))

        val categories = storage?.categories.orEmpty()
            .filterNot { it.name == LOGS_CATEGORY && it.bytes == 0L }
            .sortedByDescending { it.bytes }
        if (categories.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StateHint(
                    colors = colors,
                    text = when {
                        storage == null -> "Calculating storage\u2026"
                        storage.scanning -> "Scanning storage\u2026"
                        else -> "Nothing measured yet"
                    },
                    detail = "Clearable caches will appear here",
                    tag = "storage-empty",
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("storage-list"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(categories, key = { it.name }) { category ->
                    CategoryRow(
                        colors = colors,
                        category = category,
                        onOpen = { onOpenCategory(category.name) },
                        onClear = { onClear(category.name) },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Your chats, source and keys are never cleared.",
            color = colors.dim,
            fontFamily = Mono,
            fontSize = LumenType.micro,
            letterSpacing = 0.3.sp,
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            containerColor = colors.surface,
            titleContentColor = colors.fg,
            textContentColor = colors.dim,
            title = { Text("Clear all caches?", fontFamily = Mono, fontSize = LumenType.heading, fontWeight = FontWeight.Medium) },
            text = {
                Text(
                    "Cached downloads and temporary files will be removed. Your chats, source and keys stay safe.",
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                    lineHeight = LumenType.lineTight,
                )
            },
            confirmButton = {
                Box(
                    Modifier
                        .heightIn(min = LumenSize.touchMin)
                        .clip(LumenShapes.small)
                        .clickable {
                            onClearAll()
                            confirmClearAll = false
                        }
                        .padding(horizontal = 8.dp)
                        .testTag("storage-clear-all-confirm"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Clear all",
                        color = alertOn(colors),
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            dismissButton = {
                Box(
                    Modifier
                        .heightIn(min = LumenSize.touchMin)
                        .clip(LumenShapes.small)
                        .clickable { confirmClearAll = false }
                        .padding(horizontal = 8.dp)
                        .testTag("storage-clear-all-cancel"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Cancel",
                        color = colors.dim,
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                    )
                }
            },
        )
    }
}

@Composable
private fun CategoryRow(
    colors: LumenColors,
    category: StorageCategory,
    onOpen: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.row)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.row)
            // Tapping the row inspects what is inside; the Clear button stays a
            // separate target, so inspecting can never accidentally delete.
            .clickable(onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .testTag("storage-row-${category.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                category.name,
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                (if (category.clearable) "Clearable" else "Kept") + " \u00b7 tap to inspect",
                color = if (category.clearable) colors.water else colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.4.sp,
            )
        }
        Text(
            humanBytes(category.bytes),
            color = colors.fg,
            fontFamily = Mono,
            fontSize = LumenType.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        if (category.clearable) {
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .heightIn(min = LumenSize.touchMin)
                    .clip(LumenShapes.small)
                    .background(colors.surface)
                    .border(1.dp, colors.outline, LumenShapes.small)
                    .clickable { onClear() }
                    .padding(horizontal = 10.dp)
                    .testTag("storage-clear-${category.name}"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Clear",
                    color = colors.accent,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** "512 B", "3 KB", "12.4 MB", "1.20 GB". */
private fun humanBytes(bytes: Long): String {
    val safe = if (bytes < 0) 0 else bytes
    if (safe < 1024L) return "$safe B"
    val kb = safe / 1024.0
    if (kb < 1024.0) return String.format(Locale.US, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return String.format(Locale.US, "%.1f MB", mb)
    return String.format(Locale.US, "%.2f GB", mb / 1024.0)
}

/**
 * The drill-down: what is actually inside one storage area, largest first.
 *
 * This is the half of the storage manager the owner asked for — *"I need to
 * inspect and delete precisely"*. The category list answers "which area is
 * big"; this answers "which folder inside it", down to individual files, with a
 * per-entry delete that confirms first.
 */
@Composable
private fun StorageDrillView(
    colors: LumenColors,
    drill: StorageDrill,
    onUp: () -> Unit,
    onClose: () -> Unit,
    onEnter: (StorageChild) -> Unit,
    onDelete: (StorageChild) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<StorageChild?>(null) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 8.dp, bottom = 24.dp),
    ) {
        LumenTopBar(
            colors = colors,
            title = drill.label,
            titleSize = LumenType.title,
            onBack = onClose,
            backTag = "storage-drill-close",
            actions = {
                LumenBarAction(
                    colors = colors,
                    label = "Up",
                    tag = "storage-drill-up",
                    onClick = onUp,
                    enabled = drill.parent != null,
                )
            },
        )
        Spacer(Modifier.height(LumenSpacing.lg))

        Text(
            humanBytes(drill.totalBytes),
            color = colors.fg,
            fontFamily = Mono,
            fontSize = 30.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("storage-drill-total"),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                drill.loading -> "Measuring\u2026"
                drill.children.isEmpty() -> "Empty"
                drill.truncated -> "${drill.children.size} entries \u00b7 at least this much"
                else -> "${drill.children.size} entries \u00b7 largest first"
            } + " \u00b7 ${drill.path.substringAfterLast('/')}",
            color = colors.dim,
            fontFamily = Mono,
            fontSize = LumenType.caption,
        )
        Spacer(Modifier.height(LumenSpacing.lg))

        if (drill.children.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StateHint(
                    colors = colors,
                    text = if (drill.loading) "Measuring this folder\u2026" else "Nothing stored here",
                    detail = if (drill.loading) "Large folders can take a moment" else "This area is empty",
                    tag = "storage-drill-empty",
                )
            }
        } else {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("storage-drill-list"),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(drill.children, key = { it.path }) { child ->
                    DrillRow(
                        colors = colors,
                        child = child,
                        onEnter = { onEnter(child) },
                        onDelete = { pendingDelete = child },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Only this app's own files can be deleted. Deleting is permanent.",
            color = colors.dim,
            fontFamily = Mono,
            fontSize = LumenType.micro,
            letterSpacing = 0.3.sp,
        )
    }

    val target = pendingDelete
    if (target != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = colors.surface,
            titleContentColor = colors.fg,
            textContentColor = colors.dim,
            title = {
                Text(
                    "Delete ${if (target.isDirectory) "folder" else "file"}?",
                    fontFamily = Mono,
                    fontSize = LumenType.heading,
                    fontWeight = FontWeight.Medium,
                )
            },
            text = {
                Text(
                    "${target.name} \u00b7 ${humanBytes(target.bytes)}" +
                        if (target.isDirectory) {
                            "\nEverything inside it will be removed. This cannot be undone."
                        } else {
                            "\nThis cannot be undone."
                        },
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                    lineHeight = LumenType.lineTight,
                )
            },
            confirmButton = {
                Box(
                    Modifier
                        .heightIn(min = LumenSize.touchMin)
                        .clip(LumenShapes.small)
                        .clickable {
                            onDelete(target)
                            pendingDelete = null
                        }
                        .padding(horizontal = 8.dp)
                        .testTag("storage-delete-confirm"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Delete",
                        color = alertOn(colors),
                        fontFamily = Mono,
                        fontSize = LumenType.body,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
            dismissButton = {
                Box(
                    Modifier
                        .heightIn(min = LumenSize.touchMin)
                        .clip(LumenShapes.small)
                        .clickable { pendingDelete = null }
                        .padding(horizontal = 8.dp)
                        .testTag("storage-delete-cancel"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cancel", color = colors.dim, fontFamily = Mono, fontSize = LumenType.body)
                }
            },
        )
    }
}

/** One drill-down row: size, name, file count, and a delete target. */
@Composable
private fun DrillRow(
    colors: LumenColors,
    child: StorageChild,
    onEnter: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.row)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.row)
            .clickable(onClick = onEnter)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("storage-entry-${child.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                child.name.ifBlank { "(unnamed)" },
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
                maxLines = 2,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(if (child.isDirectory) "folder" else "file")
                    if (child.isDirectory && child.fileCount > 0) {
                        append(" \u00b7 ${child.fileCount} file")
                        if (child.fileCount != 1L) append('s')
                    }
                },
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.micro,
                letterSpacing = 0.4.sp,
            )
        }
        Text(
            humanBytes(child.bytes),
            color = colors.fg,
            fontFamily = Mono,
            fontSize = LumenType.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .heightIn(min = LumenSize.touchMin)
                .clip(LumenShapes.small)
                .background(colors.surface)
                .border(1.dp, LumenAlert.copy(alpha = 0.55f), LumenShapes.small)
                .clickable(onClick = onDelete)
                .padding(horizontal = 10.dp)
                .testTag("storage-delete-${child.name}"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Delete",
                color = alertOn(colors),
                fontFamily = Mono,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
