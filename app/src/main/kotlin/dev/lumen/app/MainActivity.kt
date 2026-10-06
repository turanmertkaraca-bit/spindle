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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lumen.app.ui.CanvasScreen
import dev.lumen.app.ui.DiagnosticsScreen
import dev.lumen.app.ui.FilesScreen
import dev.lumen.app.ui.GitHubScreen
import dev.lumen.app.ui.HomeScreen
import dev.lumen.app.ui.KeyScreen
import dev.lumen.app.ui.LumenChatScreen
import dev.lumen.app.ui.LumenColors
import dev.lumen.app.ui.ModelPickerScreen
import dev.lumen.app.ui.SettingsScreen
import dev.lumen.app.ui.StorageScreen
import dev.lumen.app.ui.TerminalScreen
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(applicationContext, File(filesDir, "workspace").apply { mkdirs() })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
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
            // The lightweight in-chat settings sheet; never a route on its own.
            var quickSettings by rememberSaveable { mutableStateOf(false) }
            // The route to return to when leaving the files cockpit.
            var filesReturn by rememberSaveable { mutableStateOf("home") }
            // The route to return to when leaving the terminal.
            var terminalReturn by rememberSaveable { mutableStateOf("home") }
            // The route to return to when leaving the canvas.
            var canvasReturn by rememberSaveable { mutableStateOf("chat") }
            // The route to return to when leaving the full model picker.
            var modelsReturn by rememberSaveable { mutableStateOf("settings") }
            val onChat = route == "chat" && state.currentSessionId != null

            // Open the canvas only when the page resolved; a refused path stays put.
            val openCanvas: (String) -> Unit = { path ->
                viewModel.openCanvas(path)
                if (viewModel.state.value.canvas != null) {
                    canvasReturn = if (route == "chat" || route == "files") route else "home"
                    route = "canvas"
                }
            }

            // Pull the live catalogue once on start; the embedded snapshot is
            // already seeded in state so the UI is never empty offline.
            LaunchedEffect(Unit) { viewModel.refreshModels() }

            BackHandler(enabled = route != "home" || state.currentSessionId != null) {
                when {
                    quickSettings -> quickSettings = false
                    state.peek != null -> viewModel.closePeek()
                    state.editor != null -> viewModel.closeEditor()
                    route == "canvas" -> {
                        viewModel.closeCanvas()
                        route = canvasReturn
                    }
                    route == "files" -> route = filesReturn
                    route == "terminal" -> {
                        viewModel.closeTerminal()
                        route = terminalReturn
                    }
                    route == "storage" || route == "diagnostics" -> route = "settings"
                    route == "github" -> {
                        viewModel.refreshGithubLogin()
                        route = "settings"
                    }
                    route == "key" -> route = "settings"
                    route == "models" -> route = modelsReturn
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
                    initialKey = viewModel.currentApiKey(),
                )
            } else when {
                onChat -> LumenChatScreen(
                    steps = state.steps,
                    input = state.input,
                    busy = state.busy,
                    error = state.error,
                    colors = colors,
                    modifier = modifier,
                    model = state.model,
                    onModel = { quickSettings = true },
                    onOpenMention = { p, l ->
                        viewModel.openFileInFiles(p, l)
                        filesReturn = "chat"
                        route = "files"
                    },
                    fileRevision = state.fileRevision,
                    fileKind = viewModel::fileKind,
                    contextWindow = state.contextWindow,
                    nextCostUsd = state.nextCostUsd,
                    newTokens = state.newTokens,
                    title = state.sessions.firstOrNull { it.id == state.currentSessionId }?.title ?: "",
                    onHome = {
                        quickSettings = false
                        viewModel.closeChat()
                        route = "home"
                    },
                    onInput = viewModel::onInput,
                    onSend = viewModel::send,
                    onStop = viewModel::stop,
                    agentMode = state.agentMode,
                    onAgentMode = viewModel::setAgentMode,
                    onFork = { state.currentSessionId?.let(viewModel::forkSession) },
                    onRewind = viewModel::rewindTo,
                    onToggleTheme = toggleTheme,
                    onEditKey = {
                        quickSettings = false
                        route = "key"
                    },
                    onFiles = {
                        quickSettings = false
                        filesReturn = "chat"
                        route = "files"
                    },
                    onTerminal = {
                        quickSettings = false
                        terminalReturn = "chat"
                        route = "terminal"
                    },
                    onExpandSubagent = viewModel::expandSubagent,
                    usage = state.usage,
                    budgetUsd = state.maxCostUsd,
                    changes = state.changes,
                    todos = state.todos,
                    peek = state.peek,
                    onClosePeek = viewModel::closePeek,
                    onOpenFile = viewModel::openFile,
                    onRevert = viewModel::revert,
                    cwd = viewModel.workspacePath,
                    exists = viewModel::fileExists,
                    touchedPaths = state.changes.byFile().keys,
                    onBacklinks = viewModel::backlinksFor,
                    onCompleteFiles = viewModel::completeFiles,
                    ask = state.ask,
                    onAnswerPermission = viewModel::answerPermission,
                    onAnswerQuestion = viewModel::answerQuestion,
                    onSkipQuestion = viewModel::skipQuestion,
                    attachments = state.attachments,
                    onRemoveAttachment = viewModel::removeAttachment,
                    hint = state.hint,
                    onOpenCanvas = openCanvas,
                    onAttachImage = viewModel::attachImage,
                    quickSettings = quickSettings,
                    onQuickSettings = { quickSettings = true },
                    onCloseQuickSettings = { quickSettings = false },
                    provider = state.provider,
                    onProvider = viewModel::setProvider,
                    theme = state.theme,
                    onTheme = viewModel::setTheme,
                    askBeforeTools = state.askBeforeTools,
                    onAskBeforeTools = viewModel::setAskBeforeTools,
                    onMaxCost = viewModel::setMaxCost,
                    onOpenFullSettings = {
                        quickSettings = false
                        route = "settings"
                    },
                    onModelSelect = viewModel::setModel,
                    onOpenModels = { modelsReturn = "chat"; route = "models" },
                )
                route == "models" -> ModelPickerScreen(
                    colors = colors,
                    provider = state.provider,
                    models = state.models,
                    selected = state.model,
                    refreshing = state.modelsRefreshing,
                    error = state.modelsError,
                    onProvider = viewModel::setProvider,
                    onSelect = { ref ->
                        viewModel.setModel(ref)
                        route = modelsReturn
                    },
                    onRefresh = { viewModel.refreshModels() },
                    onBack = { route = modelsReturn },
                    modifier = modifier,
                )
                route == "files" -> {
                    // Always re-list on entry so external/agent changes show.
                    LaunchedEffect(Unit) { viewModel.openFiles(state.files?.dir) }
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
                        onOpenCanvas = openCanvas,
                        modifier = modifier,
                    )
                }
                route == "terminal" -> {
                    LaunchedEffect(Unit) { viewModel.openTerminal() }
                    TerminalScreen(
                        colors = colors,
                        lines = state.terminal.lines,
                        running = state.terminal.running,
                        error = state.terminal.error,
                        onSend = viewModel::sendTerminal,
                        onInterrupt = viewModel::interruptTerminal,
                        onClear = viewModel::clearTerminal,
                        onRestart = viewModel::openTerminal,
                        onBack = {
                            viewModel.closeTerminal()
                            route = terminalReturn
                        },
                        modifier = modifier,
                    )
                }
                route == "canvas" -> {
                    // A cold start with no retained HTML falls back to where we came from.
                    LaunchedEffect(state.canvas) {
                        if (state.canvas == null) route = canvasReturn
                    }
                    CanvasScreen(
                        html = state.canvas.orEmpty(),
                        colors = colors,
                        sourceName = state.canvasPath?.substringAfterLast('/') ?: "page",
                        onBack = {
                            viewModel.closeCanvas()
                            route = canvasReturn
                        },
                        modifier = modifier,
                    )
                }
                route == "storage" -> {
                    LaunchedEffect(Unit) { viewModel.scanStorage() }
                    StorageScreen(
                        colors = colors,
                        storage = state.storage,
                        onRescan = viewModel::scanStorage,
                        onClear = viewModel::clearStorageCategory,
                        onClearAll = viewModel::clearCache,
                        onBack = { route = "settings" },
                        modifier = modifier,
                    )
                }
                route == "diagnostics" -> {
                    LaunchedEffect(Unit) { viewModel.refreshLinuxEnvironment() }
                    DiagnosticsScreen(
                        colors = colors,
                        diag = state.diag,
                        linux = state.linux,
                        onInstallDebian = viewModel::installDebian,
                        onRefreshLinux = viewModel::refreshLinuxEnvironment,
                        onClear = viewModel::clearDiagnostics,
                        onBack = { route = "settings" },
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
                    onEditKey = { route = "key" },
                    onBack = { route = "home" },
                    modifier = modifier,
                    askBeforeTools = state.askBeforeTools,
                    onAskBeforeTools = viewModel::setAskBeforeTools,
                    maxCostUsd = state.maxCostUsd,
                    onMaxCost = viewModel::setMaxCost,
                    onStorage = { route = "storage" },
                    onDiagnostics = { route = "diagnostics" },
                    onGitHub = { route = "github" },
                    githubLogin = state.githubLogin,
                    onBrowseModels = { modelsReturn = "settings"; route = "models" },
                )
                route == "key" -> KeyScreen(
                    colors = colors,
                    onSubmit = { provider, key ->
                        viewModel.saveKey(provider, key)
                        route = "settings"
                    },
                    modifier = modifier,
                    onToggleTheme = toggleTheme,
                    initialProvider = state.provider,
                    initialKey = viewModel.currentApiKey(),
                )
                route == "github" -> GitHubScreen(
                    colors = colors,
                    onBack = {
                        viewModel.refreshGithubLogin()
                        route = "settings"
                    },
                    modifier = modifier,
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
                    onFork = viewModel::forkSession,
                    onPin = viewModel::setPinned,
                    onArchive = viewModel::setArchived,
                    onRename = viewModel::renameSession,
                    tagFilter = state.tagFilter,
                    availableTags = state.availableTags,
                    onToggleTagFilter = viewModel::toggleTagFilter,
                    onClearTagFilters = viewModel::clearTagFilters,
                    onAddTag = viewModel::addSessionTag,
                    onRemoveTag = viewModel::removeSessionTag,
                    showArchived = state.showArchived,
                    onShowArchived = viewModel::setShowArchived,
                    search = state.search,
                    searchQuery = state.searchQuery,
                    onSearch = viewModel::searchSessions,
                    onSettings = { route = "settings" },
                    modifier = modifier,
                    onToggleTheme = toggleTheme,
                    onFiles = {
                        filesReturn = "home"
                        route = "files"
                    },
                    onTerminal = {
                        terminalReturn = "home"
                        route = "terminal"
                    },
                    provider = state.provider,
                    model = state.model,
                    maxCostUsd = state.maxCostUsd,
                    sandboxLabel = when {
                        state.linux.installing -> "installing"
                        state.linux.debianActive -> "debian"
                        state.linux.alpineReady -> "alpine"
                        else -> "system"
                    },
                    githubLogin = state.githubLogin,
                    askBeforeTools = state.askBeforeTools,
                    onAskBeforeTools = viewModel::setAskBeforeTools,
                    onDiagnostics = { route = "diagnostics" },
                    onStorage = { route = "storage" },
                    onGitHub = { route = "github" },
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
        outline = animateColorAsState(target.outline, spec, label = "outline").value,
        accent = animateColorAsState(target.accent, spec, label = "accent").value,
        water = animateColorAsState(target.water, spec, label = "water").value,
        spectrum = target.spectrum.mapIndexed { i, c ->
            animateColorAsState(c, spec, label = "spectrum$i").value
        },
        dark = dark,
    )
}
