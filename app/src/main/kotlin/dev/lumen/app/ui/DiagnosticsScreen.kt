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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lumen.app.DiagLine
import dev.lumen.app.LinuxEnvironmentState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Mono = FontFamily.Monospace

/**
 * The diagnostics screen: the Linux environment state with an explicit Debian
 * install, plus a copyable/clearable timestamped event log. All actions are
 * hoisted so the screen stays a pure render layer.
 */
@Composable
fun DiagnosticsScreen(
    colors: LumenColors,
    diag: List<DiagLine>,
    linux: LinuxEnvironmentState,
    onInstallDebian: () -> Unit,
    onRefreshLinux: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    Column(
        modifier.fillMaxSize().background(colors.bg).imePadding()
            .padding(start = 20.dp, end = 20.dp, top = 56.dp, bottom = 20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("diagnostics", color = colors.fg, fontFamily = Mono, fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "copy",
                    color = colors.dim, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            clipboard.setText(AnnotatedString(diag.joinToString("\n") { "${stamp(it.at)} ${it.text}" }))
                        }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("diag-copy"),
                )
                Text(
                    "clear",
                    color = colors.dim, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("diag-clear"),
                )
                Text(
                    "done",
                    color = colors.accent, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onBack() }
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                        .testTag("diag-back"),
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        Text("linux environment", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, colors.rule, RoundedCornerShape(12.dp))
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            EnvRow(colors, "alpine", if (linux.alpineReady) "ready" else "not installed", linux.alpineReady)
            Spacer(Modifier.height(4.dp))
            val debianLabel = when {
                linux.debianActive -> "active"
                linux.debianReady -> "installed (probe failed)"
                else -> "not installed"
            }
            EnvRow(colors, "debian", debianLabel, linux.debianActive)
            linux.progress?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = colors.water, fontFamily = Mono, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val enabled = !linux.installing
                Text(
                    if (linux.installing) "installing…" else "install debian",
                    color = if (enabled) colors.accent else colors.faint,
                    fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, if (enabled) colors.rule else colors.faint, RoundedCornerShape(50))
                        .clickable(enabled = enabled) { onInstallDebian() }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .testTag("install-debian"),
                )
                Text(
                    "refresh",
                    color = colors.dim, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .border(1.dp, colors.rule, RoundedCornerShape(50))
                        .clickable { onRefreshLinux() }
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                        .testTag("linux-refresh"),
                )
            }
        }
        Spacer(Modifier.height(18.dp))

        Text("event log", color = colors.faint, fontFamily = Mono, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        if (diag.isEmpty()) {
            Text("no events yet", color = colors.faint, fontFamily = Mono, fontSize = 12.sp)
        } else {
            LazyColumn(Modifier.fillMaxWidth().weight(1f).testTag("diag-log")) {
                itemsIndexed(diag) { _, line ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                        Text(stamp(line.at), color = colors.faint, fontFamily = Mono, fontSize = 10.5.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(line.text, color = colors.fg, fontFamily = Mono, fontSize = 11.5.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvRow(colors: LumenColors, label: String, value: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = colors.fg, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.width(70.dp))
        Text(value, color = if (ok) colors.water else colors.dim, fontFamily = Mono, fontSize = 12.5.sp)
    }
}

private fun stamp(at: Long): String = TIMESTAMP.format(Date(at))

private val TIMESTAMP = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
