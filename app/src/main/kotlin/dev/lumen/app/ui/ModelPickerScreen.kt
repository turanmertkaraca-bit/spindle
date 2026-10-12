package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumen.app.data.ProviderCatalogue
import dev.spindle.core.provider.ModelInfo
import java.util.Locale

/**
 * One row a model picker can render, resolved from either a live [ModelInfo] or
 * a bare stored ref (a favourite/recents entry from another provider). Keeps the
 * full-screen picker and the in-chat sheet on the exact same grammar.
 */
internal data class ModelRowData(
    val ref: String,
    val label: String,
    val id: String,
    val providerLabel: String?,
    val contextWindow: Int?,
    val cost: String?,
    val tag: String,
)

/** Build a row from a live model of the given provider. */
internal fun modelRowData(provider: String, m: ModelInfo): ModelRowData = ModelRowData(
    ref = "$provider/${m.id}",
    label = m.label.ifBlank { m.id },
    id = m.id,
    providerLabel = null,
    contextWindow = m.contextWindow,
    cost = if (m.inputCostPerM > 0.0 || m.outputCostPerM > 0.0) {
        String.format(Locale.US, "%.2f/%.2f", m.inputCostPerM, m.outputCostPerM)
    } else {
        null
    },
    tag = "model-option-${m.id}",
)

/** Build a row from a stored ref when no live [ModelInfo] is known for it. */
internal fun modelRowDataFromRef(ref: String, tag: String): ModelRowData = ModelRowData(
    ref = ref,
    label = ref.substringAfter('/'),
    id = ref.substringAfter('/'),
    providerLabel = ref.substringBefore('/'),
    contextWindow = null,
    cost = null,
    tag = tag,
)

/** The shared model row: label, optional id/provider subtitle, context/cost, star. */
@Composable
internal fun ModelRow(
    colors: LumenColors,
    data: ModelRowData,
    selected: Boolean,
    favorite: Boolean,
    onSelect: (String) -> Unit,
    onToggleFavorite: ((String) -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.card)
            .background(if (selected) colors.surface else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.card)
            .clickable { onSelect(data.ref) }
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(data.tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                data.label,
                color = if (selected) colors.fg else colors.dim,
                fontFamily = LumenFonts.value,
                fontSize = LumenType.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = data.providerLabel
                ?: data.id.takeIf { it != data.label }
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    color = colors.faint,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (data.contextWindow != null) {
                Text(
                    "${data.contextWindow / 1000}k",
                    color = colors.faint,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.caption,
                )
            }
            if (data.cost != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    data.cost,
                    color = colors.faint,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.micro,
                )
            }
        }
        if (onToggleFavorite != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                if (favorite) "\u2605" else "\u2606",
                color = if (favorite) colors.accent else colors.faint,
                fontFamily = LumenFonts.value,
                fontSize = LumenType.bodyLarge,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable { onToggleFavorite(data.ref) }
                    .padding(4.dp)
                    .testTag("model-star-${data.id}"),
            )
        } else if (selected) {
            Spacer(Modifier.width(10.dp))
            Text("\u2713", color = colors.water, fontFamily = LumenFonts.value, fontSize = LumenType.bodyLarge)
        }
    }
}

/**
 * The full-screen live model catalogue: favourites and recents pinned on top
 * (regardless of provider), then the active provider's advertised models. Every
 * action is hoisted — the caller wires them to [dev.lumen.app.ChatViewModel].
 */
