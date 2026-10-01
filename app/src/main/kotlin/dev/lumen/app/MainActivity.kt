package dev.lumen.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.lumen.app.ui.FilesScreen
import dev.lumen.app.ui.HomeScreen
import dev.lumen.app.ui.KeyScreen
import dev.lumen.app.ui.LumenChatScreen
import dev.lumen.app.ui.LumenColors
import dev.lumen.app.ui.SettingsScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(applicationContext, File(filesDir, "workspace").apply { mkdirs() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.theme) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            val colors = animatedColors(dark)
            val toggleTheme = { viewModel.setTheme(if (dark) "light" else "dark") }

            // Keep the system bar icons legible against our own background.
            val view = LocalView.current
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            }

            var route by rememberSaveable { mutableStateOf("home") }
            // The route to return to when leaving the files cockpit.
            var filesReturn by rememberSaveable { mutableStateOf("home") }
            val onChat = route == "chat" && state.currentSessionId != null

            BackHandler(enabled = route != "home" || state.currentSessionId != null) {
                when {
                    state.peek != null -> viewModel.closePeek()
                    state.editor != null -> viewModel.closeEditor()
                    route == "files" -> route = filesReturn
                    route == "settings" -> route = "home"
                    route == "chat" -> {
                        viewModel.closeChat()
                        route = "home"
                    }
                    state.currentSessionId != null -> viewModel.closeChat()
                }
            }

            val modifier = Modifier.fillMaxSize().systemBarsPadding()
            if (state.needsKey) {
                KeyScreen(
                    colors = colors,
                    onSubmit = { provider, key ->
                        viewModel.saveKey(provider, key)
                        route = "home"
                    },
                    modifier = modifier,
                    onToggleTheme = toggleTheme,
                    initialProvider = state.provider,
                )
            } else when {
                onChat -> LumenChatScreen(
                    steps = state.steps,
                    input = state.input,
                    busy = state.busy,
                    error = state.error,
                    colors = colors,
                    modifier = modifier,
                    title = state.sessions.firstOrNull { it.id == state.currentSessionId }?.title ?: "",
                    onHome = {
                        viewModel.closeChat()
                        route = "home"
                    },
                    onInput = viewModel::onInput,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    onToggleTheme = toggleTheme,
                    onEditKey = { route = "settings" },
                    onFiles = {
                        filesReturn = "chat"
                        route = "files"
                    },
                    onExpandSubagent = viewModel::expandSubagent,
                    usage = state.usage,
                    changes = state.changes,
                    peek = state.peek,
                    onClosePeek = viewModel::closePeek,
                    onOpenFile = viewModel::openFile,
                    onRevert = viewModel::revert,
                    cwd = viewModel.workspacePath,
                    exists = viewModel::fileExists,
                    touchedPaths = state.changes.byFile().keys,
                    ask = state.ask,
                    onAnswerPermission = viewModel::answerPermission,
                    onAnswerQuestion = viewModel::answerQuestion,
                    onSkipQuestion = viewModel::skipQuestion,
                )
                route == "files" -> {
                    LaunchedEffect(Unit) { if (state.files == null) viewModel.openFiles() }
                    FilesScreen(
                        colors = colors,
                        files = state.files,
                        editor = state.editor,
                        onOpenDir = { viewModel.openFiles(it) },
                        onUp = viewModel::filesUp,
                        onEnter = { entry ->
                            if (entry.isDir) viewModel.enterDir(entry.path) else viewModel.editFile(entry.path)
                        },
                        onSaveFile = { path, content ->
                            viewModel.saveFile(path, content)
                            viewModel.closeEditor()
                        },
                        onCloseEditor = viewModel::closeEditor,
                        onCreateFile = viewModel::createFile,
                        onCreateDir = viewModel::createDir,
                        onRename = viewModel::renameEntry,
                        onDelete = viewModel::deleteEntry,
                        onBack = { route = filesReturn },
                        modifier = modifier,
                    )
                }
                route == "settings" -> SettingsScreen(
                    colors = colors,
                    provider = state.provider,
                    model = state.model,
                    theme = state.theme,
                    onProvider = viewModel::setProvider,
                    onModel = viewModel::setModel,
                    onTheme = viewModel::setTheme,
                    onEditKey = {
                        viewModel.clearKey()
                        route = "home"
                    },
                    onBack = { route = "home" },
                    modifier = modifier,
                    askBeforeTools = state.askBeforeTools,
                    onAskBeforeTools = viewModel::setAskBeforeTools,
                )
                else -> HomeScreen(
                    colors = colors,
                    sessions = state.sessions,
                    onNewChat = {
                        viewModel.newChat()
                        route = "chat"
                    },
                    onOpen = { id ->
                        viewModel.openSession(id)
                        route = "chat"
                    },
                    onDelete = viewModel::deleteSession,
                    onSettings = { route = "settings" },
                    modifier = modifier,
                    onToggleTheme = toggleTheme,
                    onFiles = {
                        filesReturn = "home"
                        route = "files"
                    },
                )
            }
        }
    }
}

/** The palette, eased between light and dark so the theme swap is not a hard cut. */
@Composable
private fun animatedColors(dark: Boolean): LumenColors {
    val target = if (dark) LumenColors.Dark else LumenColors.Light
    val spec = tween<Color>(durationMillis = 420)
    return LumenColors(
        bg = animateColorAsState(target.bg, spec, label = "bg").value,
        surface = animateColorAsState(target.surface, spec, label = "surface").value,
        fg = animateColorAsState(target.fg, spec, label = "fg").value,
        dim = animateColorAsState(target.dim, spec, label = "dim").value,
        faint = animateColorAsState(target.faint, spec, label = "faint").value,
        rule = animateColorAsState(target.rule, spec, label = "rule").value,
        accent = animateColorAsState(target.accent, spec, label = "accent").value,
        water = animateColorAsState(target.water, spec, label = "water").value,
        spectrum = target.spectrum.mapIndexed { i, c ->
            animateColorAsState(c, spec, label = "spectrum$i").value
        },
        dark = dark,
    )
}
