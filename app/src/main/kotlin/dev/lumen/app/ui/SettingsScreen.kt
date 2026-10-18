package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.data.ProviderCatalogue
import kotlin.math.roundToInt

/**
 * Settings, regrouped from a flat 13-section scroll into tight, labelled cards.
 * Values are mono, labels proportional; the API key sits at the top as a
 * connection row instead of buried at the bottom, and model choice is a single
 * row into the shared picker rather than three inline options.
 */
@Composable
fun SettingsScreen(
    colors: LumenColors,
    provider: String,
    model: String,
    theme: String,
    onProvider: (String) -> Unit,
    onModel: (String) -> Unit,
    onTheme: (String) -> Unit,
    onEditKey: () -> Unit,
    /** Default `task` subagent model (`provider/model`); "" inherits the chat model. */
    subagentModel: String = "",
    onSubagentModel: (String) -> Unit = {},
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Active palette: "prism" | "ember" | "phosphor" | "abyss". */
    palette: String = "prism",
    /** Switch palette from the settings screen. */
    onPalette: (String) -> Unit = {},
    askBeforeTools: Boolean = false,
    onAskBeforeTools: (Boolean) -> Unit = {},
    /** Whether a finished/failed run posts a title-only notification. */
    notifyOnComplete: Boolean = true,
    onNotifyOnComplete: (Boolean) -> Unit = {},
    /** Per-session cost ceiling in USD; 0 means unlimited. */
    maxCostUsd: Double = 0.0,
    onMaxCost: (Double) -> Unit = {},
    /** Active primary agent: "build" | "plan" | "delegate". */
    agentMode: String = "build",
    onAgentMode: (String) -> Unit = {},
    /** Auto-compaction threshold percentage (50..95) of the model window. */
    autoCompactPercent: Int = 80,
    onAutoCompactPercent: (Int) -> Unit = {},
    /** Open the storage manager. */
    onStorage: () -> Unit = {},
    /** Open the diagnostics screen (Linux environment + event log). */
    onDiagnostics: () -> Unit = {},
    /** Open the GitHub token + repository screen, when supplied. */
    onGitHub: (() -> Unit)? = null,
    /** Non-secret GitHub login shown on the GitHub link's status line. */
    githubLogin: String = "",
    /** Open the full-screen live model picker for the default model, when supplied. */
    onBrowseModels: (() -> Unit)? = null,
    /** Open the live picker aimed at the subagent model, when supplied. */
    onBrowseSubagent: (() -> Unit)? = null,
    /** Whether an API key is currently stored (drives the connection row). */
    hasKey: Boolean = true,
    /** True while the live model catalogue is loading; the default-model row spins. */
    modelsLoading: Boolean = false,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 4.dp, bottom = LumenSpacing.xxl),
    ) {
        LumenTopBar(
            colors = colors,
            title = "Settings",
            onBack = onBack,
            backTag = "settings-back",
            titleSize = LumenType.title,
        )
        Spacer(Modifier.height(LumenSpacing.lg))

        // ---- connection ----
        LumenSectionHeader(colors, "connection")
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsCard(colors) {
            FieldRow(colors, "provider") {
                LumenSegmented(
                    segments = ProviderCatalogue.choices.map {
                        LumenSegment(it.first, it.second, "provider-${it.first}")
                    },
                    selected = provider,
                    onSelect = onProvider,
                    colors = colors,
                )
            }
            RowDivider(colors)
            NavRow(
                colors = colors,
                label = "api key",
                value = if (hasKey) "change" else "not set",
                tag = "edit-key",
                onClick = onEditKey,
            )
        }

        // ---- model ----
        Spacer(Modifier.height(LumenSpacing.xl))
        LumenSectionHeader(colors, "model")
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsCard(colors) {
            NavRow(
                colors = colors,
                label = "default model",
                value = model.substringAfterLast('/').ifBlank { "none" },
                tag = "settings-model",
                onClick = { onBrowseModels?.invoke() ?: onModel(model) },
                enabled = onBrowseModels != null,
                loading = modelsLoading,
            )
            RowDivider(colors)
            NavRow(
                colors = colors,
                label = "subagent model",
                value = if (subagentModel.isBlank()) "inherit" else subagentModel.substringAfterLast('/'),
                tag = "settings-subagent-model",
                onClick = { onBrowseSubagent?.invoke() },
                enabled = onBrowseSubagent != null,
            )
        }

        // ---- appearance ----
        Spacer(Modifier.height(LumenSpacing.xl))
        LumenSectionHeader(colors, "appearance")
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsCard(colors) {
            FieldRow(colors, "theme") {
                LumenSegmented(
                    segments = listOf(
                        LumenSegment("system", "system", "theme-system"),
                        LumenSegment("light", "light", "theme-light"),
                        LumenSegment("dark", "dark", "theme-dark"),
                    ),
                    selected = theme,
                    onSelect = onTheme,
                    colors = colors,
                )
            }
            RowDivider(colors)
            FieldRow(colors, "palette") {
                LumenSegmented(
                    segments = LumenColors.PaletteIds.map {
                        LumenSegment(it, paletteLabel(it), "palette-$it")
                    },
                    selected = palette,
                    onSelect = onPalette,
                    colors = colors,
                )
            }
        }

        // ---- agent ----
        Spacer(Modifier.height(LumenSpacing.xl))
        LumenSectionHeader(colors, "agent")
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsCard(colors) {
            FieldRow(colors, "mode") {
                LumenSegmented(
                    segments = listOf(
                        LumenSegment("build", "build", "agent-build"),
                        LumenSegment("plan", "plan", "agent-plan"),
                        LumenSegment("delegate", "delegate", "agent-delegate"),
                    ),
                    selected = agentMode,
                    onSelect = onAgentMode,
                    colors = colors,
                )
            }
            RowDivider(colors)
            ToggleRow(
                colors = colors,
                label = "ask before tools",
                checked = askBeforeTools,
                onCheckedChange = onAskBeforeTools,
                tag = "ask-before-tools",
            )
        }

        // ---- limits ----
        Spacer(Modifier.height(LumenSpacing.xl))
        LumenSectionHeader(colors, "limits")
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsCard(colors) {
            FieldRow(colors, "budget") {
                val custom = isCustomBudget(maxCostUsd)
                LumenSegmented(
                    segments = buildList {
                        add(LumenSegment("off", "unlimited", "budget-off"))
                        add(LumenSegment("050", "\$0.50", "budget-050"))
                        add(LumenSegment("2", "\$2", "budget-2"))
                        add(LumenSegment("5", "\$5", "budget-5"))
                        if (custom) add(LumenSegment("custom", formatBudget(maxCostUsd), "budget-custom"))
                    },
                    selected = budgetValue(maxCostUsd),
                    onSelect = { if (it != "custom") onMaxCost(budgetUsd(it)) },
                    colors = colors,
                )
            }
            RowDivider(colors)
            FieldRow(colors, "auto-compact \u00b7 $autoCompactPercent%") {
                LumenSlider(
                    value = autoCompactPercent.toFloat(),
                    onValueChange = { onAutoCompactPercent(it.roundToInt()) },
                    colors = colors,
                    valueRange = 50f..95f,
                    tag = "context-slider",
                )
            }
        }

        // ---- notifications ----
        Spacer(Modifier.height(LumenSpacing.xl))
        LumenSectionHeader(colors, "notifications")
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsCard(colors) {
            ToggleRow(
                colors = colors,
                label = "notify when a run finishes",
                checked = notifyOnComplete,
                onCheckedChange = onNotifyOnComplete,
                tag = "notify-on-complete",
            )
        }

        // ---- integrations ----
        if (onGitHub != null) {
            Spacer(Modifier.height(LumenSpacing.xl))
            LumenSectionHeader(colors, "integrations")
            Spacer(Modifier.height(LumenSpacing.sm))
            SettingsCard(colors) {
                NavRow(
                    colors = colors,
                    label = "github",
                    value = if (githubLogin.isBlank()) "connect" else githubLogin,
                    tag = "settings-github",
                    onClick = onGitHub,
                )
            }
        }

        // ---- system ----
        Spacer(Modifier.height(LumenSpacing.xl))
        LumenSectionHeader(colors, "system")
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsCard(colors) {
            NavRow(colors, "storage", "space \u00b7 caches", "settings-storage", onStorage)
            RowDivider(colors)
            NavRow(colors, "diagnostics", "environment \u00b7 log", "settings-diagnostics", onDiagnostics)
        }
    }
}

