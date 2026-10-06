package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.lumen.app.data.KeyStore
import dev.lumen.app.data.ProviderCatalogue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private val Mono = FontFamily.Monospace

/** A short, single-line nudge appended under the key field for each provider. */
private val KeyHints: Map<String, String> = mapOf(
    "opencode-go" to "Paste the key from your OpenCode Go account.",
    "deepseek" to "Paste the key from platform.deepseek.com.",
    "openrouter" to "Paste a key from openrouter.ai/keys.",
)

/** One selectable provider: name, a one-line hint and its default model. */
private data class ProviderOption(
    val id: String,
    val name: String,
    val hint: String,
    val model: String,
)

private val Providers: List<ProviderOption> = ProviderCatalogue.choices.map { (id, name) ->
    ProviderOption(
        id = id,
        name = name,
        hint = KeyHints[id] ?: "Paste the key for this provider.",
        model = KeyStore.defaultModel(id).substringAfter('/'),
    )
}

/** The minimal, authenticated probe endpoint per provider. */
private data class ProbeSpec(val url: String, val model: String)

private val ProbeSpecs: Map<String, ProbeSpec> = mapOf(
    "opencode-go" to ProbeSpec(
        "https://opencode.ai/zen/go/v1/chat/completions",
        "deepseek-v4.1-flash",
    ),
    "deepseek" to ProbeSpec(
        "https://api.deepseek.com/chat/completions",
        "deepseek-flash",
    ),
    "openrouter" to ProbeSpec(
        "https://openrouter.ai/api/v1/chat/completions",
        "openrouter/free",
    ),
)

/** Inline result of the key probe. A public `/models` call would not prove anything. */
private sealed interface ProbeOutcome {
    data object Valid : ProbeOutcome
    data object Invalid : ProbeOutcome
    data class Unreachable(val detail: String) : ProbeOutcome
}

/**
 * One authenticated 1-token chat completion. 2xx proves the key is accepted;
 * 401/403 means it was rejected; anything else (or no response) is treated as
 * unreachable rather than pretending the key is bad. The key is only ever sent
 * as an Authorization header — never logged.
 */
