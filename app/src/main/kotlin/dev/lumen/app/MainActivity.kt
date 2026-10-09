package dev.lumen.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.lumen.app.platform.RunService
import dev.lumen.app.ui.BootScreen
import dev.lumen.app.ui.CanvasScreen
import dev.lumen.app.ui.DiagnosticsScreen
import dev.lumen.app.ui.FilesScreen
import dev.lumen.app.ui.GitHubScreen
import dev.lumen.app.ui.HomeScreen
import dev.lumen.app.ui.KeyScreen
import dev.lumen.app.ui.LumenChatScreen
import dev.lumen.app.ui.LumenColors
import dev.lumen.app.ui.ModelPickerScreen
import dev.lumen.app.ui.OnboardingScreen
import dev.lumen.app.ui.SettingsScreen
import dev.lumen.app.ui.StorageScreen
import dev.lumen.app.ui.TerminalScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    private val viewModel: ChatViewModel by viewModels {
        ChatViewModel.Factory(applicationContext, File(filesDir, "workspace").apply { mkdirs() })
    }

    /**
     * A session id delivered by a completion-notification tap. Held as Compose
     * state (not a plain field) so the effect inside setContent reacts whether
     * it arrived in [onCreate] (cold start) or [onNewIntent] (singleTask
     * re-entry).
     */
    private var pendingSessionId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeSessionIntent(intent)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.theme) {
                "light" -> false
                "dark" -> true
                else -> systemDark
            }
            val colors = animatedColors(state.palette, dark)
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
            // Preserves per-screen rememberSaveable state while the exclusive
            // `when` below swaps branches, so the chat timeline keeps its scroll
            // offset when the user detours to Files/Settings and back. Chat is
            // keyed by session so each conversation keeps its own position.
            val saveableStateHolder = rememberSaveableStateHolder()
            // Launch maintenance splash: prune the sandbox, then show the app.
            var booted by rememberSaveable { mutableStateOf(false) }
            var bootMessage by remember { mutableStateOf("preparing environment…") }
            val appContext = LocalContext.current
            val storagePermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted -> viewModel.setDownloadsAccess(granted) }
            val requestBatteryExemption: () -> Unit = {
                runCatching {
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(appContext.packageName)) {
                        appContext.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                .setData(Uri.parse("package:${appContext.packageName}")),
                        )
                    }
                }
                Unit
            }
            // Session export/import. File IO runs off the main thread; failures
            // land on the diagnostics ring instead of crashing the picker flow.
            val ioScope = rememberCoroutineScope()
            // The id awaiting a CreateDocument target: the picker result only
            // carries the uri, not the session it was launched for.
            var pendingExportId by remember { mutableStateOf<String?>(null) }
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/json"),
            ) { uri ->
                val id = pendingExportId
                pendingExportId = null
                if (uri == null || id == null) return@rememberLauncherForActivityResult
                ioScope.launch(Dispatchers.IO) {
                    runCatching {
                        val json = viewModel.exportSession(id) ?: error("session not found")
                        appContext.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(StandardCharsets.UTF_8))
                        } ?: error("cannot open export destination")
                    }.onFailure { e ->
                        viewModel.recordDiagnostic("export failed: ${e.message}")
                    }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                ioScope.launch(Dispatchers.IO) {
                    runCatching {
                        val json = appContext.contentResolver.openInputStream(uri)?.use { input ->
                            input.readBytes().toString(StandardCharsets.UTF_8)
                        } ?: error("cannot open import source")
                        val count = viewModel.importSessions(json)
                        viewModel.recordDiagnostic("imported $count session(s)")
                    }.onFailure { e ->
                        viewModel.recordDiagnostic("import failed: ${e.message}")
                    }
                }
            }
            val requestExport: (String) -> Unit = { id ->
                val title = state.sessions.firstOrNull { it.id == id }?.title ?: "session"
                pendingExportId = id
                exportLauncher.launch(archiveFileName(title))
            }
            val requestImport: () -> Unit = {
                importLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream"),
                )
            }
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
            // Boot housekeeping once per launch; idempotent and never fatal.
            LaunchedEffect(Unit) {
                bootMessage = viewModel.runStartupMaintenance()
                booted = true
            }

            // A completion-notification tap asks us to open one session. The id
            // arrives as activity state (cold start or singleTask re-entry), so
            // this fires in both cases; it is cleared after being consumed.
            val deepLinkSession = pendingSessionId
            LaunchedEffect(deepLinkSession) {
                val id = deepLinkSession ?: return@LaunchedEffect
                viewModel.openSession(id)
                route = "chat"
                pendingSessionId = null
            }

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
                        viewModel.refreshGitActivity()
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
            if (!booted) {
                BootScreen(colors, bootMessage, modifier)
            } else if (!state.onboarded) {
                OnboardingScreen(
                    colors = colors,
                    linux = state.linux,
                    onInstallDebian = viewModel::installDebian,
                    onRequestBattery = requestBatteryExemption,
                    onRequestDownloads = {
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    },
                    onAddKey = {
                        viewModel.completeOnboarding()
                        route = "key"
                    },
                    onFinish = {
                        viewModel.completeOnboarding()
                        route = "home"
                    },
                    modifier = modifier,
                )
            } else {
                val screenKey = if (onChat) "chat:${state.currentSessionId}" else route
                saveableStateHolder.SaveableStateProvider(screenKey) {
                    when {
                onChat && state.needsKey -> KeyScreen(
                    colors = colors,
                    onSubmit = { provider, key -> viewModel.saveKey(provider, key) },
                    modifier = modifier,
                    onToggleTheme = toggleTheme,
                    initialProvider = state.provider,
                    initialKey = viewModel.currentApiKey(),
                )
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
                        when (p.substringAfterLast('.', "").lowercase()) {
                            "apk" -> viewModel.installApk(p)
                            "html", "htm" -> openCanvas(p)
                            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> viewModel.openFileExternally(p)
                            else -> {
                                viewModel.openFileInFiles(p, l)
                                filesReturn = "chat"
                                route = "files"
                            }
                        }
                    },
                    onOpenUrl = viewModel::openExternalUrl,
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
                    lastRevert = state.lastRevert,
                    onUndoRevert = viewModel::undoRevert,
                    onDismissRevert = viewModel::dismissRevert,
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
                    palette = state.palette,
                    onPalette = { viewModel.setPalette(it) },
                    askBeforeTools = state.askBeforeTools,
                    onAskBeforeTools = viewModel::setAskBeforeTools,
                    onMaxCost = viewModel::setMaxCost,
                    autoCompactPercent = state.autoCompactPercent,
                    onAutoCompactPercent = viewModel::setAutoCompactPercent,
                    onCompactNow = viewModel::compactNow,
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
                    subagentModel = state.subagentModel,
                    onSubagentModel = viewModel::setSubagentModel,
                    palette = state.palette,
                    onPalette = { viewModel.setPalette(it) },
                    onEditKey = { route = "key" },
                    onBack = { route = "home" },
                    modifier = modifier,
                    askBeforeTools = state.askBeforeTools,
                    onAskBeforeTools = viewModel::setAskBeforeTools,
                    notifyOnComplete = state.notifyOnComplete,
                    onNotifyOnComplete = viewModel::setNotifyOnComplete,
                    maxCostUsd = state.maxCostUsd,
                    onMaxCost = viewModel::setMaxCost,
                    autoCompactPercent = state.autoCompactPercent,
                    onAutoCompactPercent = viewModel::setAutoCompactPercent,
                    agentMode = state.agentMode,
                    onAgentMode = viewModel::setAgentMode,
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
                route == "github" -> {
                    LaunchedEffect(Unit) { viewModel.refreshGitActivity() }
                    GitHubScreen(
                        colors = colors,
                        onBack = {
                            viewModel.refreshGithubLogin()
                            route = "settings"
                        },
                        modifier = modifier,
                        activity = state.gitActivity,
                        activityRefreshing = state.gitActivityRefreshing,
                        onRefreshActivity = viewModel::refreshGitActivity,
                        onCommit = { message -> viewModel.viewModelScope.launch { viewModel.gitCommit(message) } },
                        onPush = { viewModel.viewModelScope.launch { viewModel.gitPush() } },
                    )
                }
                else -> HomeScreen(
                    colors = colors,
                    sessions = state.sessions,
                    runningSessionIds = state.runningSessionIds,
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
                    onExport = requestExport,
                    onImport = requestImport,
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
                    }             // close when
                }                 // close SaveableStateProvider
            }                     // close else
        }
    }

    /**
     * The activity is `singleTask`, so tapping a completion notification while
     * Lumen is already alive lands here instead of [onCreate]. The new intent
     * becomes the activity's intent, and the session id is consumed by the
     * effect inside the composition.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeSessionIntent(intent)
    }

    /** Pull a deep-linked session id out of [intent], consuming it once. */
    private fun consumeSessionIntent(intent: Intent?) {
        if (intent == null) return
        val id = intent.getStringExtra(RunService.EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() } ?: return
        // Consume it so an Activity recreation does not re-open the same chat.
        intent.removeExtra(RunService.EXTRA_SESSION_ID)
        pendingSessionId = id
    }
}

/** A filesystem-safe `lumen-<title>.json` name for the document picker. */
private fun archiveFileName(title: String): String {
    val safe = title.trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.')
        .ifBlank { "session" }
        .take(60)
    return "lumen-$safe.json"
}

/** The palette, eased between light and dark so the theme swap is not a hard cut. */
@Composable
private fun animatedColors(palette: String, dark: Boolean): LumenColors {
    val target = LumenColors.forId(palette, dark)
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
