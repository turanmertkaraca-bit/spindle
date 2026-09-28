package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Mono = FontFamily.Monospace

/**
 * First-run key entry. Minimal: pick a provider, paste a key, done. The key is
 * stored on-device and only ever sent to the provider you chose.
 */
@Composable
fun KeyScreen(
    colors: LumenColors,
    onSubmit: (provider: String, key: String) -> Unit,
    modifier: Modifier = Modifier,
    onToggleTheme: (() -> Unit)? = null,
) {
    var provider by remember { mutableStateOf("opencode-go") }
    var key by remember { mutableStateOf("") }

    Column(
        modifier.fillMaxSize().background(colors.bg)
            .padding(start = 20.dp, end = 20.dp, top = 64.dp, bottom = 24.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("lumen", color = colors.fg, fontFamily = Mono, fontSize = 26.sp, fontWeight = FontWeight.Medium)
            if (onToggleTheme != null) {
                Text(
                    "◐",
                    color = colors.faint, fontFamily = Mono, fontSize = 16.sp,
                    modifier = Modifier.clickable { onToggleTheme() }.padding(4.dp).testTag("theme"),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "connect a provider to start",
            color = colors.dim, fontFamily = Mono, fontSize = 13.sp,
        )
        Spacer(Modifier.height(28.dp))

        Text("provider", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in dev.lumen.app.data.ProviderCatalogue.choices) {
                val selected = provider == p.first
                Text(
                    p.second,
                    color = if (selected) colors.fg else colors.dim,
                    fontFamily = Mono, fontSize = 13.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, if (selected) colors.fg else colors.rule, RoundedCornerShape(8.dp))
                        .background(if (selected) colors.rule else colors.bg)
                        .clickable { provider = p.first }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("provider-${p.first}"),
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("api key", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .border(1.dp, colors.rule, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (key.isEmpty()) {
                Text("paste your key…", color = colors.faint, fontFamily = Mono, fontSize = 14.sp)
            }
            BasicTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().testTag("keyfield"),
            )
        }

        Spacer(Modifier.height(24.dp))
        val canGo = key.isNotBlank()
        Text(
            "continue",
            color = if (canGo) colors.accent else colors.faint,
            fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = canGo) { onSubmit(provider, key) }
                .padding(vertical = 10.dp, horizontal = 4.dp)
                .testTag("continue"),
        )
        Spacer(Modifier.weight(1f))
        Text(
            "keys stay on this device.\nthey are sent only to the provider you pick.",
            color = colors.faint, fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}
