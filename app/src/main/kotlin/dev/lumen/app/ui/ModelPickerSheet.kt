package dev.lumen.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
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
) {
    val known = models.associateBy { "$provider/${it.id}" }
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
            Text(
                if (refreshing) "refreshing" else "refresh",
                color = colors.accent,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.body,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable(enabled = !refreshing) { onRefresh() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("model-sheet-refresh"),
            )
        }
        Spacer(Modifier.height(LumenSpacing.md))

        if (favorites.isNotEmpty()) {
            LumenSectionHeader(colors, "favorites")
            Spacer(Modifier.height(LumenSpacing.sm))
            for (ref in favorites) {
                val d = known[ref]?.let { modelRowData(provider, it) }
                    ?: modelRowDataFromRef(ref, "sheet-fav-${ref.substringAfter('/')}")
                ModelRow(
                    colors, d, selected == ref, favorite = true, onSelect, onToggleFavorite,
                )
                Spacer(Modifier.height(LumenSpacing.sm))
            }
            Spacer(Modifier.height(LumenSpacing.sm))
        }

        if (recents.isNotEmpty()) {
            LumenSectionHeader(colors, "recent")
            Spacer(Modifier.height(LumenSpacing.sm))
            for (ref in recents) {
                val d = known[ref]?.let { modelRowData(provider, it) }
                    ?: modelRowDataFromRef(ref, "sheet-recent-${ref.substringAfter('/')}")
                ModelRow(
                    colors, d, selected == ref, favorite = ref in favorites, onSelect, onToggleFavorite,
                )
                Spacer(Modifier.height(LumenSpacing.sm))
            }
            Spacer(Modifier.height(LumenSpacing.sm))
        }

        LumenSectionHeader(colors, "all models")
        Spacer(Modifier.height(LumenSpacing.sm))
        for (m in models) {
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
