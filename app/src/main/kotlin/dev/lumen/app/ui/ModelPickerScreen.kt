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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
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

/**
 * Model labels from routers read "Vendor: Model" (e.g. "DeepSeek: DeepSeek-V4.1").
 * Show just the model name so the title is not the vendor repeated and the row
 * has the width to render it whole.
 */
internal fun displayModelLabel(raw: String): String {
    val trimmed = raw.trim()
    val sep = trimmed.indexOf(": ")
    return if (sep in 1 until trimmed.length - 2) trimmed.substring(sep + 2).trim() else trimmed
}

/** Build a row from a live model of the given provider. */
internal fun modelRowData(provider: String, m: ModelInfo): ModelRowData = ModelRowData(
    ref = "$provider/${m.id}",
    label = displayModelLabel(m.label.ifBlank { m.id }),
    id = m.id,
    providerLabel = null,
    contextWindow = m.contextWindow.takeIf { it > 0 },
    cost = if (m.inputCostPerM > 0.0 || m.outputCostPerM > 0.0) {
        buildList {
            if (m.inputCostPerM > 0.0) add(String.format(Locale.US, "%.2f", m.inputCostPerM))
            if (m.outputCostPerM > 0.0) add(String.format(Locale.US, "%.2f", m.outputCostPerM))
        }.joinToString("/")
    } else {
        // A model that advertises no price is free, not "unknown": draw the word
        // so the blank cost slot never reads as a bug or a fetched-nothing state.
        "free"
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
    starTag: String = "model-star-${data.id}",
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
                // Let a long display name wrap rather than clip to "DeepSeek V4.1
                // Fl…": the row grows a line instead of hiding the model.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = data.providerLabel
                ?: data.id.takeIf { it != data.label }
            if (sub != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    sub,
                    color = colors.dim,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        // Fixed-width metric column: every row's name truncates at the same x and
        // context/cost stay aligned, instead of the column jittering per model.
        Column(Modifier.width(60.dp), horizontalAlignment = Alignment.End) {
            val context = data.contextWindow?.takeIf { it > 0 }
            if (context != null) {
                Text(
                    "${context / 1000}k",
                    color = colors.dim,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (data.cost != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    data.cost,
                    color = colors.dim,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.micro,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text("\u2713", color = colors.water, fontFamily = LumenFonts.value, fontSize = LumenType.bodyLarge)
        }
        if (onToggleFavorite != null) {
            Spacer(Modifier.width(if (selected) 4.dp else 8.dp))
            Box(
                Modifier
                    .size(40.dp)
                    .clip(LumenShapes.small)
                    .clickable { onToggleFavorite(data.ref) }
                    .semantics {
                        contentDescription = if (favorite) {
                            "remove from favourites"
                        } else {
                            "add to favourites"
                        }
                        role = Role.Button
                        this.selected = favorite
                    }
                    .testTag(starTag),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (favorite) "\u2605" else "\u2606",
                    color = if (favorite) colors.accent else colors.fg,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.bodyLarge,
                )
            }
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
    onProvider: (String) -> Unit = {},
    /** Select a full `provider/modelId`; the caller switches provider + navigates back. */
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    favorites: Set<String> = emptySet(),
    recents: List<String> = emptyList(),
    onToggleFavorite: ((String) -> Unit)? = null,
) {
    var browseProvider by rememberSaveable(provider) { mutableStateOf(provider) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(ModelSort.Name) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }

    val allModels = remember(models, provider, browseProvider) {
        if (browseProvider == provider) models else ProviderCatalogue.defaultModels(browseProvider)
    }
    val known = remember(models, provider, browseProvider) {
        buildMap {
            models.forEach { put("$provider/${it.id}", it) }
            allModels.forEach { put("$browseProvider/${it.id}", it) }
        }
    }
    // Filter and sort in one pass so the list the user sees is derived, never
    // mutated: typing in the search box cannot leave a stale row behind.
    val liveModels = remember(allModels, query, sort, favoritesOnly, favorites, browseProvider) {
        filterAndSortModels(
            models = allModels,
            provider = browseProvider,
            query = query,
            sort = sort,
            favoritesOnly = favoritesOnly,
            favorites = favorites,
        )
    }
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
                ProviderChip(colors, label, browseProvider == id, "models-provider-$id") {
                    browseProvider = id
                }
            }
        }

        Spacer(Modifier.height(LumenSpacing.sm))
        ModelFilterBar(
            colors = colors,
            query = query,
            onQuery = { query = it },
            sort = sort,
            onSort = { sort = it },
            favoritesOnly = favoritesOnly,
            onFavoritesOnly = { favoritesOnly = it },
            hasFavorites = favorites.isNotEmpty(),
        )

        // A refresh over an already-populated list still needs to be visible: a
        // slim spectral bar under the provider row, rather than a silent swap.
        if (refreshing && models.isNotEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = colors.water,
                trackColor = Color.Transparent,
            )
        }

        if (error != null) {
            Spacer(Modifier.height(LumenSpacing.md))
            Text(
                error,
                color = alertOn(colors),
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

        if (liveModels.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().padding(top = LumenSpacing.xxl),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) {
                    LumenLoading(colors, "loading models\u2026", tag = "models-loading")
                } else if (query.isNotBlank() || favoritesOnly) {
                    // A filtered-to-nothing list is not "no models available":
                    // saying so would send the user to Refresh for a problem
                    // their own filter caused. Offer the way back instead.
                    StateHint(
                        colors,
                        "No models match",
                        detail = "Clear the search or the starred filter",
                        tag = "models-no-match",
                    )
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
                        val d = pinnedRowData(ref, known, "model-fav-${ref.substringAfter('/')}")
                        ModelRow(
                            colors, d, selected == ref, favorite = true, onSelect,
                            onToggleFavorite = onToggleFavorite,
                            starTag = "model-fav-star-$ref",
                        )
                    }
                }
                if (recents.isNotEmpty()) {
                    item { Column { LumenSectionHeader(colors, "recent") } }
                    items(recents, key = { "rec-$it" }) { ref ->
                        val d = pinnedRowData(ref, known, "model-recent-${ref.substringAfter('/')}")
                        ModelRow(
                            colors, d, selected == ref, favorite = ref in favorites, onSelect,
                            onToggleFavorite = onToggleFavorite,
                            starTag = "model-recent-star-$ref",
                        )
                    }
                }
                item { Column { LumenSectionHeader(colors, ProviderCatalogue.label(browseProvider)) } }
                items(liveModels, key = { it.id }) { m ->
                    val ref = "$browseProvider/${m.id}"
                    ModelRow(
                        colors,
                        modelRowData(browseProvider, m).copy(
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

internal fun pinnedRowData(
    ref: String,
    known: Map<String, ModelInfo>,
    tag: String,
): ModelRowData {
    val refProvider = ref.substringBefore('/')
    val base = known[ref]?.let { modelRowData(refProvider, it) } ?: modelRowDataFromRef(ref, tag)
    return base.copy(providerLabel = ProviderCatalogue.label(refProvider), tag = tag)
}

/** How the model list is ordered. [Name] is the neutral default. */
internal enum class ModelSort(val label: String) {
    Name("name"),
    Context("context"),
    Cost("cost"),
}

/**
 * Apply the browser's search, sort and favourites-only filters in one pass.
 *
 * Pure on purpose: the screen derives its list from this rather than mutating
 * state, so a query can never leave a stale row behind and the behaviour can be
 * tested without a Compose harness.
 *
 * Matching is case-insensitive across the id and the display label, because a
 * user searching "deepseek" should find "DeepSeek: DeepSeek-V4.1" by either
 * name. Sorting puts unknown values last rather than first: a model with no
 * advertised context window or cost is not "cheapest" or "smallest".
 */
internal fun filterAndSortModels(
    models: List<ModelInfo>,
    provider: String,
    query: String,
    sort: ModelSort,
    favoritesOnly: Boolean,
    favorites: Set<String>,
): List<ModelInfo> {
    val needle = query.trim().lowercase()
    val filtered = models.filter { model ->
        if (favoritesOnly && "$provider/${model.id}" !in favorites) return@filter false
        if (needle.isEmpty()) return@filter true
        model.id.lowercase().contains(needle) ||
            displayModelLabel(model.id).lowercase().contains(needle)
    }
    return when (sort) {
        ModelSort.Name -> filtered.sortedBy { displayModelLabel(it.id).lowercase() }
        // Descending for size and cost: "biggest" and "priciest" are what a user
        // opens this list to find. Both fields default to zero when a provider
        // advertises nothing, so zero-cost models sink to the end instead of
        // masquerading as the cheapest option.
        ModelSort.Context -> filtered.sortedByDescending { it.contextWindow }
        ModelSort.Cost -> filtered.sortedByDescending { it.inputCostPerM + it.outputCostPerM }
    }
}

/**
 * Search, sort and favourites controls for the model list.
 *
 * A catalogue of a few hundred models is unusable as a flat scroll: the reason
 * to open this screen is usually "find a cheap one with a big context" or "the
 * one I used last week". These controls are plain text and chips so they match
 * the rest of the app's terminal-adjacent styling rather than introducing a
 * material search bar.
 */
@Composable
private fun ModelFilterBar(
    colors: LumenColors,
    query: String,
    onQuery: (String) -> Unit,
    sort: ModelSort,
    onSort: (ModelSort) -> Unit,
    favoritesOnly: Boolean,
    onFavoritesOnly: (Boolean) -> Unit,
    hasFavorites: Boolean,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = LumenSpacing.page)) {
        // Search field. A BasicTextField keeps the look consistent with the
        // project-name and key inputs elsewhere in the app.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.inset)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.inset)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "\u2315",
                color = colors.dim,
                fontFamily = LumenFonts.value,
                fontSize = LumenType.body,
            )
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "search models",
                        color = colors.dim,
                        fontFamily = LumenFonts.value,
                        fontSize = LumenType.body,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = colors.fg,
                        fontFamily = LumenFonts.value,
                        fontSize = LumenType.body,
                    ),
                    cursorBrush = SolidColor(colors.water),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("models-search"),
                )
            }
            if (query.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(LumenShapes.small)
                        .clickable { onQuery("") }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .testTag("models-search-clear"),
                ) {
                    Text("\u00d7", color = colors.dim, fontFamily = LumenFonts.value, fontSize = LumenType.body)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (option in ModelSort.entries) {
                SortChip(
                    colors = colors,
                    text = option.label,
                    selected = sort == option,
                    tag = "models-sort-${option.name.lowercase()}",
                    onClick = { onSort(option) },
                )
            }
            // Favourites-only only makes sense once something is starred, so it
            // is hidden rather than shown permanently disabled.
            if (hasFavorites) {
                SortChip(
                    colors = colors,
                    text = "starred",
                    selected = favoritesOnly,
                    tag = "models-filter-starred",
                    onClick = { onFavoritesOnly(!favoritesOnly) },
                )
            }
        }
    }
}

/** A small toggle chip used for sort and filter options. */
@Composable
private fun SortChip(
    colors: LumenColors,
    text: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clip(LumenShapes.small)
            .background(if (selected) colors.surface else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) colors.fg else colors.dim,
            fontFamily = LumenFonts.value,
            fontSize = LumenType.micro,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun ProviderChip(
    colors: LumenColors,
    text: String,
    selected: Boolean,
    tag: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(LumenShapes.inset)
            .background(if (selected) colors.surface else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.inset)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (selected) colors.fg else colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.body,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
