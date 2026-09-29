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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.data.ProviderCatalogue

private val Mono = FontFamily.Monospace

/** A short, single-line nudge appended under the key field for each provider. */
private val KeyHints: Map<String, String> = mapOf(
    "opencode-go" to "paste the key from your OpenCode Go account.",
    "deepseek" to "paste the key from platform.deepseek.com.",
    "openrouter" to "paste a key from openrouter.ai/keys.",
)

/** The "failed" colour, matching the chat screen's ErrorNotice. */
private fun LumenColors.alert(): Color = spectrum.firstOrNull() ?: accent

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
    initialProvider: String = "opencode-go",
    initialKey: String = "",
    busy: Boolean = false,
    error: String? = null,
) {
    var provider by rememberSaveable { mutableStateOf(initialProvider) }
    var key by rememberSaveable { mutableStateOf(initialKey) }
    var visible by rememberSaveable { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val canGo = key.isNotBlank() && !busy

    Column(
        modifier.fillMaxSize().background(colors.bg).imePadding()
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
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleTheme() }
                        .padding(4.dp)
                        .testTag("theme")
                        .semantics { contentDescription = "toggle theme" },
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
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (p in ProviderCatalogue.choices) {
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
                        .testTag("provider-${p.first}")
                        .semantics { contentDescription = "provider ${p.second}" },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
        Text("api key", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .border(1.dp, colors.rule, RoundedCornerShape(10.dp))
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = key,
                onValueChange = { key = it },
                singleLine = true,
                enabled = !busy,
                textStyle = LocalTextStyle.current.copy(color = colors.fg, fontFamily = Mono, fontSize = 14.sp),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(onGo = { if (canGo) onSubmit(provider, key) }),
                modifier = Modifier.weight(1f).padding(vertical = 12.dp).testTag("keyfield"),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                        if (key.isEmpty()) {
                            Text("paste your key…", color = colors.faint, fontFamily = Mono, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
            )
            Text(
                if (visible) "hide" else "show",
                color = colors.faint, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { visible = !visible }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .semantics { contentDescription = if (visible) "hide key" else "show key" },
            )
            Text(
                "paste",
                color = colors.accent, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        val text = clipboard.getText()?.text
                        if (!text.isNullOrBlank()) key = text.trim()
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .semantics { contentDescription = "paste key" },
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            KeyHints[provider] ?: "paste the key for the provider you picked.",
            color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
        )

        Spacer(Modifier.height(24.dp))
        if (error != null) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.rule)
                    .border(1.dp, colors.alert().copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.alert()))
                Spacer(Modifier.width(10.dp))
                Text(
                    error,
                    color = colors.fg, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
        Box(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(if (canGo) colors.accent else Color.Transparent)
                .border(1.dp, if (canGo) Color.Transparent else colors.rule, RoundedCornerShape(50))
                .clickable(enabled = canGo) { onSubmit(provider, key) }
                .padding(vertical = 14.dp)
                .testTag("continue"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (busy) "connecting…" else "continue",
                color = if (canGo) colors.bg else colors.faint,
                fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            "keys stay on this device.\nthey are sent only to the provider you pick.",
            color = colors.faint, fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}
