package dev.lumen.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.spindle.core.provider.ModelInfo

/**
 * The dedicated in-chat model picker: the favourites and recents you actually
 * switch between, the active provider's live models, and one link out to the
 * full catalogue. A real bottom sheet — not the buried list the quick settings
 * sheet used to carry.
 */
@Composable
internal fun ModelPickerSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    colors: LumenColors,
    provider: String,
    models: List<ModelInfo>,
    selected: String,
    favorites: Set<String>,
    recents: List<String>,
    refreshing: Boolean,
    onSelect: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onRefresh: () -> Unit,
    onBrowseAll: () -> Unit,
    modelsError: String? = null,
) {
    // Built once per (models, provider) rather than on every recomposition of
    // the hosting screen (which happens on every streaming delta).
    val known = remember(models, provider) { models.associateBy { "$provider/${it.id}" } }
    LumenBottomSheet(
        visible = visible,
        onDismiss = onDismiss,
        colors = colors,
        maxHeight = 560.dp,
        tag = "model-picker-sheet",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "model",
                color = colors.fg,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.heading,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Row(
                Modifier
                    .clip(LumenShapes.small)
                    .clickable(enabled = !refreshing) { onRefresh() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("model-sheet-refresh"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = colors.accent,
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    if (refreshing) "refreshing" else "refresh",
                    color = colors.accent,
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.body,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(LumenSpacing.md))

        if (modelsError != null) {
            Text(
                modelsError,
                color = LumenAlert,
                fontFamily = LumenFonts.value,
                fontSize = LumenType.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("model-sheet-error"),
            )
            Spacer(Modifier.height(LumenSpacing.sm))
        }

        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
            if (favorites.isNotEmpty()) {
                item(key = "fav-header") {
                    LumenSectionHeader(colors, "favorites")
                    Spacer(Modifier.height(LumenSpacing.sm))
                }
                items(favorites.toList(), key = { "fav-$it" }) { ref ->
                    val d = pinnedRowData(ref, known, "sheet-fav-${ref.substringAfter('/')}")
                    ModelRow(
                        colors, d, selected == ref, favorite = true, onSelect, onToggleFavorite,
                        starTag = "sheet-fav-star-$ref",
                    )
                    Spacer(Modifier.height(LumenSpacing.sm))
                }
                item(key = "fav-footer") { Spacer(Modifier.height(LumenSpacing.sm)) }
            }

            if (recents.isNotEmpty()) {
                item(key = "recent-header") {
                    LumenSectionHeader(colors, "recent")
                    Spacer(Modifier.height(LumenSpacing.sm))
                }
                items(recents, key = { "recent-$it" }) { ref ->
                    val d = pinnedRowData(ref, known, "sheet-recent-${ref.substringAfter('/')}")
                    ModelRow(
                        colors, d, selected == ref, favorite = ref in favorites, onSelect, onToggleFavorite,
                        starTag = "sheet-recent-star-$ref",
                    )
                    Spacer(Modifier.height(LumenSpacing.sm))
                }
                item(key = "recent-footer") { Spacer(Modifier.height(LumenSpacing.sm)) }
            }

            item(key = "all-header") {
                LumenSectionHeader(colors, "all models")
                Spacer(Modifier.height(LumenSpacing.sm))
            }
            when {
                models.isEmpty() && refreshing -> {
                    item(key = "loading") {
                        LumenLoading(colors, "loading\u2026", tag = "model-sheet-loading")
                        Spacer(Modifier.height(LumenSpacing.sm))
                    }
                }
                models.isEmpty() -> {
                    item(key = "empty") {
                        Text(
                            "no models",
                            color = colors.faint,
                            fontFamily = LumenFonts.label,
                            fontSize = LumenType.caption,
                        )
                        Spacer(Modifier.height(LumenSpacing.sm))
                    }
                }
                else -> {
                    items(models, key = { "model-${it.id}" }) { m ->
                        val ref = "$provider/${m.id}"
                        ModelRow(
                            colors,
                            modelRowData(provider, m).copy(
                                tag = if (selected == ref) "sheet-model-selected-${m.id}" else "sheet-model-${m.id}",
                            ),
                            selected = selected == ref,
                            favorite = ref in favorites,
                            onSelect = onSelect,
                            onToggleFavorite = onToggleFavorite,
                        )
                        Spacer(Modifier.height(LumenSpacing.sm))
                    }
                }
            }
        }

        Spacer(Modifier.height(LumenSpacing.sm))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                "browse all \u203a",
                color = colors.accent,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onBrowseAll() }
                    .padding(horizontal = 10.dp, vertical = 8.dp)
                    .testTag("model-sheet-browse"),
            )
        }
    }
}
