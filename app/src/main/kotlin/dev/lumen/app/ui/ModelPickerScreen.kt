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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lumen.app.data.ProviderCatalogue
import dev.spindle.core.provider.ModelInfo
import java.util.Locale

private val Mono = FontFamily.Monospace

/**
 * The full-screen live model catalogue: browse every model a provider
 * advertises, filter by provider, refresh, and pick one. Every action is
 * hoisted — the caller wires them to [dev.lumen.app.ChatViewModel].
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
    /** Select a full `provider/modelId`; the caller navigates back. */
    onSelect: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

        if (error != null && models.isEmpty()) {
            Spacer(Modifier.height(LumenSpacing.md))
            Text(
                error,
                color = LumenAlert,
                fontFamily = Mono,
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
                StateHint(
                    colors,
                    "No models available",
                    detail = "Refresh or check the provider key",
                    tag = "models-empty",
                )
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
                items(models, key = { it.id }) { m ->
                    ModelRow(colors, provider, m, selected == "$provider/${m.id}", onSelect)
                }
            }
        }
    }
}

@Composable
private fun ModelRow(
    colors: LumenColors,
    provider: String,
    m: ModelInfo,
    selected: Boolean,
    onSelect: (String) -> Unit,
) {
    val ref = "$provider/${m.id}"
    val tag = if (selected) "model-option-selected-${m.id}" else "model-option-${m.id}"
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.card)
            .background(if (selected) colors.surface else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.card)
            .clickable { onSelect(ref) }
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                m.label ?: m.id,
                color = if (selected) colors.fg else colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (m.label != m.id) {
                Spacer(Modifier.height(2.dp))
                Text(
                    m.id,
                    color = colors.faint,
                    fontFamily = Mono,
                    fontSize = LumenType.caption,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${m.contextWindow / 1000}k",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.caption,
            )
            if (m.inputCostPerM > 0.0 || m.outputCostPerM > 0.0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    String.format(Locale.US, "%.2f/%.2f", m.inputCostPerM, m.outputCostPerM),
                    color = colors.faint,
                    fontFamily = Mono,
                    fontSize = LumenType.micro,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(10.dp))
            Text("✓", color = colors.water, fontFamily = Mono, fontSize = LumenType.bodyLarge)
        }
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
    Text(
        text,
        color = if (selected) colors.fg else colors.dim,
        fontFamily = Mono,
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
