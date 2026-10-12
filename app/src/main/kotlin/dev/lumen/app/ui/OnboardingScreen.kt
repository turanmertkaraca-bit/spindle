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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.LinuxEnvironmentState

/**
 * The launch maintenance splash. Shown for the moment it takes to prune the
 * sandbox before the app appears, so a user always sees that housekeeping ran.
 */
@Composable
fun BootScreen(colors: LumenColors, message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxSize().background(colors.bg).testTag("boot-screen"),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(LumenSize.droplet)
                    .clip(WaterShapes.droplet(tail = 0.6f))
                    .background(colors.water),
            )
            Spacer(Modifier.height(LumenSpacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "lumen",
                    color = colors.fg,
                    fontFamily = LumenFonts.value,
                    fontSize = LumenType.hero,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 3.sp,
                )
                Spacer(Modifier.width(LumenSpacing.md))
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = colors.water,
                    strokeWidth = 2.dp,
                )
            }
            Spacer(Modifier.height(LumenSpacing.sm))
            Text(
                message,
                color = colors.dim,
                fontFamily = LumenFonts.label,
                fontSize = LumenType.caption,
            )
        }
    }
}

/**
 * First-run setup wizard. Walks the user through what the app is, lets them pick
 * a palette, sets up the sandbox, offers the background/storage permissions that
 * keep long runs alive, and finally invites an API key (the one step that can be
 * skipped).
 */
@Composable
fun OnboardingScreen(
    colors: LumenColors,
    linux: LinuxEnvironmentState,
    onInstallDebian: () -> Unit,
    onRequestBattery: () -> Unit,
    onRequestDownloads: () -> Unit,
    onAddKey: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier,
    palette: String = "abyss",
    onPalette: (String) -> Unit = {},
    versionName: String = "",
) {
    var step by rememberSaveable { mutableStateOf(0) }
    val steps = 4

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = LumenSpacing.page, vertical = LumenSpacing.xxl)
            .testTag("onboarding"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(LumenSize.droplet)
                    .clip(WaterShapes.droplet(tail = 0.6f))
                    .background(LumenColors.forId(palette, colors.dark).water),
            )
            Spacer(Modifier.width(LumenSpacing.sm))
            Text(
                "lumen",
                color = colors.fg,
                fontFamily = LumenFonts.value,
                fontSize = LumenType.title,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            if (step > 0) {
                Text(
                    "Back",
                    color = colors.dim,
                    fontFamily = LumenFonts.label,
                    fontSize = LumenType.body,
                    modifier = Modifier
                        .clip(LumenShapes.small)
                        .clickable { step-- }
                        .padding(horizontal = LumenSpacing.sm, vertical = LumenSpacing.xs)
                        .testTag("onboarding-back"),
                )
            }
        }

        Spacer(Modifier.height(LumenSpacing.md))
        StepDots(colors = colors, step = step, steps = steps)

        Spacer(Modifier.height(LumenSpacing.xxl))

        when (step) {
            0 -> StepCard(
                colors = colors,
                title = "welcome",
                body = "Lumen is a coding agent that runs a real Linux sandbox on " +
                    "your phone. It edits files, runs commands, and works in the " +
                    "background while you do other things.\n\nThis quick setup takes " +
                    "about a minute.",
            )
            1 -> StepCard(
                colors = colors,
                title = "the sandbox",
                body = "The agent needs a Linux userland for tools like git, node " +
                    "and apt. Debian is a one-time ~48 MB download and the most " +
                    "capable option; without it a lightweight shell is used.",
            )
            2 -> StepCard(
                colors = colors,
                title = "keep it running",
                body = "Android may kill long runs to save battery. Exempting Lumen " +
                    "from battery optimisation keeps the agent alive in the " +
                    "background. Optionally grant shared Downloads access so the " +
                    "sandbox can read files you place there.",
            )
            else -> StepCard(
                colors = colors,
                title = "connect a model",
                body = "Add an API key to start chatting. You can skip this and set " +
                    "it up later — Files, Terminal and Settings work without one.",
            )
        }

        if (step == 0) {
            Spacer(Modifier.height(LumenSpacing.lg))
            PaletteCard(
                colors = colors,
                selected = palette,
                onPalette = onPalette,
            )
        }

        Spacer(Modifier.height(LumenSpacing.xxl))

        when (step) {
            1 -> {
                SandboxStatus(colors, linux)
                Spacer(Modifier.height(LumenSpacing.lg))
                PrimaryButton(
                    colors,
                    "Install Debian",
                    "onboarding-install-debian",
                    onInstallDebian,
                    enabled = !linux.installing,
                )
            }
            2 -> {
                PrimaryButton(colors, "Exempt from battery optimisation", "onboarding-battery", onRequestBattery)
                Spacer(Modifier.height(LumenSpacing.lg))
                SecondaryButton(colors, "Allow shared Downloads", "onboarding-downloads", onRequestDownloads)
            }
            3 -> {
                PrimaryButton(colors, "Add API key", "onboarding-add-key", onAddKey)
                Spacer(Modifier.height(LumenSpacing.lg))
                SecondaryButton(colors, "Skip for now", "onboarding-skip", onFinish)
            }
        }

        if (step < 3) {
            Spacer(Modifier.height(LumenSpacing.xl))
            PrimaryButton(
                colors,
                if (step == 0) "Get started" else "Continue",
                "onboarding-next",
            ) { step++ }
        }

        Spacer(Modifier.height(LumenSpacing.xxl))
        Text(
            "v${versionName.ifBlank { "0.2.4" }}",
            color = colors.faint,
            fontFamily = LumenFonts.value,
            fontSize = LumenType.micro,
        )
    }
}

