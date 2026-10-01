package dev.lumen.app.ui

import android.annotation.SuppressLint
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

private val Mono = FontFamily.Monospace

/**
 * The interactive canvas: a full-screen sandboxed viewer for a self-contained
 * HTML page the agent wrote.
 *
 * Security posture, all in one place:
 *  - JavaScript ON and DOM storage ON — the pages are interactive, that is
 *    the entire point;
 *  - file access OFF, content access OFF — the page cannot read the device,
 *    other files, or the sandbox through the WebView;
 *  - cross-file / universal access OFF and network loads BLOCKED — a
 *    self-contained page gets no reach of its own;
 *  - the HTML is loaded as a STRING via loadDataWithBaseURL(null, …), so the
 *    page has no origin to reach back through;
 *  - nothing auto-opens — the caller renders this only after an explicit tap.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CanvasScreen(
    html: String,
    colors: LumenColors,
    sourceName: String = "page",
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Bump to rebuild the WebView with the same HTML (a hard reload).
    var reload by remember { mutableStateOf(0) }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        Row(
            Modifier.fillMaxWidth().background(colors.surface)
                .padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "\u2039", color = colors.dim, fontFamily = Mono, fontSize = 20.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .testTag("canvas-back"),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "\u25b6 $sourceName",
                color = colors.fg, fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).testTag("canvas-title"),
            )
            Text(
                "reload", color = colors.accent, fontFamily = Mono, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { reload += 1 }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                    .testTag("canvas-reload"),
            )
        }
        key(reload) {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("canvas-web"),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(colors.bg.toArgb())
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.blockNetworkLoads = true
                        settings.mediaPlaybackRequiresUserGesture = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        webViewClient = WebViewClient()
                        loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                    }
                },
                onRelease = { web ->
                    runCatching { web.loadUrl("about:blank") }
                    web.destroy()
                },
            )
        }
    }
}
