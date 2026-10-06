package dev.lumen.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

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
    // Bump to rebuild the WebView with the same HTML (a hard reload), but only
    // after a short debounce so mashing "reload" cannot leak a WebView per tap.
    var reload by remember { mutableStateOf(0) }
    var requested by remember { mutableStateOf(0) }
    LaunchedEffect(requested) {
        if (requested == 0) return@LaunchedEffect
        delay(250)
        reload = requested
    }

    // Track the live WebView so lifecycle changes can pause/resume it, and so it
    // is detached from its parent before destroy (destroying an attached view
    // leaks / can crash).
    val lifecycleOwner = LocalLifecycleOwner.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.let { web ->
                runCatching { web.loadUrl("about:blank") }
                runCatching { (web.parent as? ViewGroup)?.removeView(web) }
                runCatching { web.destroy() }
            }
            webView = null
        }
    }

    Column(modifier.fillMaxSize().background(colors.bg)) {
        LumenTopBar(
            colors = colors,
            title = "\u25b6 $sourceName",
            titleTag = "canvas-title",
            onBack = onBack,
            backTag = "canvas-back",
            actions = {
                LumenBarAction(
                    colors = colors,
                    label = "Reload",
                    tag = "canvas-reload",
                    onClick = { requested += 1 },
                )
            },
        )
        if (html.isBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                StateHint(
                    colors,
                    "Nothing to render",
                    detail = "This page has no HTML",
                    tag = "canvas-empty",
                )
            }
        } else {
            key(reload) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .testTag("canvas-web"),
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
                            webView = this
                        }
                    },
                    onRelease = { web ->
                        runCatching { web.loadUrl("about:blank") }
                        runCatching { (web.parent as? ViewGroup)?.removeView(web) }
                        runCatching { web.destroy() }
                        if (webView === web) webView = null
                    },
                )
            }
        }
    }
}
