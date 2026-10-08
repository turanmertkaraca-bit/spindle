package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.data.ProviderCatalogue
import kotlin.math.roundToInt

private val Mono = FontFamily.Monospace

/** Provider, model and theme. The key itself is managed on the key screen. */
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
    /** Open the full-screen live model picker, when supplied. */
    onBrowseModels: (() -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = LumenSpacing.page, end = LumenSpacing.page, top = 8.dp, bottom = LumenSpacing.xxl),
    ) {
        LumenTopBar(
            colors = colors,
            title = "Settings",
            titleSize = LumenType.title,
            actions = {
                LumenBarAction(colors, "Done", "settings-done", onBack, primary = true)
            },
        )
        Spacer(Modifier.height(LumenSpacing.xl))

        LumenSectionHeader(colors, "provider", caption = "which API endpoint the agent talks to")
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (p in ProviderCatalogue.choices) {
                chip(colors, p.second, provider == p.first, "provider-${p.first}") { onProvider(p.first) }
            }
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(colors, "model", caption = "the default model for new chats")
        Spacer(Modifier.height(LumenSpacing.md))
        val models = ProviderCatalogue.defaultModels(provider)
        if (models.isEmpty()) {
            StateHint(
                colors,
                "No models configured",
                detail = "Check the provider or add a key",
                tag = "models-empty",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in models) {
                val ref = "$provider/${m.id}"
                val selected = model == ref
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(LumenShapes.panel)
                        .background(if (selected) colors.surface else Color.Transparent)
                        .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.panel)
                        .clickable { onModel(ref) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (selected) colors.water else Color.Transparent,
                                    WaterShapes.droplet(tail = 0.5f),
                                ),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            m.label ?: m.id,
                            color = if (selected) colors.fg else colors.dim,
                            fontFamily = Mono,
                            fontSize = LumenType.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Text(
                        "${m.contextWindow / 1000}k",
                        color = colors.faint,
                        fontFamily = Mono,
                        fontSize = LumenType.caption,
                    )
                }
            }
        }
        if (onBrowseModels != null) {
            Spacer(Modifier.height(LumenSpacing.md))
            LumenBarAction(
                colors = colors,
                label = "Browse all models",
                tag = "settings-browse-models",
                onClick = onBrowseModels,
            )
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(colors, "theme", caption = "light, dark or follow the system")
        Spacer(Modifier.height(LumenSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chip(colors, "system", theme == "system", "theme-system") { onTheme("system") }
            chip(colors, "light", theme == "light", "theme-light") { onTheme("light") }
            chip(colors, "dark", theme == "dark", "theme-dark") { onTheme("dark") }
        }

        Spacer(Modifier.height(LumenSpacing.xl))
        LumenSectionHeader(colors, "palette", caption = "the colour mood of the app")
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (id in LumenColors.PaletteIds) {
                val label = when (id) {
                    "ember" -> "Ember"
                    "phosphor" -> "Phosphor"
                    "abyss" -> "Abyss"
                    else -> "Prism"
                }
                chip(colors, label, palette == id, "palette-$id") { onPalette(id) }
            }
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(
            colors,
            "agent",
            caption = "Delegate thinks more and talks less, handing work to subagents",
        )
        Spacer(Modifier.height(LumenSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chip(colors, "build", agentMode == "build", "agent-build") { onAgentMode("build") }
            chip(colors, "plan", agentMode == "plan", "agent-plan") { onAgentMode("plan") }
            chip(colors, "delegate", agentMode == "delegate", "agent-delegate") { onAgentMode("delegate") }
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(
            colors,
            "budget",
            caption = "Stop a session before it spends more than this",
        )
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chip(colors, "off", maxCostUsd <= 0.0, "budget-off") { onMaxCost(0.0) }
            chip(colors, "\$0.50", maxCostUsd == 0.5, "budget-050") { onMaxCost(0.5) }
            chip(colors, "\$2", maxCostUsd == 2.0, "budget-2") { onMaxCost(2.0) }
            chip(colors, "\$5", maxCostUsd == 5.0, "budget-5") { onMaxCost(5.0) }
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(
            colors,
            "context",
            caption = "Auto-compact at $autoCompactPercent% of the model's context window",
        )
        Spacer(Modifier.height(LumenSpacing.md))
        Slider(
            value = autoCompactPercent.toFloat(),
            onValueChange = { onAutoCompactPercent(it.roundToInt()) },
            valueRange = 50f..95f,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("context-slider"),
        )

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(colors, "tools", caption = "what the agent may run on its own")
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.card)
                .clickable { onAskBeforeTools(!askBeforeTools) }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ask before tools",
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "Confirm each tool before it runs",
                    color = colors.dim,
                    fontFamily = Mono,
                    fontSize = LumenType.caption,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = askBeforeTools,
                onCheckedChange = onAskBeforeTools,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.bg,
                    checkedTrackColor = colors.water,
                    uncheckedThumbColor = colors.dim,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.outline,
                ),
                modifier = Modifier.testTag("ask-before-tools"),
            )
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(colors, "notifications", caption = "what Lumen tells you when a run ends")
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.card)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.card)
                .clickable { onNotifyOnComplete(!notifyOnComplete) }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Notify when a run finishes",
                    color = colors.fg,
                    fontFamily = Mono,
                    fontSize = LumenType.bodyLarge,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "A notification when a background run finishes or fails",
                    color = colors.dim,
                    fontFamily = Mono,
                    fontSize = LumenType.caption,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = notifyOnComplete,
                onCheckedChange = onNotifyOnComplete,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.bg,
                    checkedTrackColor = colors.water,
                    uncheckedThumbColor = colors.dim,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.outline,
                ),
                modifier = Modifier.testTag("notify-on-complete"),
            )
        }

        if (onGitHub != null) {
            Spacer(Modifier.height(LumenSpacing.xxl))
            LumenSectionHeader(colors, "integrations", caption = "connect outside services")
            Spacer(Modifier.height(LumenSpacing.md))
            SettingsLink(
                colors = colors,
                label = "github",
                subtitle = if (githubLogin.isBlank()) {
                    "connect a personal access token"
                } else {
                    "connected as $githubLogin"
                },
                tag = "settings-github",
                onClick = onGitHub,
            )
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        LumenSectionHeader(colors, "system", caption = "storage and the linux environment")
        Spacer(Modifier.height(LumenSpacing.md))
        SettingsLink(
            colors,
            "storage",
            "what is using space \u00b7 clear safe caches",
            "settings-storage",
            onStorage,
        )
        Spacer(Modifier.height(LumenSpacing.sm))
        SettingsLink(
            colors,
            "diagnostics",
            "linux environment \u00b7 event log",
            "settings-diagnostics",
            onDiagnostics,
        )

        Spacer(Modifier.height(LumenSpacing.xxl))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(LumenShapes.pill)
                .background(colors.surface)
                .border(1.dp, colors.outline, LumenShapes.pill)
                .clickable { onEditKey() }
                .padding(vertical = 15.dp)
                .testTag("edit-key"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Change API key",
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
            )
        }
    }
}

@Composable
private fun SettingsLink(
    colors: LumenColors,
    label: String,
    subtitle: String,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.card)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.bodyLarge,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.caption,
            )
        }
        Text(
            "\u203a",
            color = colors.accent,
            fontFamily = Mono,
            fontSize = 20.sp,
        )
    }
}

@Composable
private fun chip(
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
        modifier = Modifier
            .heightIn(min = LumenSize.touchMin)
            .clip(LumenShapes.inset)
            .background(if (selected) colors.surface else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.outline, LumenShapes.inset)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag(tag),
    )
}
