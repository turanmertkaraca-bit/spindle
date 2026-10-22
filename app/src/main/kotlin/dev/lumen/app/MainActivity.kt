package dev.lumen.app

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.lumen.app.data.ProjectStore
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
import dev.lumen.app.ui.ProjectsScreen
import dev.lumen.app.ui.SettingsScreen
import dev.lumen.app.ui.StorageScreen
import dev.lumen.app.ui.TerminalScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

class MainActivity : ComponentActivity() {

    /** One project folder per project; the active one is the agent's workspace. */
    private val projects by lazy { ProjectStore(applicationContext) }

    private val viewModel: ChatViewModel by viewModels {
        val store = projects
        ChatViewModel.Factory(applicationContext, store.ensure().dir, store)
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
            val colors = animatedColors(state.palette, dark, state.reduceMotion)
            val versionName = remember {
                runCatching {
                    packageManager.getPackageInfo(packageName, 0).versionName
                }.getOrNull().orEmpty()
            }
            val toggleTheme = { viewModel.setTheme(if (dark) "light" else "dark") }

            // Keep the system bar icons legible against our own background.
            val view = LocalView.current
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                window.setBackgroundDrawable(ColorDrawable(colors.bg.toArgb()))
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
            var settingsReturn by rememberSaveable { mutableStateOf("home") }
            // The route to return to when leaving the project picker.
            var projectsReturn by rememberSaveable { mutableStateOf("home") }
            var keyReturn by rememberSaveable { mutableStateOf("settings") }
            // The dedicated in-chat model picker sheet; never a route on its own.
            var modelPicker by rememberSaveable { mutableStateOf(false) }
            // Which slot the full-screen picker is filling: "default" | "subagent".
            var modelsTarget by rememberSaveable { mutableStateOf("default") }
            // Selecting a model may cross providers, so switch first then set.
            val pickModel: (String) -> Unit = { ref ->
                val refProvider = ref.substringBefore('/')
                if (refProvider != state.provider) viewModel.setProvider(refProvider)
                viewModel.setModel(ref)
            }
            // Preserves per-screen rememberSaveable state while the exclusive
            // `when` below swaps branches, so the chat timeline keeps its scroll
            // offset when the user detours to Files/Settings and back. Chat is
            // keyed by session so each conversation keeps its own position.
            val saveableStateHolder = rememberSaveableStateHolder()
            // Launch maintenance splash: prune the sandbox, then show the app.
            var booted by rememberSaveable { mutableStateOf(false) }
            var bootMessage by remember { mutableStateOf("preparing environment…") }
            val appContext = LocalContext.current
            val toast: (String) -> Unit = { message ->
                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
            }
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
                ioScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val json = viewModel.exportSession(id) ?: error("session not found")
                            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(json.toByteArray(StandardCharsets.UTF_8))
                            } ?: error("cannot open export destination")
                        }
                    }
                    result.onSuccess {
                        toast("Session exported")
                    }.onFailure { e ->
                        viewModel.recordDiagnostic("export failed: ${e.message}")
                        toast("Export failed: ${e.message ?: "unknown error"}")
                    }
                }
            }
            val importLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                ioScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val json = appContext.contentResolver.openInputStream(uri)?.use { input ->
                                input.readBytes().toString(StandardCharsets.UTF_8)
                            } ?: error("cannot open import source")
                            json to viewModel.importSessions(json)
                        }
                    }
                    result.onSuccess { (json, count) ->
                        viewModel.recordDiagnostic("imported $count session(s)")
                        when {
                            count > 0 -> toast("Imported $count session(s)")
                            runCatching { Json.parseToJsonElement(json) }.isFailure ->
                                toast("Malformed session file")
                            else -> toast("No sessions found in file")
                        }
                    }.onFailure { e ->
                        viewModel.recordDiagnostic("import failed: ${e.message}")
                        toast("Import failed: ${e.message ?: "unknown error"}")
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
            LaunchedEffect(Unit) { if (!booted) { viewModel.refreshModels() } }
            // Keep the launcher icon in step with the chosen palette (the icon
            // tracks the theme, never a separate picker).
            LaunchedEffect(state.palette) { syncLauncherAlias(state.palette) }
            // Boot housekeeping once per launch; idempotent and never fatal.
            // The staged progress keeps the boot screen honest on a slow start.
            // Guarded on `booted` so a configuration change does not re-run it.
            LaunchedEffect(Unit) {
                if (!booted) {
                    bootMessage = viewModel.runStartupMaintenance { bootMessage = it }
                    booted = true
                }
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
                    modelPicker -> modelPicker = false
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
                    route == "projects" -> route = projectsReturn
                    route == "github" -> {
                        viewModel.refreshGithubLogin()
                        viewModel.refreshGitActivity()
                        route = "settings"
                    }
                    route == "key" -> route = keyReturn
                    route == "models" -> route = modelsReturn
                    route == "settings" -> route = settingsReturn
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
                        keyReturn = "settings"
                        route = "key"
                    },
                    onFinish = {
                        viewModel.completeOnboarding()
                        route = "home"
                    },
                    modifier = modifier,
                    palette = state.palette,
                    onPalette = { viewModel.setPalette(it) },
                    versionName = versionName,
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
                    ambient = !state.reduceMotion,
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
                    contextUsedTokens = state.contextUsedTokens,
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
                    onRegenerate = viewModel::regenerateLast,
                    onEdit = viewModel::beginEdit,
                    onToggleTheme = toggleTheme,
                    onEditKey = {
                        quickSettings = false
                        keyReturn = "chat"
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
                    compacting = state.compacting,
                    onOpenFullSettings = {
                        quickSettings = false
                        settingsReturn = "chat"
                        route = "settings"
                    },
                    onModelSelect = pickModel,
                    onOpenModels = { modelPicker = true },
                    modelPicker = modelPicker,
                    onCloseModelPicker = { modelPicker = false },
                    pickerModels = state.models,
                    pickerRefreshing = state.modelsRefreshing,
                    modelsError = state.modelsError,
                    catalogueLoading = state.modelsRefreshing,
                    favoriteModels = state.favoriteModels,
                    recentModels = state.recentModels,
                    onToggleFavorite = viewModel::toggleFavorite,
                    onRefreshModels = { viewModel.refreshModels() },
                    onBrowseAllModels = {
                        modelPicker = false
                        modelsTarget = "default"
                        modelsReturn = "chat"
                        route = "models"
                    },
                )
                route == "models" -> ModelPickerScreen(
                    colors = colors,
                    provider = state.provider,
                    models = state.models,
                    selected = if (modelsTarget == "subagent") state.subagentModel else state.model,
                    refreshing = state.modelsRefreshing,
                    error = state.modelsError,
                    onProvider = { if (modelsTarget != "subagent") viewModel.setProvider(it) },
                    onSelect = { ref ->
                        if (modelsTarget == "subagent") viewModel.setSubagentModel(ref) else pickModel(ref)
                        route = modelsReturn
                    },
                    onRefresh = { viewModel.refreshModels() },
                    onBack = { route = modelsReturn },
                    modifier = modifier,
                    favorites = state.favoriteModels,
                    recents = state.recentModels,
                    onToggleFavorite = viewModel::toggleFavorite,
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
                            if (viewModel.saveFile(path, content)) {
                                viewModel.closeEditor()
                            } else {
                                toast("Could not save ${path.substringAfterLast('/')}")
                            }
                        },
                        onCloseEditor = viewModel::closeEditor,
                        onCreateFile = viewModel::createFile,
                        onCreateDir = viewModel::createDir,
                        onRename = viewModel::renameEntry,
                        onDelete = viewModel::deleteEntry,
                        onBack = {
                            viewModel.closeFiles()
                            route = filesReturn
                        },
                        onOpenCanvas = openCanvas,
                        modifier = modifier,
                        onDraftChange = viewModel::updateEditorDraft,
                        projectName = state.projectName,
                        workspacePath = viewModel.workspacePath,
                        onOpenExternally = viewModel::openFileExternally,
                        onRefresh = { viewModel.openFiles(state.files?.dir) },
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
                        baseUrl = state.canvasBaseUrl,
                        onBack = {
                            viewModel.closeCanvas()
                            route = canvasReturn
                        },
                        modifier = modifier,
                        onReload = { state.canvasPath?.let { viewModel.openCanvas(it) } },
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
                route == "projects" -> {
                    LaunchedEffect(Unit) { viewModel.refreshProjects() }
                    ProjectsScreen(
                        colors = colors,
                        projects = state.projects,
                        onSwitch = viewModel::switchProject,
                        onCreate = viewModel::createProject,
                        onDelete = viewModel::deleteProject,
                        onOpenFiles = {
                            filesReturn = "projects"
                            route = "files"
                        },
                        onBack = { route = projectsReturn },
                        modifier = modifier,
                        error = state.error,
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
                    reduceMotion = state.reduceMotion,
                    onReduceMotion = viewModel::setReduceMotion,
                    onEditKey = {
                        keyReturn = "settings"
                        route = "key"
                    },
                    onBack = { route = settingsReturn },
                    modifier = modifier,
                    askBeforeTools = state.askBeforeTools,
                    onAskBeforeTools = viewModel::setAskBeforeTools,
                    terseReplies = state.terseReplies,
                    onTerseReplies = viewModel::setTerseReplies,
                    allowedPatterns = state.allowedPatterns,
                    onForgetPattern = viewModel::forgetAllowedPattern,
                    onClearPatterns = viewModel::clearAllowedPatterns,
                    notifyOnComplete = state.notifyOnComplete,
                    onNotifyOnComplete = viewModel::setNotifyOnComplete,
                    maxCostUsd = state.maxCostUsd,
                    onMaxCost = viewModel::setMaxCost,
                    autoCompactPercent = state.autoCompactPercent,
                    onAutoCompactPercent = viewModel::setAutoCompactPercent,
                    agentMode = state.agentMode,
                    onAgentMode = viewModel::setAgentMode,
                    onProjects = {
                        projectsReturn = "settings"
                        route = "projects"
                    },
                    projectName = state.projectName,
                    onStorage = { route = "storage" },
                    onDiagnostics = { route = "diagnostics" },
                    onGitHub = { route = "github" },
                    githubLogin = state.githubLogin,
                    hasKey = !state.needsKey,
                    onBrowseModels = { modelsTarget = "default"; modelsReturn = "settings"; route = "models" },
                    onBrowseSubagent = { modelsTarget = "subagent"; modelsReturn = "settings"; route = "models" },
                    modelsLoading = state.modelsRefreshing,
                    modelsError = state.modelsError,
                )
                route == "key" -> KeyScreen(
                    colors = colors,
                    onSubmit = { provider, key ->
                        viewModel.saveKey(provider, key)
                        route = keyReturn
                    },
                    modifier = modifier,
                    onToggleTheme = toggleTheme,
                    initialProvider = state.provider,
                    initialKey = viewModel.currentApiKey(),
                    onBack = { route = keyReturn },
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
                    onSettings = {
                        settingsReturn = "home"
                        route = "settings"
                    },
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
                    onProjects = {
                        projectsReturn = "home"
                        route = "projects"
                    },
                    projectName = state.projectName,
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
                    sessionsLoading = state.sessionsLoading,
                    sessionsError = state.sessionsError,
                )
                    }             // close when
                }                 // close SaveableStateProvider
            }                     // close else
        }
    }

    /**
     * Re-read the open session's stored state whenever the Activity returns to
     * the foreground. A run that finished while we were backgrounded may have
     * emitted its terminal event before this ViewModel re-subscribed, so this
     * clears a possibly wedged `busy` from the store. Read-only and a no-op
     * when no chat is open.
     */
    /**
     * Point the launcher icon at the palette's activity-alias. Android only lets
     * an app change its home-screen icon by toggling the enabled alias, so keep
     * exactly one enabled and switch the rest off. Best-effort and idempotent:
     * a launcher that caches the icon simply updates on its next refresh.
     */
    private fun syncLauncherAlias(palette: String) {
        val target = LAUNCHER_ALIASES[palette] ?: LAUNCHER_ALIASES.getValue("abyss")
        for (alias in LAUNCHER_ALIASES.values) {
            val component = ComponentName(this, alias)
            val wanted = if (alias == target) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            if (packageManager.getComponentEnabledSetting(component) == wanted) continue
            runCatching {
                packageManager.setComponentEnabledSetting(
                    component,
                    wanted,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.reconcileOpenSession()
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

/** Palette id -> launcher activity-alias that carries that palette's icon. */
private val LAUNCHER_ALIASES = mapOf(
    "prism" to "dev.lumen.app.LauncherPrism",
    "ember" to "dev.lumen.app.LauncherEmber",
    "phosphor" to "dev.lumen.app.LauncherPhosphor",
    "abyss" to "dev.lumen.app.LauncherAbyss",
)

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
private fun animatedColors(palette: String, dark: Boolean, reduceMotion: Boolean = false): LumenColors {
    val target = LumenColors.forId(palette, dark)
    // Reduced motion: jump straight to the target instead of a 420 ms cross-fade.
    if (reduceMotion) return target
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