/** A thin row of step dots: the current step fills with the water hue. */
@Composable
private fun StepDots(colors: LumenColors, step: Int, steps: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LumenSpacing.xs),
    ) {
        repeat(steps) { index ->
            Box(
                Modifier
                    .size(if (index == step) 7.dp else 5.dp)
                    .clip(LumenShapes.pill)
                    .background(if (index == step) colors.water else colors.outline),
            )
        }
    }
}

/** The shared onboarding card: hairline outline, card radius, surface fill. */
@Composable
private fun LumenCard(
    colors: LumenColors,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(LumenShapes.card)
            .background(colors.surface)
            .border(1.dp, colors.outline, LumenShapes.card)
            .padding(LumenSpacing.lg),
        content = content,
    )
}

@Composable
private fun StepCard(colors: LumenColors, title: String, body: String) {
    LumenCard(colors) {
        Text(
            title,
            color = colors.fg,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.title,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(LumenSpacing.md))
        Text(
            body,
            color = colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.bodyLarge,
            lineHeight = LumenType.lineBody,
        )
    }
}

/** A compact one-of-four palette picker. Never picks an icon — only the theme. */
@Composable
private fun PaletteCard(colors: LumenColors, selected: String, onPalette: (String) -> Unit) {
    LumenCard(colors) {
        Text(
            "palette",
            color = colors.fg,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.heading,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(LumenSpacing.xs))
        Text(
            "pick a look — you can change it later",
            color = colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.caption,
        )
        Spacer(Modifier.height(LumenSpacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(LumenSpacing.sm)) {
            LumenColors.PaletteIds.forEach { id ->
                PaletteSwatch(
                    colors = colors,
                    id = id,
                    active = id == selected,
                    onClick = { onPalette(id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PaletteSwatch(
    colors: LumenColors,
    id: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swatch = LumenColors.forId(id, colors.dark)
    Column(
        modifier
            .clip(LumenShapes.inset)
            .background(if (active) colors.surface else Color.Transparent)
            .border(1.dp, if (active) swatch.water else colors.outline, LumenShapes.inset)
            .clickable { onClick() }
            .padding(vertical = LumenSpacing.sm, horizontal = LumenSpacing.xs)
            .testTag("onboarding-palette-$id"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().height(12.dp).clip(LumenShapes.small)) {
            swatch.spectrum.take(4).forEach { stop ->
                Box(Modifier.weight(1f).fillMaxSize().background(stop))
            }
        }
        Spacer(Modifier.height(LumenSpacing.xs))
        Text(
            onboardingPaletteLabel(id),
            color = if (active) colors.fg else colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.micro,
            fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

private fun onboardingPaletteLabel(id: String): String = when (id) {
    "ember" -> "ember"
    "phosphor" -> "phosphor"
    "abyss" -> "abyss"
    else -> "prism"
}

@Composable
private fun SandboxStatus(colors: LumenColors, linux: LinuxEnvironmentState) {
    val label = when {
        linux.installing -> linux.progress ?: "installing…"
        linux.debianActive -> "Debian active"
        linux.debianReady -> "Debian installed (probe failed)"
        linux.alpineReady -> "lightweight sandbox ready"
        else -> "no sandbox installed yet"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.testTag("onboarding-sandbox-status"),
    ) {
        if (linux.installing) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = colors.water,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(LumenSpacing.sm))
        }
        Text(
            label,
            color = if (linux.debianActive || linux.alpineReady) colors.water else colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.caption,
        )
    }
}

@Composable
private fun PrimaryButton(
    colors: LumenColors,
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.pill)
            .background(if (enabled) colors.water.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (enabled) colors.water else colors.faint, LumenShapes.pill)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = LumenSpacing.lg)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (enabled) colors.fg else colors.faint,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SecondaryButton(colors: LumenColors, label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.pill)
            .background(Color.Transparent)
            .border(1.dp, colors.outline, LumenShapes.pill)
            .clickable { onClick() }
            .padding(vertical = LumenSpacing.lg)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = colors.dim,
            fontFamily = LumenFonts.label,
            fontSize = LumenType.bodyLarge,
        )
    }
}
