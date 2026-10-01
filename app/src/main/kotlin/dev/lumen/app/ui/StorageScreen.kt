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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    Column(
        modifier.fillMaxSize().background(colors.bg).imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("storage", color = colors.fg, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "rescan",
                    color = colors.dim, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onRescan() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("storage-rescan"),
                )
                Text(
                    "done",
                    color = colors.accent, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("storage-back"),
                )
            }
        }
        Spacer(Modifier.height(20.dp))

        Text(
            storage?.let { humanBytes(it.total) } ?: "…",
            color = colors.fg, fontFamily = Mono, fontSize = 30.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.testTag("storage-total"),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            when {
                storage == null -> "calculating…"
                storage.scanning -> "scanning…"
                storage.categories.isEmpty() -> "nothing measured"
                else -> "${storage.categories.size} areas · largest first"
            },
            color = colors.dim, fontFamily = Mono, fontSize = 12.sp,
        )
        Spacer(Modifier.height(14.dp))

        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .border(1.dp, colors.rule, RoundedCornerShape(50))
                .clickable { onClearAll() }
                .padding(vertical = 13.dp)
                .testTag("storage-clear-all"),
            contentAlignment = Alignment.Center,
        ) {
            Text("clear all caches", color = colors.accent, fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(14.dp))

        val categories = storage?.categories.orEmpty().sortedByDescending { it.bytes }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("storage-list")) {
            items(categories, key = { it.name }) { category ->
                CategoryRow(colors = colors, category = category, onClear = { onClear(category.name) })
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "source, lumen.db and keys are never cleared",
            color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.3.sp,
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
        Modifier.fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp)
            .testTag("storage-row-${category.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(category.name, color = colors.fg, fontFamily = Mono, fontSize = 13.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                if (category.clearable) "clearable" else "kept",
                color = if (category.clearable) colors.water else colors.faint,
                fontFamily = Mono, fontSize = 10.5.sp, letterSpacing = 0.4.sp,
            )
        }
        Text(
            humanBytes(category.bytes),
            color = colors.fg, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        )
        if (category.clearable) {
            Spacer(Modifier.width(10.dp))
            Text(
                "clear",
                color = colors.accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.rule, RoundedCornerShape(6.dp))
                    .clickable { onClear() }
                    .padding(horizontal = 10.dp, vertical = 5.dp)
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