@Composable
fun ModelPickerScreen(
    colors: LumenColors,
    provider: String,
    models: List<ModelInfo>,
    /** The active model as `provider/modelId`. */
    selected: String,
    refreshing: Boolean,
    error: String?,
    onProvider: (String) -> Unit,
    /** Select a full `provider/modelId`; the caller switches provider + navigates back. */
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    favorites: Set<String> = emptySet(),
    recents: List<String> = emptyList(),
    onToggleFavorite: ((String) -> Unit)? = null,
) {
    val known = models.associateBy { "$provider/${it.id}" }
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .testTag("models-screen"),
    ) {
        LumenTopBar(
            colors = colors,
            title = "Models",
            onBack = onBack,
            backTag = "models-back",
            titleSize = LumenType.title,
            actions = {
                if (refreshing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        color = colors.accent,
                        strokeWidth = 1.5.dp,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                LumenBarAction(
                    colors,
                    if (refreshing) "Refreshing" else "Refresh",
                    "models-refresh",
                    onRefresh,
                    primary = true,
                    enabled = !refreshing,
                )
            },
        )
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = LumenSpacing.page),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((id, label) in ProviderCatalogue.choices) {
                ProviderChip(colors, label, provider == id, "models-provider-$id") { onProvider(id) }
            }
        }

        // A refresh over an already-populated list still needs to be visible: a
        // slim spectral bar under the provider row, rather than a silent swap.
        if (refreshing && models.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = colors.water,
                trackColor = Color.Transparent,
            )
        }

        if (error != null && models.isEmpty()) {
            Spacer(Modifier.height(LumenSpacing.md))
            Text(
                error,
                color = LumenAlert,
                fontFamily = LumenFonts.value,
                fontSize = LumenType.caption,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LumenSpacing.page)
                    .testTag("models-error"),
            )
        }

        if (models.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(top = LumenSpacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    LumenLoading(colors, "loading models\u2026", tag = "models-loading")
                } else {
                    StateHint(
                        colors,
                        "No models available",
                        detail = "Refresh or check the provider key",
                        tag = "models-empty",
                    )
                }
            }
        } else {
            Spacer(Modifier.height(LumenSpacing.sm))
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .testTag("models-list"),
                contentPadding = PaddingValues(horizontal = LumenSpacing.page, vertical = LumenSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (favorites.isNotEmpty()) {
                    item { Column { LumenSectionHeader(colors, "favorites") } }
                    items(favorites.toList(), key = { "fav-$it" }) { ref ->
                        val d = known[ref]?.let { modelRowData(provider, it) }
                            ?: modelRowDataFromRef(ref, "model-fav-${ref.substringAfter('/')}")
                        ModelRow(
                            colors, d, selected == ref, favorite = true, onSelect,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
                if (recents.isNotEmpty()) {
                    item { Column { LumenSectionHeader(colors, "recent") } }
                    items(recents, key = { "rec-$it" }) { ref ->
                        val d = known[ref]?.let { modelRowData(provider, it) }
                            ?: modelRowDataFromRef(ref, "model-recent-${ref.substringAfter('/')}")
                        ModelRow(
                            colors, d, selected == ref, favorite = ref in favorites, onSelect,
                            onToggleFavorite = onToggleFavorite,
                        )
                    }
                }
                item { Column { LumenSectionHeader(colors, providerLabel(provider)) } }
                items(models, key = { it.id }) { m ->
                    val ref = "$provider/${m.id}"
                    ModelRow(
                        colors,
                        modelRowData(provider, m).copy(
                            tag = if (selected == ref) {
                                "model-option-selected-${m.id}"
                            } else {
                                "model-option-${m.id}"
                            },
                        ),
                        selected = selected == ref,
                        favorite = ref in favorites,
                        onSelect = onSelect,
                        onToggleFavorite = onToggleFavorite,
                    )
                }
            }
        }
    }
}

private fun providerLabel(provider: String): String =
    ProviderCatalogue.choices.firstOrNull { it.first == provider }?.second ?: provider

@Composable
private fun ProviderChip(
    colors: LumenColors,
    text: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Text(
        text,
        color = if (selected) colors.fg else colors.dim,
        fontFamily = LumenFonts.label,
        fontSize = LumenType.body,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier = Modifier
            .clip(LumenShapes.inset)
            .background(if (selected) colors.surface else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.inset)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag(tag),
    )
}
