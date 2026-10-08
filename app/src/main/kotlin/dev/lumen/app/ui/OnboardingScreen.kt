package dev.lumen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.LinuxEnvironmentState

private val Mono = FontFamily.Monospace

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
            Text(
                "lumen",
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.hero,
                fontWeight = FontWeight.Medium,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(LumenSpacing.sm))
            Text(
                message,
                color = colors.dim,
                fontFamily = Mono,
                fontSize = LumenType.caption,
            )
        }
    }
}

/**
 * First-run setup wizard. Walks the user through what the app is, sets up the
 * sandbox, offers the background/storage permissions that keep long runs alive,
 * and finally invites an API key (the one step that can be skipped).
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
                    .background(colors.water),
            )
            Spacer(Modifier.width(LumenSpacing.sm))
            Text(
                "lumen",
                color = colors.fg,
                fontFamily = Mono,
                fontSize = LumenType.title,
                fontWeight = FontWeight.Medium,
                letterSpacing = 2.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${step + 1}/$steps",
                color = colors.faint,
                fontFamily = Mono,
                fontSize = LumenType.caption,
            )
        }

        Spacer(Modifier.height(LumenSpacing.xxl))

        when (step) {
            0 -> Step(
                colors = colors,
                title = "welcome",
                body = "Lumen is a coding agent that runs a real Linux sandbox on " +
                    "your phone. It edits files, runs commands, and works in the " +
                    "background while you do other things.\n\nThis quick setup takes " +
                    "about a minute.",
            )
            1 -> Step(
                colors = colors,
                title = "the sandbox",
                body = "The agent needs a Linux userland for tools like git, node " +
                    "and apt. Debian is a one-time ~48 MB download and the most " +
                    "capable option; without it a lightweight shell is used.",
            )
            2 -> Step(
                colors = colors,
                title = "keep it running",
                body = "Android may kill long runs to save battery. Exempting Lumen " +
                    "from battery optimisation keeps the agent alive in the " +
                    "background. Optionally grant shared Downloads access so the " +
                    "sandbox can read files you place there.",
            )
            else -> Step(
                colors = colors,
                title = "connect a model",
                body = "Add an API key to start chatting. You can skip this and set " +
                    "it up later — Files, Terminal and Settings work without one.",
            )
        }

        Spacer(Modifier.height(LumenSpacing.xxl))

        when (step) {
            1 -> {
                SandboxStatus(colors, linux)
                Spacer(Modifier.height(LumenSpacing.lg))
                PrimaryButton(colors, "Install Debian", "onboarding-install-debian", onInstallDebian)
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
            "v0.1.3",
            color = colors.faint,
            fontFamily = Mono,
            fontSize = LumenType.micro,
        )
    }
}

@Composable
private fun Step(colors: LumenColors, title: String, body: String) {
    Text(
        title,
        color = colors.fg,
        fontFamily = Mono,
        fontSize = LumenType.title,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(LumenSpacing.md))
    Text(
        body,
        color = colors.dim,
        fontFamily = Mono,
        fontSize = LumenType.bodyLarge,
        lineHeight = LumenType.lineBody,
    )
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
    Text(
        label,
        color = if (linux.debianActive || linux.alpineReady) colors.water else colors.dim,
        fontFamily = Mono,
        fontSize = LumenType.caption,
        modifier = Modifier.testTag("onboarding-sandbox-status"),
    )
}

@Composable
private fun PrimaryButton(colors: LumenColors, label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(LumenShapes.pill)
            .background(colors.water.copy(alpha = 0.16f))
            .border(1.dp, colors.water, LumenShapes.pill)
            .clickable { onClick() }
            .padding(vertical = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.fg, fontFamily = Mono, fontSize = LumenType.bodyLarge, fontWeight = FontWeight.Medium)
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
            .padding(vertical = 14.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = colors.dim, fontFamily = Mono, fontSize = LumenType.bodyLarge)
    }
}