/** A grouped card: one surface with a hairline boundary holding sibling rows. */
@Composable
private fun SettingsCard(colors: LumenColors, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.card),
        content = content,
    )
}

@Composable
private fun RowDivider(colors: LumenColors) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.outline.copy(alpha = 0.6f)))
}

/** A navigational row: label left, mono value + chevron right. */
@Composable
private fun NavRow(
    colors: LumenColors,
    label: String,
    value: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = colors.fg,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = colors.water,
                strokeWidth = 1.5.dp,
            )
        } else {
            Text(
                value,
                color = colors.dim,
                fontFamily = LumenFonts.value,
                fontSize = LumenType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                // fill = false so a short value keeps the label wide while a very
                // long model id is capped at half the row instead of crushing it.
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text("\u203a", color = colors.accent, fontFamily = LumenFonts.value, fontSize = 20.sp)
    }
}

/** A toggle row: label left, themed switch right. */
@Composable
private fun ToggleRow(
    colors: LumenColors,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    tag: String,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = colors.fg,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        LumenSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = colors,
            tag = tag,
        )
    }
}

/** A labelled control row inside a card: small label above, control below. */
@Composable
private fun FieldRow(colors: LumenColors, label: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            label,
            color = colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.caption,
            fontWeight = FontWeight.Normal,
        )
        Spacer(Modifier.height(LumenSpacing.sm))
        content()
    }
}

private fun paletteLabel(id: String): String = when (id) {
    "ember" -> "ember"
    "phosphor" -> "phosphor"
    "abyss" -> "abyss"
    else -> "prism"
}

private fun isCustomBudget(maxCostUsd: Double): Boolean =
    maxCostUsd > 0.0 && maxCostUsd != 0.5 && maxCostUsd != 2.0 && maxCostUsd != 5.0

private fun formatBudget(maxCostUsd: Double): String {
    val n = if (maxCostUsd == Math.floor(maxCostUsd)) maxCostUsd.toLong().toString() else maxCostUsd.toString()
    return "\$$n"
}

private fun budgetValue(maxCostUsd: Double): String = when {
    maxCostUsd <= 0.0 -> "off"
    maxCostUsd == 0.5 -> "050"
    maxCostUsd == 2.0 -> "2"
    maxCostUsd == 5.0 -> "5"
    else -> "custom"
}

private fun budgetUsd(segment: String): Double = when (segment) {
    "050" -> 0.5
    "2" -> 2.0
    "5" -> 5.0
    else -> 0.0
}
