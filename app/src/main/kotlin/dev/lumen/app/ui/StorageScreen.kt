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
import dev.lumen.app.StorageReport
import java.util.Locale

private val Mono = FontFamily.Monospace

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
) {
    var confirmClearAll by remember { mutableStateOf(false) }

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
                LumenBarAction(colors, "Rescan", "storage-rescan", onRescan)
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
                .background(LumenAlert.copy(alpha = 0.08f))
                .border(1.dp, LumenAlert.copy(alpha = 0.55f), LumenShapes.pill)
                .clickable { confirmClearAll = true }
                .padding(vertical = 14.dp)
                .testTag("storage-clear-all"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Clear all caches",
                color = LumenAlert,
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(LumenSpacing.lg))

        val categories = storage?.categories.orEmpty().sortedByDescending { it.bytes }
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
                    CategoryRow(colors = colors, category = category, onClear = { onClear(category.name) })
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Your chats, source and keys are never cleared.",
            color = colors.faint,
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
                Text(
                    "Clear all",
                    color = LumenAlert,
                    fontFamily = Mono,
                    fontSize = LumenType.body,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .clickable {
                            onClearAll()
                            confirmClearAll = false
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("storage-clear-all-confirm"),
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
                        .clickable { confirmClearAll = false }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("storage-clear-all-cancel"),
                )
            },
        )
    }
}

@Composable
private fun CategoryRow(
    colors: LumenColors,
    category: StorageCategory,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.row)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.row)
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
                if (category.clearable) "Clearable" else "Kept",
                color = if (category.clearable) colors.water else colors.faint,
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
            Text(
                "Clear",
                color = colors.accent,
                fontFamily = Mono,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .background(colors.surface)
                    .border(1.dp, colors.outline, LumenShapes.small)
                    .clickable { onClear() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .testTag("storage-clear-${category.name}"),
            )
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