private suspend fun probeKey(provider: String, key: String): ProbeOutcome =
    withContext(Dispatchers.IO) {
        val spec = ProbeSpecs[provider]
            ?: return@withContext ProbeOutcome.Unreachable("unknown provider")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15_000
                readTimeout = 20_000
                useCaches = false
                doOutput = true
                setRequestProperty("Authorization", "Bearer $key")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", ProviderCatalogue.USER_AGENT)
            }
            val payload =
                "{\"model\":\"${spec.model}\"," +
                    "\"messages\":[{\"role\":\"user\",\"content\":\"ping\"}]," +
                    "\"max_tokens\":1}"
            conn.outputStream.use { it.write(payload.toByteArray(StandardCharsets.UTF_8)) }
            when (conn.responseCode) {
                in 200..299 -> ProbeOutcome.Valid
                401, 403 -> ProbeOutcome.Invalid
                else -> ProbeOutcome.Unreachable("HTTP ${conn.responseCode}")
            }
        } catch (io: IOException) {
            ProbeOutcome.Unreachable("offline")
        } catch (t: Throwable) {
            ProbeOutcome.Unreachable("unreachable")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

/**
 * First-run key entry. Pick a provider (each shows a hint and its default
 * model), paste a key, optionally test it, continue. The key is stored
 * on-device and only ever sent to the provider you chose.
 *
 * The screen is deliberately self-contained: it keeps [busy]/[error] from the
 * caller (MainActivity wires those later) and runs the "Test key" probe itself
 * through local state, so no caller change is required for testing to work.
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
    var probe by remember { mutableStateOf<ProbeOutcome?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val focusInteraction = remember { MutableInteractionSource() }
    val focused by focusInteraction.collectIsFocusedAsState()

    val canGo = key.isNotBlank() && !busy && !testing
    val canTest = key.isNotBlank() && !busy && !testing

    Column(
        modifier.fillMaxSize().background(colors.bg).imePadding()
            .verticalScroll(rememberScrollState())
            .padding(start = LumenSpacing.xl, end = LumenSpacing.xl, top = LumenSpacing.md, bottom = 28.dp),
    ) {
        LumenTopBar(
            colors = colors,
            title = "API key",
            titleSize = 20.sp,
            actions = {
                if (onToggleTheme != null) {
                    LumenBarAction(
                        colors = colors, label = "\u25d0", tag = "theme", onClick = onToggleTheme,
                        contentDescription = "toggle theme",
                    )
                }
            },
        )
        Spacer(Modifier.height(LumenSpacing.xl))
        Text("Connect a provider to start", color = colors.dim, fontFamily = Mono, fontSize = 13.sp)
        Spacer(Modifier.height(LumenSpacing.xl))

        SectionLabel(colors, "provider")
        Spacer(Modifier.height(LumenSpacing.md))
        Column(verticalArrangement = Arrangement.spacedBy(LumenSpacing.sm)) {
            Providers.forEach { option ->
                ProviderCard(
                    colors = colors,
                    option = option,
                    selected = provider == option.id,
                    enabled = !busy,
                    onClick = {
                        provider = option.id
                        probe = null
                    },
                )
            }
        }

        Spacer(Modifier.height(LumenSpacing.xl))
        SectionLabel(colors, "api key")
        Spacer(Modifier.height(LumenSpacing.md))
        Row(
            Modifier.fillMaxWidth()
                .clip(LumenShapes.panel)
                .background(colors.surface)
                .border(1.dp, if (focused) colors.water else colors.rule, LumenShapes.panel)
                .padding(start = LumenSpacing.md, end = LumenSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = key,
                onValueChange = {
                    key = it
                    probe = null
                },
                singleLine = true,
                enabled = !busy,
                interactionSource = focusInteraction,
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
                            Text("Paste your key", color = colors.faint, fontFamily = Mono, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
            )
            Text(
                if (visible) "Hide" else "Show",
                color = colors.dim, fontFamily = Mono, fontSize = 12.sp,
                modifier = Modifier
                    .clip(LumenShapes.small)
                    .clickable(enabled = !busy) { visible = !visible }
                    .padding(horizontal = LumenSpacing.sm, vertical = LumenSpacing.sm)
                    .testTag("key-visibility")
                    .semantics { contentDescription = if (visible) "hide key" else "show key" },
            )
        }
        Spacer(Modifier.height(LumenSpacing.sm))
        Text(
            KeyHints[provider] ?: "Paste the key for the provider you picked.",
            color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
        )

        Spacer(Modifier.height(LumenSpacing.md))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SecondaryAction(colors = colors, label = "Paste", tag = "key-paste", enabled = !busy) {
                val text = clipboard.getText()?.text
                if (!text.isNullOrBlank()) {
                    key = text.trim()
                    probe = null
                }
            }
            Spacer(Modifier.width(LumenSpacing.sm))
            SecondaryAction(
                colors = colors,
                label = if (testing) "Testing…" else "Test key",
                tag = "test-key",
                enabled = canTest,
            ) {
                scope.launch {
                    testing = true
                    probe = null
                    val result = probeKey(provider, key.trim())
                    probe = result
                    testing = false
                }
            }
        }

        probe?.let { outcome ->
            Spacer(Modifier.height(LumenSpacing.md))
            ProbeLine(colors, outcome)
        }

        if (error != null) {
            Spacer(Modifier.height(LumenSpacing.md))
            AlertLine(colors, error)
        }

        Spacer(Modifier.height(LumenSpacing.xl))
        Box(
            Modifier.fillMaxWidth()
                .clip(LumenShapes.pill)
                .background(if (canGo) colors.water else colors.surface)
                .border(1.dp, if (canGo) Color.Transparent else colors.rule, LumenShapes.pill)
                .clickable(enabled = canGo) { onSubmit(provider, key) }
                .padding(vertical = 14.dp)
                .testTag("continue"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (busy) "Connecting…" else "Continue",
                color = if (canGo) colors.bg else colors.faint,
                fontFamily = Mono, fontSize = 15.sp, fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(LumenSpacing.lg))
        Text(
            "Keys stay on this device.\nThey are sent only to the provider you pick.",
            color = colors.faint, fontFamily = Mono, fontSize = 11.sp, lineHeight = 16.sp,
        )
    }
}

@Composable
private fun SectionLabel(colors: LumenColors, text: String) {
    Text(text, color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
}

@Composable
private fun ProviderCard(
    colors: LumenColors,
    option: ProviderOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .clip(LumenShapes.card)
            .background(if (selected) colors.surface else Color.Transparent)
            .border(1.dp, if (selected) colors.water else colors.rule, LumenShapes.card)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = LumenSpacing.lg, vertical = LumenSpacing.md)
            .testTag("provider-${option.id}")
            .semantics { contentDescription = "provider ${option.name}" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(8.dp).background(
                if (selected) colors.water else Color.Transparent,
                WaterShapes.droplet(tail = 0.5f),
            ),
        )
        Spacer(Modifier.width(LumenSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                option.name,
                color = if (selected) colors.fg else colors.dim,
                fontFamily = Mono, fontSize = 14.sp,
            )
            Spacer(Modifier.height(LumenSpacing.xxs))
            Text(
                option.hint,
                color = colors.faint, fontFamily = Mono, fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(LumenSpacing.sm))
        Text(
            "default \u00b7 ${option.model}",
            color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SecondaryAction(
    colors: LumenColors,
    label: String,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        color = if (enabled) colors.dim else colors.faint,
        fontFamily = Mono, fontSize = 12.sp,
        modifier = Modifier
            .clip(LumenShapes.pill)
            .background(colors.surface)
            .border(1.dp, colors.rule, LumenShapes.pill)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = LumenSpacing.lg, vertical = 9.dp)
            .testTag(tag),
    )
}

@Composable
private fun ProbeLine(colors: LumenColors, outcome: ProbeOutcome) {
    val text = when (outcome) {
        is ProbeOutcome.Valid -> "Key looks valid"
        is ProbeOutcome.Invalid -> "Key rejected by the provider"
        is ProbeOutcome.Unreachable -> "Could not reach the provider (${outcome.detail})"
    }
    val tint = when (outcome) {
        is ProbeOutcome.Valid -> colors.water
        is ProbeOutcome.Invalid -> LumenAlert
        is ProbeOutcome.Unreachable -> colors.dim
    }
    Row(
        Modifier.fillMaxWidth().testTag("probe-status").semantics { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(LumenSpacing.md))
        Text(text, color = tint, fontFamily = Mono, fontSize = 12.sp)
    }
}

@Composable
private fun AlertLine(colors: LumenColors, text: String) {
    Row(
        Modifier.fillMaxWidth()
            .clip(LumenShapes.panel)
            .background(colors.surface)
            .padding(horizontal = LumenSpacing.md, vertical = LumenSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(LumenAlert))
        Spacer(Modifier.width(LumenSpacing.md))
        Text(
            text,
            color = colors.fg, fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
