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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.data.ProviderCatalogue

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
    askBeforeTools: Boolean = false,
    onAskBeforeTools: (Boolean) -> Unit = {},
    /** Open the storage manager. */
    onStorage: () -> Unit = {},
    /** Open the diagnostics screen (Linux environment + event log). */
    onDiagnostics: () -> Unit = {},
) {
    Column(
        modifier.fillMaxSize().background(colors.bg).imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("settings", color = colors.fg, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text(
                "done",
                color = colors.accent, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .testTag("settings-done"),
            )
        }
        Spacer(Modifier.height(24.dp))

        Text("provider", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (p in ProviderCatalogue.choices) {
                chip(colors, p.second, provider == p.first, "provider-${p.first}") { onProvider(p.first) }
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("model", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        val models = ProviderCatalogue.defaultModels(provider)
        for (m in models) {
            val ref = "$provider/${m.id}"
            val selected = model == ref
            Row(
                Modifier.fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, if (selected) colors.water else colors.rule, RoundedCornerShape(10.dp))
                    .background(if (selected) colors.surface else Color.Transparent)
                    .clickable { onModel(ref) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    m.label ?: m.id,
                    color = colors.fg, fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${m.contextWindow / 1000}k",
                    color = colors.dim, fontFamily = Mono, fontSize = 11.sp,
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("theme", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            chip(colors, "system", theme == "system", "theme-system") { onTheme("system") }
            chip(colors, "light", theme == "light", "theme-light") { onTheme("light") }
            chip(colors, "dark", theme == "dark", "theme-dark") { onTheme("dark") }
        }

        Spacer(Modifier.height(24.dp))
        Text("tools", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ask before tools", color = colors.fg, fontFamily = Mono, fontSize = 13.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "confirm each tool before it runs",
                    color = colors.dim, fontFamily = Mono, fontSize = 11.sp,
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
                    uncheckedBorderColor = colors.rule,
                ),
                modifier = Modifier.testTag("ask-before-tools"),
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("system", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        SettingsLink(colors, "storage", "what is using space · clear safe caches", "settings-storage", onStorage)
        Spacer(Modifier.height(6.dp))
        SettingsLink(colors, "diagnostics", "linux environment · event log", "settings-diagnostics", onDiagnostics)

        Spacer(Modifier.height(24.dp))
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .border(1.dp, colors.rule, RoundedCornerShape(50))
                .clickable { onEditKey() }
                .padding(vertical = 14.dp)
                .testTag("edit-key"),
            contentAlignment = Alignment.Center,
        ) {
            Text("update api key", color = colors.fg, fontFamily = Mono, fontSize = 14.sp)
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
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 11.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.fg, fontFamily = Mono, fontSize = 14.sp)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = colors.dim, fontFamily = Mono, fontSize = 11.sp)
        }
        Text("›", color = colors.accent, fontFamily = Mono, fontSize = 18.sp)
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
        fontFamily = Mono, fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) colors.water else colors.rule, RoundedCornerShape(8.dp))
            .background(if (selected) colors.surface else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .testTag(tag),
    )
}
