package dev.lumen.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lumen.app.data.KeyStore
import dev.lumen.app.data.ProviderCatalogue
import dev.lumen.app.platform.AndroidEnvironment
import dev.lumen.app.platform.AndroidShellExecutor
import dev.lumen.app.platform.AndroidTerminal
import dev.lumen.app.platform.DebianEnvironment
import dev.lumen.app.platform.PerfSampler
import dev.lumen.app.platform.PerfSummary
import dev.lumen.app.platform.RunService
import dev.lumen.app.platform.WorkspaceWatcher
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.StepMapper
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.ToolResult
import dev.spindle.core.model.ToolState
import dev.spindle.core.model.Usage
import dev.spindle.core.store.RevertResult
import dev.spindle.core.store.Reverter
import dev.spindle.core.store.SnapshotStore
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.tool.HostShellExecutor
import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.agent.ContextBudget
import dev.spindle.core.agent.PermissionGate
import dev.spindle.core.agent.QuestionGate
import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.DeltaKind
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.model.TodoItem
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.refs.ReferenceResolver
import dev.spindle.core.store.SearchHit
import dev.spindle.core.store.SessionSearch
import dev.spindle.core.store.SessionStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap

/** One row on the home screen. */
data class SessionRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** Normalized tags on the session, oldest first; used for chips and filtering. */
    val tags: List<String> = emptyList(),
)

/**
 * The file shown in the peek sheet. [lines] is UTF-8, split without a trailing
 * newline artifact, and capped at [MAX_PEEK_LINES] (see [truncated]); [highlight]
 * is the 1-based line the reference pointed at, or null.
 */
data class FilePeek(
    val path: String,
    val lines: List<String>,
    val highlight: Int? = null,
    val truncated: Boolean = false,
)

/**
 * An image the user queued to send with the next prompt. [base64] is the raw
 * inline payload (no data-URL prefix); [mime] is what the provider adapter
 * needs to label it.
 */
data class PendingImage(
    val name: String,
    val mime: String,
    val base64: String,
)

/**
 * One earlier step in the current session that references a file. [stepIndex]
 * indexes [ChatState.steps] so the peek sheet can jump the timeline to it;
 * [touched] is true when the same file also appears in the run's changes.
 */
data class Backlink(
    val stepIndex: Int,
    val label: String,
    val summary: String,
    val touched: Boolean,
)

/** One row in the project file browser. [path] is workspace-relative, `/`-joined. */
data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
)

/** The current folder listing. [dir] is workspace-relative; "" is the root. */
data class FilesState(
    val dir: String,
    val entries: List<FileEntry>,
    val loading: Boolean = false,
    val error: String? = null,
)

/** The file open in the editor, capped at [ChatViewModel.MAX_PEEK_LINES]. */
data class EditorState(
    val path: String,
    val lines: List<String>,
    val truncated: Boolean = false,
    val error: String? = null,
)

/**
 * The interactive shell's retained output. [lines] are raw UTF-8 chunks in
 * arrival order (the screen joins and splits them); [running] is false once the
 * process has exited or been stopped.
 */
data class TerminalState(
    val lines: List<String> = emptyList(),
    val running: Boolean = false,
    val error: String? = null,
)

/** One measured area of app storage. [bytes] is always non-negative. */
data class StorageCategory(
    val name: String,
    val bytes: Long,
    val clearable: Boolean,
)

/**
 * The storage breakdown. [scanning] is true while a scan is in flight so the
 * screen can show progress without blanking the previous numbers.
 */
data class StorageReport(
    val total: Long,
    val categories: List<StorageCategory>,
    val scanning: Boolean,
)

/** One timestamped diagnostics line. [at] is epoch milliseconds. */
data class DiagLine(val at: Long, val text: String)

/** Alpine/Debian readiness plus the in-flight install state for the UI. */
data class LinuxEnvironmentState(
    val alpineReady: Boolean = false,
    val debianReady: Boolean = false,
    val debianActive: Boolean = false,
    val installing: Boolean = false,
    val progress: String? = null,
)

/**
 * A gate prompt waiting on the user. [Permission] maps to the approval policy's
 * ASK branch; [Question] maps to the `question` tool. Each carries its own id so
 * the UI can key selection state and the view model can resolve the right wait.
 */
sealed interface PendingAsk {
    val id: String

    data class Permission(
        override val id: String,
        val tool: String,
        val detail: String,
        val pattern: String?,
    ) : PendingAsk

    data class Question(
        override val id: String,
        val question: String,
        val options: List<String>,
        val multiple: Boolean,
    ) : PendingAsk
}

/** What the screens need to draw, in one immutable snapshot. */
data class ChatState(
    val steps: List<UiStep> = emptyList(),
    val busy: Boolean = false,
    val input: String = "",
    val error: String? = null,
    val model: String = "",
    val provider: String = "opencode-go",
    /** True until an API key is saved — the app shows the key form instead. */
    val needsKey: Boolean = true,
    /** Recent chats, newest first. */
    val sessions: List<SessionRow> = emptyList(),

    /** Home shows archived sessions when this is on (default hides them). */
    val showArchived: Boolean = false,
    /**
     * Active tag filters on Home. A session matches when it carries ANY of
     * these tags (OR, not AND), so adding a tag widens the listing.
     */
    val tagFilter: Set<String> = emptySet(),
    /** Every tag present on a loaded session, sorted, for the filter bar. */
    val availableTags: List<String> = emptyList(),
    /** Full-text hits for [searchQuery]; null when no search is active. */
    val search: List<SearchHit>? = null,
    /** The text currently in the home search field. */
    val searchQuery: String = "",
    /** Active primary agent for new prompts: "build" | "plan". */
    val agentMode: String = "build",
    /** The chat currently open, or null on the home screen. */
    val currentSessionId: String? = null,
    /** "system" | "light" | "dark". */
    val theme: String = "system",
    /** Running session token/cost totals, rolled up from each message. */
    val usage: Usage = Usage(),

    /** Per-session cost ceiling in USD; 0 means unlimited. */
    val maxCostUsd: Double = 0.0,
    /** Structured file changes the agent made in this session. */
    val changes: RunChanges = RunChanges.EMPTY,
    /** The current session's live todo list, refreshed on each rebuild. */
    val todos: List<TodoItem> = emptyList(),
    /** The file currently shown in the peek sheet, or null when closed. */
    val peek: FilePeek? = null,
    /** The project file browser's current folder, or null until opened. */
    val files: FilesState? = null,
    /** The file open in the files editor, or null when closed. */
    val editor: EditorState? = null,
    /** The interactive shell's output and lifecycle flag. */
    val terminal: TerminalState = TerminalState(),
    /** A permission or question prompt awaiting the user, or null when idle. */
    val ask: PendingAsk? = null,
    /** Whether tools are confirmed interactively before running. */
    val askBeforeTools: Boolean = false,
    /** The self-contained HTML page shown in the canvas, or null when closed. */
    val canvas: String? = null,
    /** Workspace-relative name of the page in the canvas, for the top bar. */
    val canvasPath: String? = null,
    /** Images queued to send with the next prompt. */
    val attachments: List<PendingImage> = emptyList(),
    /** A soft, non-fatal notice shown above the composer (e.g. no vision). */
    val hint: String? = null,
    /** The last storage scan, or null before the first one. */
    val storage: StorageReport? = null,
    /** Timestamped diagnostics, oldest first, capped at [MAX_DIAG_LINES]. */
    val diag: List<DiagLine> = emptyList(),
    /** Alpine/Debian readiness and the Debian install progress. */
    val linux: LinuxEnvironmentState = LinuxEnvironmentState(),
)

/**
 * Owns the agent loop, the session store and the navigation-relevant state.
 * One process, many sessions: the store is the source of truth and the screens
 * render what it says.
 */
class ChatViewModel(
    private val workspace: Path,
    private val keys: KeyStore,
    private val store: SessionStore,
    private val snapshots: SnapshotStore? = null,
    private val shell: ShellExecutor? = null,
    private val context: Context? = null,
    private val environment: AndroidEnvironment? = null,
    private val debian: DebianEnvironment? = null,
    /** Dispatcher for storage scans, cache clears and the Debian install. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The process-wide scope a run is launched in. Production passes the
     * Application scope so destroying the Activity cannot cancel a live run;
     * tests (and any headless host) fall back to [viewModelScope].
     */
    private val runScope: CoroutineScope? = null,
    /** Test seam: overrides the provider registry so a scripted provider can run. */
    private val registryFactory: ((provider: String, key: String) -> SimpleProviderRegistry)? = null,
    /** How long session search waits for more keystrokes; 0 lands inline in tests. */
    private val searchDebounceMs: Long = SEARCH_DEBOUNCE_MS,
    /**
     * Process-wide event bus. Production passes the Application's bus so a run
     * that outlives this ViewModel still feeds whichever ViewModel is open when
     * the user returns; tests default to a private bus.
     */
    eventBus: EventBus = EventBus(),
    /**
     * Process-wide ids of sessions with a live in-process run. [reconcileOrphanRuns]
     * must not treat one of these as a stale orphan, or reopening the Activity
     * mid-run would clobber the live run's persisted state.
     */
    private val runningSessions: MutableSet<String>? = null,
    /** Test seam: overrides the frame sampler so teardown can be asserted. */
    private val perf: PerfSampler = PerfSampler(),
) : ViewModel() {

    private val bus: EventBus = eventBus

    private val _state = MutableStateFlow(
        ChatState(
            model = keys.model,
            provider = keys.provider,
            needsKey = !keys.hasKey,
            theme = keys.theme,
            askBeforeTools = keys.askBeforeTools,
            agentMode = keys.agentMode,
            maxCostUsd = keys.maxCostUsd,
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var runJob: Job? = null

    /**
     * The live streaming accumulator. Text deltas are appended to per-part
     * [StringBuilder]s here and only materialized into [ChatState.steps] on a
     * throttled cadence, so a long stream never re-concatenates its whole body
     * or copies the whole row list once per token. Every non-delta step mutation
     * goes through it too ([StepMapper.DeltaBuffer.transform]/[reset]/[rebase]),
     * so buffered text is never lost.
     */
    private val stream = StepMapper.DeltaBuffer()

    /**
     * Bumped whenever the timeline is replaced wholesale (session open/new,
     * rewind, close). A rebuild that read the store before such a replacement
     * must not publish its now-stale snapshot over the fresh rows.
     */
    private var timelineGeneration = 0

    /** Scheduled delta flush; coalesces a burst of tokens into one state publish. */
    private var deltaFlushJob: Job? = null

    /** Coalesces rebuild requests while one is in flight (see [RebuildCoalescer]). */
    private val rebuilds = RebuildCoalescer()

    /** The session id of the in-flight run, so [stop] can clear its live marker. */
    private var runningSessionId: String? = null

    /**
     * Bumped whenever a run starts or is superseded. A finishing coroutine only
     * touches shared state (busy flag, service, watcher) when its generation is
     * still current, so a stop()/new send() cannot be clobbered by the old run's
     * `finally`.
     */
    private var runGeneration = 0

    /**
     * The run generation that currently owns [perf], or 0 when none. Guarded by
     * [perfLock]; lets a superseded run's teardown refuse to stop the sampler
     * that a newer run already started.
     */
    private val perfLock = Any()
    private var perfGeneration = 0

    /** Bumped on every session switch so a slow `openSession` cannot land stale. */
    private var sessionOpenToken = 0

    /** In-flight debounced search, cancelled when a newer query arrives. */
    private var searchJob: Job? = null

    /** The one interactive shell behind the terminal screen. */
    private val terminal = AndroidTerminal(workspace.toFile(), shell)

    /**
     * Polls the workspace during a run so files changed by a script the agent
     * ran (not through the app's edit tools) still land in the Changes view.
     * Best-effort: a sandbox without a context runs without it.
     */
    private val watcher: WorkspaceWatcher? = context?.let {
        WorkspaceWatcher(workspace.toFile(), runScope ?: viewModelScope) { changed -> absorbIndirect(changed) }
    }

    /**
     * Start the frame sampler and record which run owns it, so a superseded
     * run's teardown can tell whether the live sampler is still its own.
     */
    private fun startPerf(generation: Int) {
        synchronized(perfLock) { perfGeneration = generation }
        runCatching { perf.start() }
    }

    /**
     * Stop the sampler only if [generation] still owns it. A superseding run
     * owns the sampler now, so the old run's `finally` must not stop it.
     */
    private fun stopPerf(generation: Int): PerfSummary? {
        val owned = synchronized(perfLock) {
            if (perfGeneration == generation) {
                perfGeneration = 0
                true
            } else {
                false
            }
        }
        return if (owned) runCatching { perf.stop() }.getOrNull() else null
    }

    /**
     * Stop the live sampler if a run owns one. [stop]/[onCleared] are
     * authoritative for the whole ViewModel, so they do not care which run it
     * is — only that one is live (a no-op otherwise, avoiding a duplicate
     * summary).
     */
    private fun stopPerfNow(): PerfSummary? {
        val owned = synchronized(perfLock) {
            if (perfGeneration != 0) {
                perfGeneration = 0
                true
            } else {
                false
            }
        }
        return if (owned) runCatching { perf.stop() }.getOrNull() else null
    }

    /** Record a sampler summary in diagnostics when a run actually sampled frames. */
    private fun reportPerf(summary: PerfSummary?) {
        summary?.let { if (it.frames > 0) diag(it.line()) }
    }

    /** Record externally-changed paths as change rows the user can see and open. */
    private fun absorbIndirect(paths: Set<String>) {
        val current = _state.value
        val sid = current.currentSessionId?.let { SessionId(it) } ?: return
        if (!current.busy) return
        val known = current.changes.byFile().keys
        val fresh = paths.filter { it !in known }
        if (fresh.isEmpty()) return
        var next = current.changes
        for (path in fresh) {
            next += FileEdit(id = Ids.new("chg"), sessionId = sid, path = path, at = System.currentTimeMillis())
        }
        _state.value = current.copy(changes = next.bounded())
    }

    // ---- interactive gates ----
    //
    // At most one ask is "active" (the one the UI shows). The gates suspend on a
    // per-entry CompletableDeferred; if a second request arrives while one is on
    // screen it is queued FIFO and promoted once the active one is answered.

    private sealed interface PendingEntry {
        val ask: PendingAsk
    }

    private class PermissionEntry(
        override val ask: PendingAsk.Permission,
        val deferred: CompletableDeferred<Boolean>,
    ) : PendingEntry

    private class QuestionEntry(
        override val ask: PendingAsk.Question,
        val deferred: CompletableDeferred<List<String>>,
    ) : PendingEntry

    private val queuedAsks = ArrayDeque<PendingEntry>()
    private var activeAsk: PendingEntry? = null

    private val permissionGate = PermissionGate { tool, detail, pattern ->
        val entry = PermissionEntry(
            PendingAsk.Permission(Ids.new("ask"), tool, detail, pattern),
            CompletableDeferred(),
        )
        enqueueAsk(entry)
        entry.deferred.await()
    }

    private val questionGate = QuestionGate { _, question, options, multiple ->
        val entry = QuestionEntry(
            PendingAsk.Question(Ids.new("ask"), question, options, multiple),
            CompletableDeferred(),
        )
        enqueueAsk(entry)
        entry.deferred.await()
    }

    @Synchronized
    private fun enqueueAsk(entry: PendingEntry) {
        queuedAsks.addLast(entry)
        if (activeAsk == null) promoteAskLocked()
    }

    @Synchronized
    private fun promoteAskLocked() {
        activeAsk = queuedAsks.removeFirstOrNull()
        _state.value = _state.value.copy(ask = activeAsk?.ask)
    }

    /** Retire [entry] if it is the active one and show the next queued ask. */
    @Synchronized
    private fun advanceAsk(entry: PendingEntry) {
        if (activeAsk !== entry) return
        promoteAskLocked()
    }

    /** Resolve the visible permission prompt; [always] remembers the scope key. */
    fun answerPermission(allow: Boolean, always: Boolean = false) {
        val entry = activeAsk as? PermissionEntry ?: return
        if (always && allow) {
            val scope = entry.ask.pattern?.takeIf { it.isNotBlank() } ?: entry.ask.tool
            keys.allowedPatterns = keys.allowedPatterns + scope
        }
        advanceAsk(entry)
        entry.deferred.complete(allow)
    }

    /** Resolve the visible question with [selected] (possibly multiple) options. */
    fun answerQuestion(selected: List<String>) {
        val entry = activeAsk as? QuestionEntry ?: return
        advanceAsk(entry)
        entry.deferred.complete(selected)
    }

    /** Skip the visible question: fall back to its first option, else nothing. */
    fun skipQuestion() {
        val entry = activeAsk as? QuestionEntry ?: return
        val fallback = entry.ask.options.firstOrNull()?.let { listOf(it) } ?: emptyList()
        advanceAsk(entry)
        entry.deferred.complete(fallback)
    }

    /** Toggle interactive approval and persist it. */
    fun setAskBeforeTools(on: Boolean) {
        keys.askBeforeTools = on
        _state.value = _state.value.copy(askBeforeTools = on)
    }

    /** Set the per-session cost ceiling in USD (0 = unlimited) and persist it. */
    fun setMaxCost(usd: Double) {
        val value = if (usd.isFinite() && usd > 0) usd else 0.0
        keys.maxCostUsd = value
        _state.value = _state.value.copy(maxCostUsd = value)
    }

    /** Drop every pending prompt and wake its waiter with cancellation. */
    @Synchronized
    private fun clearAsks() {
        val stale = ArrayList<PendingEntry>()
        activeAsk?.let { stale.add(it) }
        stale.addAll(queuedAsks)
        activeAsk = null
        queuedAsks.clear()
        _state.value = _state.value.copy(ask = null)
        for (entry in stale) {
            when (entry) {
                is PermissionEntry -> entry.deferred.cancel()
                is QuestionEntry -> entry.deferred.cancel()
            }
        }
    }

    /** Diagnostics are appended from several threads, so the ring is guarded. */
    private val diagLines = ArrayDeque<DiagLine>()
    private val diagLock = Any()

    init {
        viewModelScope.launch { refreshSessions() }
        viewModelScope.launch { collectEvents() }
        viewModelScope.launch { reconcileOrphanRuns() }
        refreshLinuxEnvironment()
    }

    /**
     * A run cannot outlive its process in this app, so any session still marked
     * RUNNING on a cold start is stale and is replayed as idle. Without this, a
     * killed run would reopen as permanently "busy". Reconciliation also has to
     * repair the persisted timeline: a tool part left PENDING/RUNNING would spin
     * forever, so it is rewritten as an aborted ERROR result and any open
     * assistant message is finalized.
     */
    private suspend fun reconcileOrphanRuns() {
        runCatching {
            val live = runningSessions ?: emptySet()
            val stale = store.sessions(limit = Int.MAX_VALUE, includeChildren = true, includeArchived = true)
                .filter { it.state == SessionState.RUNNING && it.id.value !in live }
            for (session in stale) {
                abortStaleRun(session.id)
                store.updateSession(session.copy(state = SessionState.IDLE))
            }
        }
    }

    /** Rewrite a stale run's in-flight tool parts to ERROR and close open messages. */
    private suspend fun abortStaleRun(sid: SessionId) {
        val messages = runCatching { store.messages(sid) }.getOrDefault(emptyList())
        for (message in messages) {
            var rewroteTool = false
            val parts = message.parts.map { part ->
                if (part is Part.Tool && (part.state == ToolState.PENDING || part.state == ToolState.RUNNING)) {
                    rewroteTool = true
                    part.copy(
                        state = ToolState.ERROR,
                        result = ToolResult(part.call.id, "aborted", isError = true),
                    )
                } else {
                    part
                }
            }
            val openAssistant = message.role == Role.ASSISTANT && message.finish == null
            if (!rewroteTool && !openAssistant) continue
            store.updateMessage(
                message.copy(
                    parts = parts,
                    finish = if (openAssistant) FinishReason.ERROR else message.finish,
                    error = message.error ?: if (openAssistant) "aborted" else null,
                ),
            )
        }
    }

    // ---- diagnostics ----

    /** Append one diagnostics line (name/timing only — never tool arguments). */
    private fun diag(text: String) {
        val snapshot = synchronized(diagLock) {
            diagLines.addLast(DiagLine(System.currentTimeMillis(), text))
            while (diagLines.size > MAX_DIAG_LINES) diagLines.removeFirst()
            diagLines.toList()
        }
        _state.value = _state.value.copy(diag = snapshot)
    }

    /** Test/teardown seam: record a diagnostics line without a full run. */
    internal fun recordDiagnostic(text: String) = diag(text)

    /** Drop every recorded diagnostics line. */
    fun clearDiagnostics() {
        synchronized(diagLock) { diagLines.clear() }
        _state.value = _state.value.copy(diag = emptyList())
    }

    // ---- storage manager ----

    /** Recompute the storage breakdown; always runs on [io], never the main thread. */
    fun scanStorage() {
        _state.value = _state.value.copy(
            storage = (_state.value.storage ?: StorageReport(0, emptyList(), true)).copy(scanning = true),
        )
        viewModelScope.launch(io) { publishStorage(measureStorage(scanDeadline())) }
    }

    /**
     * Remove ONLY regenerable caches: the app cache dir, the Alpine apk/tmp
     * caches and the Debian apt/tmp/cache dirs. Project source, the lumen.db
     * stores, snapshots and keys are never touched. Re-scans afterwards.
     */
    fun clearCache() {
        _state.value = _state.value.copy(
            storage = (_state.value.storage ?: StorageReport(0, emptyList(), true)).copy(scanning = true),
        )
        viewModelScope.launch(io) {
            runCatching { safeCacheTargets().forEach { deleteTree(it) } }
            publishStorage(measureStorage(scanDeadline()))
        }
    }

    /** Clear only the safe part of [name]; unknown or kept categories are a no-op. */
    fun clearStorageCategory(name: String) {
        val targets = safeTargetsFor(name) ?: return
        _state.value = _state.value.copy(
            storage = (_state.value.storage ?: StorageReport(0, emptyList(), true)).copy(scanning = true),
        )
        viewModelScope.launch(io) {
            runCatching { targets.forEach { deleteTree(it) } }
            publishStorage(measureStorage(scanDeadline()))
        }
    }

    private fun publishStorage(report: StorageReport) {
        _state.value = _state.value.copy(storage = report.copy(scanning = false))
    }

    private fun scanDeadline(): Long = System.currentTimeMillis() + SCAN_BUDGET_MS

    /** Walk the real trees and label them. Pure; call off the main thread. */
    private fun measureStorage(deadline: Long): StorageReport {
        val categories = ArrayList<StorageCategory>()
        categories += StorageCategory(STORAGE_WORKSPACE, sizeOf(workspace.toFile(), deadline), false)
        environment?.let { categories += StorageCategory(STORAGE_ALPINE, sizeOf(it.rootfs, deadline), true) }
        debian?.let { categories += StorageCategory(STORAGE_DEBIAN, sizeOf(it.rootfs, deadline), true) }
        environment?.let { categories += StorageCategory(STORAGE_WRAPPERS, sizeOf(it.wrappersDir, deadline), false) }
        context?.let { ctx ->
            categories += StorageCategory(STORAGE_STORES, storeBytes(ctx), false)
            categories += StorageCategory(STORAGE_LOGS, sizeOfLogs(ctx, deadline), true)
            categories += StorageCategory(STORAGE_CACHE, sizeOf(ctx.cacheDir, deadline), true)
        }
        return StorageReport(categories.sumOf { it.bytes }, categories, false)
    }

    /** The safe targets behind the "all caches" clear. */
    private fun safeCacheTargets(): List<File> = buildList {
        context?.cacheDir?.let { add(it) }
        environment?.let {
            add(File(it.rootfs, "var/cache/apk"))
            add(File(it.rootfs, "tmp"))
        }
        debian?.let {
            val root = it.rootfs
            add(File(root, "var/cache/apt/archives"))
            add(File(root, "var/lib/apt/lists"))
            add(File(root, "tmp"))
            add(File(root, "root/.cache"))
            add(File(root, "root/.npm"))
            add(it.tmpDir)
        }
    }

    /** Per-category clear; returns null for anything not safe to clear. */
    private fun safeTargetsFor(name: String): List<File>? = when (name) {
        STORAGE_CACHE -> context?.let { listOf(it.cacheDir) }
        STORAGE_LOGS -> context?.let { ctx ->
            buildList {
                ctx.filesDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".log") }
                    ?.forEach { add(it) }
                val logsDir = File(ctx.filesDir, "logs")
                if (logsDir.isDirectory) add(logsDir)
            }
        }
        STORAGE_ALPINE -> environment?.let {
            listOf(File(it.rootfs, "var/cache/apk"), File(it.rootfs, "tmp"))
        }
        STORAGE_DEBIAN -> debian?.let {
            val root = it.rootfs
            listOf(
                File(root, "var/cache/apt/archives"),
                File(root, "var/lib/apt/lists"),
                File(root, "tmp"),
                File(root, "root/.cache"),
                File(root, "root/.npm"),
                it.tmpDir,
            )
        }
        else -> null
    }

    /** Sum the `lumen.db*` store files (main DB plus its WAL/SHM siblings). */
    private fun storeBytes(ctx: Context): Long =
        ctx.filesDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("lumen.db") }
            ?.sumOf { it.length() } ?: 0

    /** Top-level `*.log` files in filesDir plus a `logs` folder when present. */
    private fun sizeOfLogs(ctx: Context, deadline: Long): Long {
        var total = ctx.filesDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".log") }
            ?.sumOf { it.length() } ?: 0
        val logsDir = File(ctx.filesDir, "logs")
        if (logsDir.isDirectory) total += sizeOf(logsDir, deadline)
        return total
    }

    /** Bounded, symlink-skipping recursive size. Never throws. */
    private fun sizeOf(f: File, deadline: Long): Long =
        sizeOf(f, 0, deadline, HashSet())

    private fun sizeOf(f: File, depth: Int, deadline: Long, seen: MutableSet<String>): Long {
        if (depth > MAX_SCAN_DEPTH || System.currentTimeMillis() > deadline) return 0
        return try {
            if (Files.isSymbolicLink(f.toPath())) return 0
            when {
                f.isFile -> f.length()
                !f.isDirectory -> 0L
                else -> {
                    val canonical = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
                    if (seen.size > MAX_SCAN_NODES || !seen.add(canonical)) return 0
                    f.listFiles()?.sumOf { sizeOf(it, depth + 1, deadline, seen) } ?: 0L
                }
            }
        } catch (t: Throwable) {
            0L
        }
    }

    /** Recursive delete that never follows symlinks. Never throws. */
    private fun deleteTree(f: File) {
        try {
            if (Files.isSymbolicLink(f.toPath())) {
                f.delete()
                return
            }
            if (f.isDirectory) f.listFiles()?.forEach { deleteTree(it) }
            f.delete()
        } catch (ignored: Throwable) {
        }
    }

    // ---- linux environment ----

    /** Re-read Alpine/Debian readiness off the main thread. */
    fun refreshLinuxEnvironment() {
        // With no environment wired (unit-test hosts), there is nothing to
        // probe: skip the background write so state stays synchronous.
        val env = environment
        val deb = debian
        if (env == null && deb == null) return
        viewModelScope.launch(io) {
            _state.value = _state.value.copy(
                linux = _state.value.linux.copy(
                    alpineReady = env?.ready() ?: false,
                    debianReady = deb?.ready() ?: false,
                    debianActive = deb?.active() ?: false,
                ),
            )
        }
    }

    /**
     * Explicit opt-in Debian install. Never auto-downloads; runs on [io] with
     * per-step progress reported into both [linux] and [diag]. On success the
     * state is refreshed and the proot probe re-run.
     */
    fun installDebian() {
        val env = debian ?: return
        if (_state.value.linux.installing) return
        _state.value = _state.value.copy(
            linux = _state.value.linux.copy(installing = true, progress = "starting…"),
        )
        diag("debian install: requested")
        viewModelScope.launch(io) {
            try {
                val ok = env.install { message ->
                    diag("debian install: $message")
                    _state.value = _state.value.copy(linux = _state.value.linux.copy(progress = message))
                }
                if (ok) {
                    runCatching { env.probe() }
                    diag("debian install: complete")
                } else {
                    diag("debian install: not active — alpine stays the shell")
                }
            } catch (t: Throwable) {
                diag("debian install failed: " + (t.message ?: t.toString()))
            } finally {
                _state.value = _state.value.copy(
                    linux = _state.value.linux.copy(
                        alpineReady = environment?.ready() ?: false,
                        debianReady = env.ready(),
                        debianActive = env.active(),
                        installing = false,
                        progress = null,
                    ),
                )
            }
        }
    }

    /** Best description of the shell the next run will use, for [diag]. */
    private fun shellKind(): String = when {
        debian?.active() == true -> "debian (proot)"
        environment?.ready() == true -> "alpine"
        environment != null -> "alpine (installs on first use)"
        else -> "system shell (fallback)"
    }

    // ---- key + settings ----

    /** Called by the key screen. Persists the key and re-enables the app. */
    fun saveKey(provider: String, key: String) {
        // Only reset the model when the provider actually changed, so a model
        // chosen in Settings survives a key re-entry.
        if (keys.provider != provider || keys.apiKey.isNullOrBlank()) {
            keys.provider = provider
            keys.model = KeyStore.defaultModel(provider)
        }
        keys.provider = provider
        keys.apiKey = key
        _state.value = _state.value.copy(
            needsKey = !keys.hasKey,
            model = keys.model,
            provider = keys.provider,
            error = null,
        )
    }

    fun clearKey() {
        keys.apiKey = null
        closeChat()
        _state.value = _state.value.copy(needsKey = true, error = null)
    }

    fun setProvider(provider: String) {
        keys.provider = provider
        keys.model = KeyStore.defaultModel(provider)
        _state.value = _state.value.copy(provider = provider, model = keys.model)
    }

    fun setModel(model: String) {
        keys.model = model
        _state.value = _state.value.copy(model = model)
    }

    fun setTheme(theme: String) {
        keys.theme = theme
        _state.value = _state.value.copy(theme = theme)
    }

    /** Switch the active primary agent (build/plan) and persist the choice. */
    fun setAgentMode(mode: String) {
        keys.agentMode = mode
        _state.value = _state.value.copy(agentMode = mode)
    }

    // ---- sessions ----

    fun newChat() {
        val token = ++sessionOpenToken
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val id = SessionId(Ids.new("ses"))
            store.createSession(
                Session(
                    id = id,
                    title = "new chat",
                    cwd = workspace.toString(),
                    createdAt = now,
                    updatedAt = now,
                    model = keys.model.substringAfter('/', keys.model),
                    providerId = keys.model.substringBefore('/', "opencode-go"),
                ),
            )
            if (token != sessionOpenToken) return@launch
            resetSessionCaches()
            replaceTimeline(emptyList())
            _state.value = _state.value.copy(
                currentSessionId = id.value,
                steps = emptyList(),
                input = "",
                error = null,
                hint = null,
                attachments = emptyList(),
                canvas = null,
                canvasPath = null,
                usage = Usage(),
                changes = RunChanges.EMPTY,
                todos = emptyList(),
            )
            refreshSessions()
        }
    }

    fun openSession(id: String) {
        // A newer navigation supersedes this load, so open A then B can never
        // apply A's messages under B's id.
        val token = ++sessionOpenToken
        viewModelScope.launch {
            val sid = SessionId(id)
            val messages = store.messages(sid)
            val session = store.session(sid)
            if (token != sessionOpenToken) return@launch
            // Drop the previous session's subagent caches *before* enrich, so
            // the new session's own children are cached, not immediately wiped.
            resetSessionCaches()
            val steps = enrich(StepMapper.fromMessages(messages))
            replaceTimeline(steps)
            _state.value = _state.value.copy(
                currentSessionId = id,
                steps = steps,
                input = "",
                error = null,
                busy = session?.state == SessionState.RUNNING,
                usage = messages.fold(Usage()) { acc, m -> acc + m.usage },
                changes = RunChanges.EMPTY,
                todos = loadTodos(sid),
                // Restore a gate prompt that was hidden by navigating away mid-run.
                ask = activeAsk?.ask,
            )
        }
    }

    /**
     * Leave the chat screen. This resets UI state ONLY: it must never cancel a
     * live run or drop a gate the run is waiting on, because the run is owned by
     * the process scope and continues in the background.
     */
    fun closeChat() {
        sessionOpenToken++
        resetSessionCaches()
        replaceTimeline(emptyList())
        _state.value = _state.value.copy(
            currentSessionId = null,
            steps = emptyList(),
            busy = false,
            error = null,
            hint = null,
            attachments = emptyList(),
            canvas = null,
            canvasPath = null,
            usage = Usage(),
            changes = RunChanges.EMPTY,
            todos = emptyList(),
            peek = null,
            ask = null,
        )
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            store.deleteSession(SessionId(id))
            if (_state.value.currentSessionId == id) closeChat()
            refreshSessions()
        }
    }

    /** Pin/unpin a session; pinned rows sort above the rest on Home. */
    fun setPinned(id: String, pinned: Boolean) {
        viewModelScope.launch {
            val session = store.session(SessionId(id)) ?: return@launch
            store.updateSession(session.copy(pinned = pinned))
            refreshSessions()
        }
    }

    /**
     * Archive/unarchive a session. Archiving hides it from Home (unless the
     * "archived" toggle is on) and closes it if it is the open chat.
     */
    fun setArchived(id: String, archived: Boolean) {
        viewModelScope.launch {
            val session = store.session(SessionId(id)) ?: return@launch
            store.updateSession(session.copy(archived = archived))
            if (archived && _state.value.currentSessionId == id) closeChat()
            refreshSessions()
        }
    }

    /** Rename a session; a blank title is ignored. */
    fun renameSession(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            val session = store.session(SessionId(id)) ?: return@launch
            store.updateSession(session.copy(title = clean))
            refreshSessions()
        }
    }

    /**
     * Add [tag] to session [id]. Tags are normalized (trimmed, lowercased),
     * blank input is ignored, duplicates collapse, the tag is capped at
     * [MAX_TAG_LENGTH] and each session at [MAX_TAGS_PER_SESSION].
     */
    fun addSessionTag(id: String, tag: String) {
        val clean = normalizeTag(tag) ?: return
        viewModelScope.launch {
            val session = store.session(SessionId(id)) ?: return@launch
            val current = normalizedTags(session.tags)
            if (clean in current || current.size >= MAX_TAGS_PER_SESSION) return@launch
            store.updateSession(session.copy(tags = current + clean))
            refreshSessions()
        }
    }

    /** Remove [tag] from session [id]; a tag the session does not carry is a no-op. */
    fun removeSessionTag(id: String, tag: String) {
        val clean = normalizeTag(tag) ?: return
        viewModelScope.launch {
            val session = store.session(SessionId(id)) ?: return@launch
            val current = normalizedTags(session.tags)
            if (clean !in current) return@launch
            store.updateSession(session.copy(tags = current - clean))
            refreshSessions()
        }
    }

    /** Toggle [tag] in the Home filter; blank input is ignored. */
    fun toggleTagFilter(tag: String) {
        val clean = normalizeTag(tag) ?: return
        val current = _state.value.tagFilter
        val next = if (clean in current) current - clean else current + clean
        _state.value = _state.value.copy(tagFilter = next)
        viewModelScope.launch { refreshSessions() }
    }

    /** Drop every active tag filter. */
    fun clearTagFilters() {
        if (_state.value.tagFilter.isEmpty()) return
        _state.value = _state.value.copy(tagFilter = emptySet())
        viewModelScope.launch { refreshSessions() }
    }

    /** Show or hide archived sessions on Home. */
    fun setShowArchived(on: Boolean) {
        _state.value = _state.value.copy(showArchived = on)
        viewModelScope.launch { refreshSessions() }
    }

    /**
     * Fork [id] at its head (all messages) into a fresh child session and open
     * it. Stores that cannot fork return null and nothing changes.
     */
    fun forkSession(id: String) {
        viewModelScope.launch {
            val newId = SessionId(Ids.new("ses"))
            val fork = store.forkSession(SessionId(id), null, newId)
            if (fork != null) {
                refreshSessions()
                openSession(fork.id.value)
            }
        }
    }

    /**
     * Drop every message after [messageId] in the current session, then rebuild
     * the timeline from what the store now holds.
     */
    fun rewindTo(messageId: String) {
        val current = _state.value.currentSessionId ?: return
        viewModelScope.launch {
            val sid = SessionId(current)
            store.rewind(sid, MessageId(messageId))
            val messages = store.messages(sid)
            val steps = enrich(StepMapper.fromMessages(messages))
            replaceTimeline(steps)
            _state.value = _state.value.copy(
                steps = steps,
                usage = messages.fold(Usage()) { acc, m -> acc + m.usage },
                changes = RunChanges.EMPTY,
                error = null,
            )
            val todos = loadTodos(sid)
            _state.value = _state.value.copy(todos = todos)
        }
    }

    /**
     * Full-text search over the store, when it advertises [SessionSearch]. A
     * blank query clears the results; a store without search is a no-op for the
     * results (the query text still tracks the field).
     */
    fun searchSessions(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        searchJob?.cancel()
        val searchable = store as? SessionSearch ?: return
        if (query.isBlank()) {
            _state.value = _state.value.copy(search = null)
            return
        }
        searchJob = viewModelScope.launch {
            if (searchDebounceMs > 0) delay(searchDebounceMs)
            val hits = runCatching { searchable.search(query) }.getOrDefault(emptyList())
            if (_state.value.searchQuery == query) {
                _state.value = _state.value.copy(search = hits)
            }
        }
    }

    private suspend fun refreshSessions() {
        val rows = store.sessions(includeArchived = _state.value.showArchived)
            .sortedWith(compareByDescending<Session> { it.pinned }.thenByDescending { it.updatedAt })
            .map { s ->
                val preview = runCatching { store.latestMessage(s.id) }.getOrNull()
                    ?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }
                    ?.let { oneLine(it, 90) } ?: ""
                SessionRow(
                    id = s.id.value,
                    title = s.title.ifBlank { "chat" },
                    updatedAt = s.updatedAt,
                    preview = preview,
                    pinned = s.pinned,
                    archived = s.archived,
                    tags = normalizedTags(s.tags),
                )
            }
        _state.value = _state.value.copy(
            sessions = filterSessionsByTags(rows, _state.value.tagFilter),
            availableTags = rows.asSequence().flatMap { it.tags }.distinct().sorted().toList(),
        )
    }

    /** Trim/lowercase a tag, rejecting blank input and capping its length. */
    private fun normalizeTag(raw: String): String? {
        val clean = raw.trim().lowercase()
        if (clean.isEmpty()) return null
        return clean.take(MAX_TAG_LENGTH)
    }

    /** Normalized, deduped tags for display, capped at [MAX_TAGS_PER_SESSION]. */
    private fun normalizedTags(tags: List<String>): List<String> =
        tags.mapNotNull { normalizeTag(it) }.distinct().take(MAX_TAGS_PER_SESSION)

    // ---- file peek ----

    /** The workspace root as a string, for the markdown reference resolver. */
    val workspacePath: String get() = workspace.toString()

    /**
     * Resolve a candidate against the workspace and refuse anything that
     * escapes it (absolute paths outside the root, or `..` climbs). Returns the
     * normalized absolute path, or null.
     */
    private fun inWorkspace(path: String): Path? {
        val base = workspace.toAbsolutePath().normalize()
        val resolved = runCatching { base.resolve(path).normalize() }.getOrNull() ?: return null
        return if (resolved.startsWith(base)) resolved else null
    }

    /** True when [rel] resolves to a real file inside the workspace. */
    fun fileExists(rel: String): Boolean {
        val target = inWorkspace(rel) ?: return false
        return Files.isRegularFile(target)
    }

    /**
     * Open [path] (relative to the workspace, or absolute under it) in the peek
     * sheet, highlighting the 1-based [line] when supplied. Escapes are refused
     * and failures surface as [ChatState.error] without opening anything.
     */
    fun openFile(path: String, line: Int? = null) {
        val target = inWorkspace(path)
        if (target == null) {
            _state.value = _state.value.copy(error = "cannot open $path")
            return
        }
        try {
            val lines = ArrayList<String>()
            var truncated = false
            Files.newBufferedReader(target, StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val l = reader.readLine() ?: break
                    if (lines.size >= MAX_PEEK_LINES) {
                        truncated = true
                        break
                    }
                    lines.add(l)
                }
            }
            val rel = runCatching {
                workspace.toAbsolutePath().normalize().relativize(target).toString().replace('\\', '/')
            }.getOrDefault(path)
            _state.value = _state.value.copy(
                peek = FilePeek(
                    path = rel,
                    lines = lines,
                    highlight = line?.takeIf { it > 0 },
                    truncated = truncated,
                ),
                error = null,
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(error = t.message ?: "cannot open $path")
        }
    }

    /** Dismiss the peek sheet. */
    fun closePeek() {
        _state.value = _state.value.copy(peek = null)
    }

    // ---- backlinks ----

    /**
     * The current session's steps that reference [path], in timeline order:
     * assistant/thinking text that resolves a real reference to it, a tool
     * step whose metadata `path` or summary/rows mention it, or any row that
     * names it. [Backlink.touched] is set when the same path is in this run's
     * changes. Cheap and capped; safe to call from the peek sheet.
     */
    fun backlinksFor(path: String): List<Backlink> =
        matchBacklinks(
            steps = _state.value.steps,
            path = path,
            cwd = workspacePath,
            exists = ::fileExists,
            touchedPaths = _state.value.changes.byFile().keys,
        )

    // ---- file completion (`@` in the composer) ----

    /** A workspace file indexed for completion, with its modified time. */
    private data class CompletionEntry(val path: String, val modified: Long)

    /** Recursive index of workspace files, rebuilt from a bounded walk when stale. */
    @Volatile
    private var completionIndex: List<CompletionEntry>? = null

    /** When [completionIndex] was built, so a burst of keystrokes reuses one walk. */
    @Volatile
    private var completionIndexAt = 0L

    /**
     * Workspace-relative `/`-paths containing [prefix] (case-insensitive), for
     * the composer's `@` completion. A path that starts with the prefix ranks
     * first, then shorter paths, then name; only paths inside the workspace are
     * listed. An empty prefix yields a few recent root-level entries. The walk
     * is bounded (cap [MAX_COMPLETION_FILES], heavy/hidden dirs skipped) and
     * cached, so a keystroke only filters an in-memory list.
     */
    fun completeFiles(prefix: String): List<String> {
        val cached = completionIndex
        val now = System.currentTimeMillis()
        val index = if (cached != null && now - completionIndexAt < COMPLETION_TTL_MS) {
            cached
        } else {
            buildCompletionIndex().also {
                completionIndex = it
                completionIndexAt = now
            }
        }
        val needle = prefix.replace('\\', '/').removePrefix("@").trimStart('/').lowercase()
        if (needle.isEmpty()) {
            val roots = index.asSequence()
                .filter { !it.path.contains('/') }
                .sortedByDescending { it.modified }
                .map { it.path }
                .take(MAX_COMPLETION_SUGGESTIONS)
                .toList()
            if (roots.isNotEmpty()) return roots
        }
        return index.asSequence()
            .filter { it.path.contains(needle, ignoreCase = true) }
            .sortedWith(
                compareByDescending<CompletionEntry> { it.path.lowercase().startsWith(needle) }
                    .thenBy { it.path.length }
                    .thenBy { it.path.lowercase() },
            )
            .map { it.path }
            .take(MAX_COMPLETION_SUGGESTIONS)
            .toList()
    }

    /** Walk the workspace once, skipping heavy/hidden dirs; never escapes it. */
    private fun buildCompletionIndex(): List<CompletionEntry> {
        val root = workspace.toAbsolutePath().normalize()
        val out = ArrayList<CompletionEntry>()
        var visited = 0
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (visited >= MAX_COMPLETION_SCAN) return FileVisitResult.TERMINATE
                        visited++
                        if (dir != root) {
                            val name = dir.fileName?.toString().orEmpty()
                            if (name.startsWith(".") || name in HEAVY_DIRS) return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (out.size >= MAX_COMPLETION_FILES || visited >= MAX_COMPLETION_SCAN) {
                            return FileVisitResult.TERMINATE
                        }
                        visited++
                        val abs = file.toAbsolutePath().normalize()
                        if (!abs.startsWith(root)) return FileVisitResult.CONTINUE
                        val rel = root.relativize(abs).toString().replace('\\', '/')
                        if (rel.isEmpty()) return FileVisitResult.CONTINUE
                        val modified = runCatching { Files.getLastModifiedTime(file).toMillis() }.getOrDefault(0L)
                        out.add(CompletionEntry(rel, modified))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (ignored: Throwable) {
        }
        return out
    }

    // ---- canvas ----

    /**
     * Read [path] (workspace-relative, or absolute under it) as UTF-8 HTML for
     * the interactive canvas, capped at [MAX_CANVAS_BYTES]. The page is rendered
     * from this string, never from a `file://` URL, so it gets no origin to
     * reach back through. Escapes and oversized/empty pages are refused.
     */
    fun openCanvas(path: String) {
        val target = inWorkspace(path)
        if (target == null || !Files.isRegularFile(target)) {
            _state.value = _state.value.copy(error = "cannot open $path")
            return
        }
        try {
            if (Files.size(target) > MAX_CANVAS_BYTES) {
                _state.value = _state.value.copy(error = "page too large")
                return
            }
            val html = String(Files.readAllBytes(target), StandardCharsets.UTF_8)
            if (html.isBlank()) {
                _state.value = _state.value.copy(error = "the page is empty")
                return
            }
            _state.value = _state.value.copy(
                canvas = html,
                canvasPath = relativeToWorkspace(target),
                error = null,
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(error = t.message ?: "cannot open $path")
        }
    }

    /** Dismiss the canvas. */
    fun closeCanvas() {
        _state.value = _state.value.copy(canvas = null, canvasPath = null)
    }

    // ---- image attachments (vision) ----

    /** Queue [base64] (an inline image, no data-URL prefix) for the next send. */
    fun attachImage(name: String, mime: String, base64: String) {
        if (base64.isBlank()) return
        _state.value = _state.value.copy(
            attachments = _state.value.attachments + PendingImage(name, mime, base64),
            error = null,
        )
    }

    /** Drop the queued image at [index]; out-of-range indices are ignored. */
    fun removeAttachment(index: Int) {
        val current = _state.value.attachments
        if (index !in current.indices) return
        _state.value = _state.value.copy(attachments = current.filterIndexed { i, _ -> i != index })
    }

    // ---- files browser + editor ----

    /**
     * List `workspace/<dir>` one level deep: folders first, then files, each
     * name-ascending. [dir] is workspace-relative ("" or null is the root);
     * anything that resolves outside the workspace is refused and surfaces as
     * [FilesState.error]. The listing is capped at [MAX_FILES_ENTRIES].
     */
    fun openFiles(dir: String? = null) {
        val rel = normalizeRel(dir ?: "")
        val target = inWorkspace(rel)
        if (target == null || !Files.isDirectory(target)) {
            surfaceFilesError("cannot open ${rel.ifEmpty { "workspace" }}", rel)
            return
        }
        try {
            _state.value = _state.value.copy(
                files = FilesState(dir = relativeToWorkspace(target), entries = listEntries(target)),
            )
        } catch (t: Throwable) {
            surfaceFilesError(t.message ?: "cannot open ${rel.ifEmpty { "workspace" }}", rel)
        }
    }

    /** Enter the workspace-relative folder [rel]. */
    fun enterDir(rel: String) = openFiles(rel)

    /** Move to the parent of the current folder, clamped at the workspace root. */
    fun filesUp() {
        val current = _state.value.files?.dir.orEmpty()
        if (current.isEmpty()) {
            openFiles()
            return
        }
        openFiles(current.substringBeforeLast('/', ""))
    }

    /**
     * Write UTF-8 [content] to [path], creating parent folders as needed and
     * refusing escapes. The listing is refreshed afterwards either way.
     */
    fun saveFile(path: String, content: String) {
        val target = inWorkspace(path)
        if (target == null) {
            surfaceFilesError("cannot write $path")
            return
        }
        try {
            target.parent?.let { Files.createDirectories(it) }
            Files.write(target, content.toByteArray(StandardCharsets.UTF_8))
            openFiles(_state.value.files?.dir.orEmpty())
        } catch (t: Throwable) {
            surfaceFilesError(t.message ?: "cannot write $path")
        }
    }

    /** Create an empty file named [name] inside [dir], refusing bad names/escapes. */
    fun createFile(dir: String, name: String) {
        val trimmed = name.trim()
        if (!validName(trimmed)) return surfaceFilesError("invalid name")
        val target = inWorkspace(joinRel(dir, trimmed))
        if (target == null) return surfaceFilesError("cannot create $trimmed")
        try {
            target.parent?.let { Files.createDirectories(it) }
            Files.createFile(target)
            openFiles(dir)
        } catch (t: Throwable) {
            surfaceFilesError(t.message ?: "cannot create $trimmed")
        }
    }

    /** Create a folder named [name] inside [dir], refusing bad names/escapes. */
    fun createDir(dir: String, name: String) {
        val trimmed = name.trim()
        if (!validName(trimmed)) return surfaceFilesError("invalid name")
        val target = inWorkspace(joinRel(dir, trimmed))
        if (target == null) return surfaceFilesError("cannot create $trimmed")
        try {
            target.parent?.let { Files.createDirectories(it) }
            Files.createDirectory(target)
            openFiles(dir)
        } catch (t: Throwable) {
            surfaceFilesError(t.message ?: "cannot create $trimmed")
        }
    }

    /**
     * Delete [path]. A folder is only removed recursively when [recursive] is
     * true; otherwise it must be empty. Escapes are refused.
     */
    fun deleteEntry(path: String, recursive: Boolean = false) {
        val target = inWorkspace(path)
        if (target == null) return surfaceFilesError("cannot delete $path")
        try {
            if (Files.isDirectory(target) && recursive) {
                val paths = ArrayList<Path>()
                Files.walk(target).use { stream -> stream.forEach { paths.add(it) } }
                paths.sortWith(compareByDescending { it.nameCount })
                for (p in paths) Files.deleteIfExists(p)
            } else {
                Files.delete(target)
            }
            openFiles(_state.value.files?.dir.orEmpty())
        } catch (t: Throwable) {
            surfaceFilesError(t.message ?: "cannot delete $path")
        }
    }

    /** Rename [path] to [newName] within the same folder; escapes are refused. */
    fun renameEntry(path: String, newName: String) {
        val trimmed = newName.trim()
        if (!validName(trimmed)) return surfaceFilesError("invalid name")
        val source = inWorkspace(path)
        val parent = normalizeRel(path).substringBeforeLast('/', "")
        val dest = inWorkspace(joinRel(parent, trimmed))
        if (source == null || dest == null) return surfaceFilesError("cannot rename $path")
        try {
            Files.move(source, dest)
            openFiles(_state.value.files?.dir.orEmpty())
        } catch (t: Throwable) {
            surfaceFilesError(t.message ?: "cannot rename $path")
        }
    }

    /**
     * Load [path] into the editor, capped at [MAX_PEEK_LINES] with a truncation
     * flag (the screen disables Save when set). Escapes are refused.
     */
    fun editFile(path: String) {
        val target = inWorkspace(path)
        if (target == null || !Files.isRegularFile(target)) {
            _state.value = _state.value.copy(editor = EditorState(path, emptyList(), error = "cannot open $path"))
            return
        }
        try {
            val lines = ArrayList<String>()
            var truncated = false
            Files.newBufferedReader(target, StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val l = reader.readLine() ?: break
                    if (lines.size >= MAX_PEEK_LINES) {
                        truncated = true
                        break
                    }
                    lines.add(l)
                }
            }
            _state.value = _state.value.copy(
                editor = EditorState(relativeToWorkspace(target), lines, truncated = truncated),
            )
        } catch (t: Throwable) {
            _state.value = _state.value.copy(editor = EditorState(path, emptyList(), error = t.message ?: "cannot open $path"))
        }
    }

    /** Dismiss the files editor. */
    fun closeEditor() {
        _state.value = _state.value.copy(editor = null)
    }

    /** One directory's entries: folders first, then files, each name-ascending. */
    private fun listEntries(dir: Path): List<FileEntry> {
        val base = workspace.toAbsolutePath().normalize()
        val all = ArrayList<FileEntry>()
        Files.newDirectoryStream(dir).use { stream ->
            for (p in stream) {
                val abs = p.toAbsolutePath().normalize()
                if (!abs.startsWith(base)) continue
                val isDir = Files.isDirectory(p)
                val size = if (isDir) 0L else runCatching { Files.size(p) }.getOrDefault(0L)
                val modified = runCatching { Files.getLastModifiedTime(p).toMillis() }.getOrDefault(0L)
                all += FileEntry(
                    name = p.fileName?.toString() ?: abs.fileName.toString(),
                    path = base.relativize(abs).toString().replace('\\', '/'),
                    isDir = isDir,
                    size = size,
                    modified = modified,
                )
            }
        }
        return all
            .sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
            .take(MAX_FILES_ENTRIES)
    }

    /** A workspace-relative, `/`-joined, trimmed path; "" for the root. */
    private fun normalizeRel(path: String): String =
        path.replace('\\', '/').trim('/').let { if (it == ".") "" else it }

    private fun relativeToWorkspace(target: Path): String = runCatching {
        workspace.toAbsolutePath().normalize().relativize(target).toString().replace('\\', '/')
    }.getOrDefault("")

    private fun joinRel(dir: String, name: String): String {
        val d = normalizeRel(dir)
        return if (d.isEmpty()) name else "$d/$name"
    }

    /** A safe single path segment: non-blank, no separators, not `.`/`..`. */
    private fun validName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\')

    /** Record a files error without clobbering the current listing. */
    private fun surfaceFilesError(message: String, dir: String = _state.value.files?.dir.orEmpty()) {
        val current = _state.value.files
        _state.value = _state.value.copy(
            files = (current ?: FilesState(dir = dir, entries = emptyList())).copy(error = message),
        )
    }

    // ---- terminal ----

    /**
     * Start the interactive shell in the workspace, replacing any live one.
     * The StateFlow flips [TerminalState.running] on immediately so the screen
     * can draw; failure (no executor, dead cwd) surfaces as an error.
     */
    fun openTerminal() {
        _state.value = _state.value.copy(terminal = TerminalState(running = true))
        viewModelScope.launch {
            val started = terminal.open(
                scope = viewModelScope,
                onChunk = { appendTerminal(it) },
                onExit = {
                    _state.value = _state.value.copy(
                        terminal = _state.value.terminal.copy(running = false),
                    )
                },
            )
            if (!started) {
                _state.value = _state.value.copy(
                    terminal = TerminalState(running = false, error = "terminal unavailable"),
                )
            }
        }
    }

    /**
     * Echo [line] for the user, then hand it to the shell as one command. The
     * shell is a pipe, not a tty, so without the echo the screen would look
     * frozen after every send.
     */
    fun sendTerminal(line: String) {
        if (!_state.value.terminal.running) return
        val command = line.trimEnd('\n')
        appendTerminal(if (command.isEmpty()) "\n" else command + "\n")
        viewModelScope.launch { terminal.write(command) }
    }

    /** Send Ctrl-C to the running shell. */
    fun interruptTerminal() {
        viewModelScope.launch { terminal.interrupt() }
    }

    /** Drop retained output without stopping the shell. */
    fun clearTerminal() {
        _state.value = _state.value.copy(
            terminal = _state.value.terminal.copy(lines = emptyList()),
        )
    }

    /** Stop the shell and mark it not running. */
    fun closeTerminal() {
        _state.value = _state.value.copy(
            terminal = _state.value.terminal.copy(running = false),
        )
        viewModelScope.launch { terminal.close() }
    }

    /** Append a raw chunk, retaining only the most recent [MAX_TERMINAL_CHUNKS]. */
    private fun appendTerminal(chunk: String) {
        val current = _state.value.terminal
        val next = current.lines + chunk
        val capped = if (next.size > MAX_TERMINAL_CHUNKS) next.takeLast(MAX_TERMINAL_CHUNKS) else next
        _state.value = _state.value.copy(terminal = current.copy(lines = capped))
    }

    // ---- revert ----

    /**
     * Undo the latest recorded edit of [edit]'s path: restore its pre-edit
     * snapshot to disk and drop every change row for that path. A missing
     * snapshot store or a failed restore surfaces as [ChatState.error].
     */
    fun revert(edit: FileEdit) {
        val snapshots = snapshots
        if (snapshots == null) {
            _state.value = _state.value.copy(error = "revert unavailable")
            return
        }
        viewModelScope.launch {
            val session = _state.value.currentSessionId ?: edit.sessionId.value
            when (val result = Reverter.revertLatest(snapshots, SessionId(session), edit.path, workspace)) {
                is RevertResult.Restored -> _state.value = _state.value.copy(
                    changes = RunChanges(edits = _state.value.changes.edits.filterNot { it.path == edit.path }),
                    error = null,
                )
                is RevertResult.Failed -> _state.value =
                    _state.value.copy(error = "revert failed: ${result.reason}")
            }
        }
    }

    /** Open a changed file in the peek sheet (workspace-relative path). */
    fun openChangedFile(path: String) = openFile(path)

    /**
     * Append one change row, keeping only the most recent [MAX_RUN_CHANGES] so a
     * long session with thousands of edits cannot grow the Changes card without
     * bound. Per-file revert still works for every retained row (and the
     * snapshot store is unaffected).
     */
    private fun addChange(edit: FileEdit) {
        _state.value = _state.value.copy(changes = (_state.value.changes + edit).bounded())
    }

    /** Cap an edit list to its most recent [MAX_RUN_CHANGES] rows. */
    private fun RunChanges.bounded(): RunChanges =
        if (edits.size <= MAX_RUN_CHANGES) this else RunChanges(edits = edits.takeLast(MAX_RUN_CHANGES))

    /** Test/teardown seam: record a structured change without a full run. */
    internal fun recordChange(edit: FileEdit) = addChange(edit)

    // ---- chat ----

    private fun providersFor(provider: String, key: String): SimpleProviderRegistry =
        registryFactory?.invoke(provider, key) ?: ProviderCatalogue.registry(provider, key)

    private suspend fun collectEvents() {
        bus.events.collect { e ->
            val current = _state.value.currentSessionId
            if (current == null || e.sessionId.value != current) return@collect
            when (e) {
                is AgentEvent.PartDelta -> {
                    val kind = if (e.kind == DeltaKind.REASONING) StepKind.THINKING else StepKind.ASSISTANT
                    val label = if (kind == StepKind.THINKING) "THINKING" else "ASSISTANT"
                    stream.append(e.partId.value, e.delta, kind, label)
                    scheduleDeltaFlush()
                }
                is AgentEvent.PartUpdated -> requestRebuild(SessionId(current))
                is AgentEvent.ToolFinished -> requestRebuild(SessionId(current))
                is AgentEvent.ToolCallStarted -> {
                    diag("tool: " + e.name)
                    applyEvent(e)
                }
                is AgentEvent.Progress -> {
                    applyEvent(e)
                    // Keep the ongoing notification's progress line in step.
                    context?.let { ctx -> runCatching { RunService.update(ctx, e.message) } }
                }
                is AgentEvent.UsageUpdated -> _state.value = _state.value.copy(usage = e.usage)
                is AgentEvent.BudgetWarning -> {
                    val pct = (e.fraction * 100).toInt()
                    diag("budget: $pct% used")
                    _state.value = _state.value.copy(hint = "cost budget ${pct}% used")
                }
                is AgentEvent.FileEdited -> addChange(e.edit)
                is AgentEvent.StateChanged -> {
                    val busy = e.state == dev.spindle.core.model.SessionState.RUNNING
                    // A state change is authoritative: flush whatever streamed
                    // before re-reading, so no text is stranded in the buffer.
                    flushDeltas()
                    _state.value = _state.value.copy(busy = busy, todos = loadTodos(SessionId(current)))
                    if (!busy) {
                        // The store is the truth: replace optimistic/partial rows
                        // (the common case after a stop) with what was saved.
                        requestRebuild(SessionId(current))
                        refreshSessions()
                    }
                }
                is AgentEvent.Error -> {
                    diag("error: " + e.message)
                    flushDeltas()
                    _state.value = _state.value.copy(error = e.message)
                }
                else -> Unit
            }
        }
    }

    /** Fold a structural event into the buffer without dropping streamed text. */
    private fun applyEvent(e: AgentEvent) {
        stream.transform { StepMapper.applyEvent(it, e) }
        publishSteps()
    }

    /** Publish the accumulator's current rows (materializing buffered deltas). */
    private fun publishSteps() {
        _state.value = _state.value.copy(steps = stream.snapshot())
    }

    /**
     * Replace the timeline wholesale (session open/new, rewind, close). Bumping
     * [timelineGeneration] invalidates any rebuild that read the store before
     * this replacement, so a stale snapshot cannot be published over the fresh
     * rows (e.g. undoing a rewind).
     */
    private fun replaceTimeline(steps: List<UiStep>) {
        timelineGeneration++
        stream.reset(steps)
    }

    /**
     * Coalesce a burst of deltas into one state publish. Tokens only append to
     * the accumulator; a single delayed flush lands them, so a long stream costs
     * O(delta) per token instead of a full list/body rebuild per token.
     */
    private fun scheduleDeltaFlush() {
        if (deltaFlushJob?.isActive == true) return
        deltaFlushJob = viewModelScope.launch {
            delay(DELTA_COALESCE_MS)
            if (stream.hasPending()) publishSteps()
        }
    }

    /** Publish any buffered deltas immediately (terminal/structural events). */
    private fun flushDeltas() {
        deltaFlushJob?.cancel()
        deltaFlushJob = null
        if (stream.hasPending()) publishSteps()
    }

    /**
     * Re-read and re-map the whole session from the store. Runs the parse and
     * child enrichment on [Dispatchers.Default] and coalesces concurrent
     * requests: while a pass is in flight, further requests only mark it dirty,
     * so a burst of [AgentEvent.PartUpdated]s cannot stack full rebuilds.
     */
    private fun requestRebuild(sid: SessionId) {
        flushDeltas()
        if (!rebuilds.request()) return
        viewModelScope.launch {
            try {
                // [sid] is the session the request came from, but the user may
                // switch sessions while the pass is in flight. Re-read the
                // *current* session on every iteration so a coalesced request
                // that raced a switch rebuilds what is actually on screen
                // instead of being consumed and dropped.
                var target = sid
                while (true) {
                    val generation = timelineGeneration
                    val built = withContext(Dispatchers.Default) {
                        val messages = store.messages(target)
                        enrich(StepMapper.fromMessages(messages)) to loadTodos(target)
                    }
                    // A wholesale replacement (rewind/open/close) landed while
                    // this pass ran; its store snapshot is stale, so drop it.
                    if (generation != timelineGeneration) break
                    if (_state.value.currentSessionId == target.value) {
                        stream.rebase(built.first)
                        _state.value = _state.value.copy(steps = stream.snapshot(), todos = built.second)
                    }
                    if (!rebuilds.finish()) break
                    target = _state.value.currentSessionId?.let { SessionId(it) } ?: break
                }
            } finally {
                rebuilds.reset()
            }
        }
    }

    /** Read the session's todo list, tolerating a store that cannot answer. */
    private suspend fun loadTodos(sid: SessionId): List<TodoItem> =
        runCatching { store.todos(sid) }.getOrDefault(emptyList())

    /** Test/teardown seam: re-read the current session's todo list. */
    internal suspend fun refreshTodos() {
        val sid = _state.value.currentSessionId?.let { SessionId(it) } ?: return
        _state.value = _state.value.copy(todos = loadTodos(sid))
    }

    /**
     * Surface what a subagent actually did by inlining its child steps. The
     * collapsed preview is the first 8 rows; the full transcript is fetched on
     * demand ([expandSubagent]) and cached, because [enrich] runs on every
     * streaming rebuild and must not read every child session each time.
     */
    private val childCache = ConcurrentHashMap<String, List<UiStep>>()

    /**
     * Subagent ids the user has explicitly expanded; kept open across rebuilds.
     * Concurrent because [enrich] now runs off the main thread on the rebuild
     * dispatcher while taps arrive on the main thread.
     */
    private val expandedChildren: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /**
     * Drop every session-scoped subagent cache. Called on session open/new/close
     * (and ViewModel teardown) so a long-lived process does not accumulate every
     * subagent transcript of every session it has ever shown.
     */
    private fun resetSessionCaches() {
        childCache.clear()
        expandedChildren.clear()
    }

    private suspend fun enrich(steps: List<UiStep>): List<UiStep> = steps.map { step ->
        val child = step.childId ?: return@map step
        val kids = childSteps(child)
        step.copy(
            rows = kids.take(8).map { it.label to it.summary },
            childSteps = if (child in expandedChildren) kids else emptyList(),
        )
    }

    /**
     * Read (and cache) one subagent's transcript. The cache is cleared on every
     * session switch and capped at [MAX_CHILD_CACHE], so a session that spawns
     * an unbounded number of subagents still cannot grow it forever; an evicted
     * transcript is simply re-read from the store on demand.
     */
    private suspend fun childSteps(child: String): List<UiStep> {
        childCache[child]?.let { return it }
        val loaded = runCatching { StepMapper.fromMessages(store.messages(SessionId(child))) }
            .getOrDefault(emptyList())
        if (childCache.size >= MAX_CHILD_CACHE) childCache.clear()
        childCache[child] = loaded
        return loaded
    }

    /** Load a subagent's full transcript (on tap) and attach it to the row. */
    fun expandSubagent(step: UiStep) {
        val child = step.childId ?: return
        // Match on `childId`, not `id`: groupSteps rewrites a folded row's id to
        // the preceding THINKING row's id, so `id` may not exist in the raw list.
        fun patch(f: (UiStep) -> UiStep) {
            stream.transform { current -> current.map { if (it.childId == child) f(it) else it } }
            publishSteps()
        }
        if (step.childSteps.isNotEmpty()) {
            // Already loaded — toggle it closed.
            expandedChildren -= child
            patch { it.copy(childSteps = emptyList()) }
            return
        }
        expandedChildren += child
        patch { it.copy(childLoading = true) }
        viewModelScope.launch {
            val kids = childSteps(child)
            // Re-attach in both the raw list and the enriched view; enrich() will
            // also pick this up on the next rebuild via childCache.
            patch { it.copy(childSteps = kids, childLoading = false) }
        }
    }

    fun onInput(v: String) { _state.value = _state.value.copy(input = v) }

    /** True when the selected model advertises image input; unknown models pass. */
    private fun modelSupportsVision(): Boolean {
        val modelId = keys.model.substringAfter('/', keys.model)
        val info = ProviderCatalogue.defaultModels(keys.provider).firstOrNull { it.id == modelId }
        return info?.supportsVision ?: true
    }

    fun send() {
        val currentId = _state.value.currentSessionId ?: return
        val sid = SessionId(currentId)
        val text = _state.value.input.trim()
        val attachments = _state.value.attachments
        // Refuse to start a second run while the previous coroutine is still
        // alive: a non-cooperative tool (e.g. a first-use rootfs install) can
        // outlive stop(), and two runs sharing the bus/sampler corrupt state.
        if ((text.isEmpty() && attachments.isEmpty()) || _state.value.busy || runJob?.isActive == true) return
        val key = keys.apiKey
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(needsKey = true, input = "")
            return
        }
        val blind = attachments.isNotEmpty() && !modelSupportsVision()

        diag("run start: " + oneLine(text, 60).ifBlank { "[image]" })
        diag("shell: " + shellKind())

        // Optimistic echo + busy, both SYNCHRONOUS with the tap, so the user
        // sees their own bubble and the stop affordance instantly instead of
        // waiting for the agent's first event round-trip. Route through the
        // accumulator so any still-buffered deltas fold in rather than vanish.
        stream.transform { current ->
            // Replace any stale optimistic row (a prior run stopped before a
            // store rebuild) so the timeline never holds two rows with the same
            // id — a duplicate LazyColumn key crashes the app.
            current.filterNot { it.id == StepMapper.PENDING_USER_ID } + StepMapper.optimisticUser(text)
        }
        _state.value = _state.value.copy(
            input = "",
            error = null,
            hint = if (blind) "the selected model may not support images" else null,
            busy = true,
            attachments = emptyList(),
            steps = stream.snapshot(),
        )

        // Keep the run alive while backgrounded and visible in the shade.
        context?.let { ctx ->
            val title = _state.value.sessions.firstOrNull { it.id == currentId }?.title.orEmpty()
            runCatching { RunService.start(ctx, currentId, title.ifBlank { "Lumen run" }) }
        }

        val generation = ++runGeneration
        runningSessionId = currentId
        runningSessions?.add(currentId)
        // The Application scope keeps the run alive across Activity destruction;
        // tests without one fall back to the ViewModel scope.
        val scope = runScope ?: viewModelScope
        runJob = scope.launch {
            // Title a fresh chat from its first message.
            runCatching {
                val s = store.session(sid)
                if (s != null && (s.title == "new chat" || s.title.isBlank())) {
                    store.updateSession(s.copy(title = oneLine(text, 48), updatedAt = System.currentTimeMillis()))
                }
            }
            // Carry the queued images on a dedicated user message; Wire expands
            // its Part.File parts into WireImage for Role.USER.
            if (attachments.isNotEmpty()) {
                runCatching {
                    store.appendMessage(
                        Message(
                            id = MessageId(Ids.new("msg")),
                            sessionId = sid,
                            role = Role.USER,
                            parts = attachments.map { a ->
                                Part.File(PartId(Ids.new("prt")), path = a.name, mime = a.mime, dataBase64 = a.base64)
                            },
                            createdAt = System.currentTimeMillis(),
                        ),
                    )
                }
            }
            val loop = AgentLoop(
                providers = providersFor(keys.provider, key),
                tools = DefaultTools.registry(shell = shell ?: HostShellExecutor()),
                store = store,
                bus = bus,
                permissions = permissionGate,
                questions = questionGate,
                approval = approvalFor(keys.askBeforeTools) { keys.allowedPatterns },
                snapshots = snapshots,
            )
            try {
                startPerf(generation)
                withContext(Dispatchers.IO) { runCatching { watcher?.start() } }
                loop.prompt(
                    sid, text, keys.model, _state.value.agentMode,
                    budget = ContextBudget(maxCostUsd = _state.value.maxCostUsd.takeIf { it > 0 }),
                )
                diag("run finished")
            } catch (e: CancellationException) {
                // A user stop (or a superseding run) is an expected end, not an
                // error; never paint it as one. Cancellation must still propagate.
                throw e
            } catch (t: Throwable) {
                diag("error: " + (t.message ?: t.toString()))
                if (generation == runGeneration) {
                    _state.value = _state.value.copy(error = t.message ?: t.toString(), busy = false)
                }
            } finally {
                // A superseded run must not tear down the new run's watcher,
                // service or busy flag: that race is exactly generation's job.
                if (generation == runGeneration) {
                    // Tear down only when this run is still current. A stop()
                    // already reset the live marker; a superseding run owns the
                    // watcher/service now, so this must not touch them.
                    runningSessionId = null
                    runningSessions?.remove(sid.value)
                    watcher?.stop()
                    context?.let { ctx -> runCatching { RunService.stop(ctx) } }
                }
                // The sampler is stopped on EVERY end path (normal finish, stop,
                // cancellation, superseding send). A superseded run no longer
                // owns the live sampler, so stopPerf is a no-op for it and can
                // never tear down the run that replaced it.
                reportPerf(stopPerf(generation))
            }
            if (generation == runGeneration) refreshSessions()
        }
    }

    fun stop() {
        runGeneration++
        runJob?.cancel()
        runningSessionId?.let { runningSessions?.remove(it) }
        runningSessionId = null
        // Land the last buffered tokens now instead of leaving them to the
        // coalescing timer (which the ViewModel may not outlive).
        flushDeltas()
        clearAsks()
        watcher?.stop()
        context?.let { ctx -> runCatching { RunService.stop(ctx) } }
        // stop() is authoritative: kill the live sampler even though the run's
        // own finally now sees a bumped generation and would skip it.
        reportPerf(stopPerfNow())
        diag("run stopped")
        _state.value = _state.value.copy(busy = false, ask = null)
    }

    override fun onCleared() {
        super.onCleared()
        // The Activity is going away for good. The run belongs to the process
        // scope and the stores live in the app container, so neither is touched
        // here: a backgrounded run finishes and persists independently. Only
        // screen-bound resources are reaped.
        watcher?.stop()
        terminal.shutdown()
        // Never leave a Choreographer callback alive past this ViewModel.
        reportPerf(stopPerfNow())
        // The subagent caches are session state; a destroyed ViewModel must not
        // pin any of it.
        resetSessionCaches()
    }

    private fun oneLine(s: String, max: Int): String =
        s.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }

    companion object {
        /** Upper bound on peeked lines; longer files are truncated with a note. */
        const val MAX_PEEK_LINES = 2000

        /** Upper bound on one folder's listing; extra entries are dropped. */
        const val MAX_FILES_ENTRIES = 2000

        /** Upper bound on workspace files indexed for `@` completion. */
        const val MAX_COMPLETION_FILES = 2000

        /** Upper bound on suggestions returned to the composer. */
        const val MAX_COMPLETION_SUGGESTIONS = 50

        /** Total directory entries visited while indexing completions, so it stays bounded. */
        private const val MAX_COMPLETION_SCAN = 20_000

        /** How long a completion index is reused before a fresh bounded walk. */
        private const val COMPLETION_TTL_MS = 3_000L

        /** Directories too heavy to walk for completion. Hidden dirs are skipped too. */
        private val HEAVY_DIRS = setOf("node_modules", "build", ".gradle", ".idea", "target", "dist", "vendor")

        /** Upper bound on retained terminal chunks, so a chatty shell cannot grow forever. */
        const val MAX_TERMINAL_CHUNKS = 2000

        /** Upper bound on an HTML page loaded into the canvas: 2 MB, UTF-8. */
        const val MAX_CANVAS_BYTES = 2L * 1024 * 1024

        /** Upper bound on retained diagnostics lines. */
        const val MAX_DIAG_LINES = 300

        /**
         * Upper bound on change rows retained per session for the Changes card.
         * Older rows are dropped; the snapshot store keeps revert available.
         */
        const val MAX_RUN_CHANGES = 500

        /**
         * Upper bound on cached subagent transcripts before the cache resets, so
         * a session that spawns many subagents cannot grow it without bound.
         */
        private const val MAX_CHILD_CACHE = 256

        /** Longest normalized tag accepted; longer input is truncated. */
        const val MAX_TAG_LENGTH = 32

        /** Most tags one session may carry; further adds are ignored. */
        const val MAX_TAGS_PER_SESSION = 10

        /** Storage scan budget, shared across every measured tree. */
        private const val SCAN_BUDGET_MS = 20_000L
        private const val MAX_SCAN_DEPTH = 40
        private const val MAX_SCAN_NODES = 200_000

        const val STORAGE_WORKSPACE = "project workspace"
        const val STORAGE_ALPINE = "alpine rootfs"
        const val STORAGE_DEBIAN = "debian rootfs"
        const val STORAGE_WRAPPERS = "command wrappers"
        const val STORAGE_STORES = "session store (lumen.db)"
        const val STORAGE_LOGS = "logs"
        const val STORAGE_CACHE = "app cache"

        /** Quiet period before a session search query is actually executed. */
        internal const val SEARCH_DEBOUNCE_MS = 150L

        /**
         * How long a burst of token deltas accumulates before one state publish.
         * Roughly two to three frames: long enough to slash allocations, short
         * enough that the stream still feels live.
         */
        internal const val DELTA_COALESCE_MS = 40L

        fun Factory(context: Context, workspace: java.io.File): androidx.lifecycle.ViewModelProvider.Factory {
            val app = context.applicationContext as? LumenApp ?: error("LumenApp must own ChatViewModel")
            return Factory(app.container, app.applicationScope, app.events, app.runningSessions, workspace)
        }

        /**
         * Build a ViewModel over the process singletons: the shared stores, the
         * Application scope and the Application event bus, so a run outlives any
         * Activity and still feeds whichever ViewModel is open when the user
         * returns.
         */
        fun Factory(
            container: AppContainer,
            runScope: CoroutineScope,
            events: EventBus,
            runningSessions: MutableSet<String>,
            workspace: java.io.File,
        ): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(
                    workspace = workspace.toPath(),
                    keys = container.keys,
                    store = container.store,
                    snapshots = container.snapshots,
                    shell = AndroidShellExecutor(container.context),
                    context = container.context,
                    environment = AndroidEnvironment(container.context),
                    debian = DebianEnvironment(container.context),
                    runScope = runScope,
                    eventBus = events,
                    runningSessions = runningSessions,
                ) as T
            }
    }
}

/**
 * Coalesces bursty rebuild requests. While a rebuild pass is in flight, further
 * requests only set [dirty] instead of stacking another pass; the running loop
 * picks up the dirty flag and runs exactly one more pass. Pure state machine so
 * the coalescing rule is unit-testable without coroutines or a store.
 */
internal class RebuildCoalescer {
    /** True between a [request] that returned true and its matching [finish]. */
    var inFlight: Boolean = false
        private set

    /** True when a request arrived while a pass was already in flight. */
    var dirty: Boolean = false
        private set

    /** Claim the rebuild slot; false means the caller should just mark dirty. */
    @Synchronized
    fun request(): Boolean {
        if (inFlight) {
            dirty = true
            return false
        }
        inFlight = true
        return true
    }

    /** Finish the current pass; true means another pass is needed. */
    @Synchronized
    fun finish(): Boolean {
        if (dirty) {
            dirty = false
            return true
        }
        inFlight = false
        return false
    }

    /** Abandon any in-flight pass (e.g. the session changed underneath it). */
    @Synchronized
    fun reset() {
        inFlight = false
        dirty = false
    }
}

/**
 * The sessions visible under a set of active tag filters. A session matches when
 * it carries ANY of [selectedTags] (OR, not AND), so selecting more tags widens
 * the listing instead of narrowing it. An empty selection returns every row.
 * Pure so filtering is unit-testable without a store or Compose.
 */
internal fun filterSessionsByTags(rows: List<SessionRow>, selectedTags: Set<String>): List<SessionRow> =
    if (selectedTags.isEmpty()) rows
    else rows.filter { row -> row.tags.any { it in selectedTags } }

/**
 * Pure backlink matching, separated so it is unit-testable without a store.
 *
 * A step references [path] when an assistant/thinking body or summary resolves
 * a real reference to it, when a tool step's metadata `path`/`file` names it,
 * or when its summary/rows/body mention it as a whole path. Results come back
 * in timeline order, capped at [MAX_BACKLINKS].
 */
internal fun matchBacklinks(
    steps: List<UiStep>,
    path: String,
    cwd: String,
    exists: (String) -> Boolean,
    touchedPaths: Set<String>,
): List<Backlink> {
    val target = normalizeBacklinkPath(path, cwd)
    if (target.isEmpty()) return emptyList()
    val touched = touchedPaths.any { normalizeBacklinkPath(it, cwd) == target }
    val out = ArrayList<Backlink>()
    for ((index, step) in steps.withIndex()) {
        if (out.size >= MAX_BACKLINKS) break
        if (stepReferences(step, target, cwd, exists)) {
            out += Backlink(index, step.label, step.summary, touched)
        }
    }
    return out
}

private const val MAX_BACKLINKS = 50

private fun stepReferences(
    step: UiStep,
    target: String,
    cwd: String,
    exists: (String) -> Boolean,
): Boolean {
    val prose = step.kind == StepKind.ASSISTANT || step.kind == StepKind.THINKING
    if (prose && (resolvesBacklink(step.body, target, cwd, exists) ||
            resolvesBacklink(step.summary, target, cwd, exists))
    ) {
        return true
    }
    val metaPath = step.toolMetadata["path"] ?: step.toolMetadata["file"]
    if (metaPath != null && normalizeBacklinkPath(metaPath, cwd) == target) return true
    if (mentionsBacklink(step.summary, target)) return true
    if (step.rows.any { mentionsBacklink(it.first, target) || mentionsBacklink(it.second, target) }) return true
    if ((step.kind == StepKind.TOOL || step.kind == StepKind.SUBAGENT) && mentionsBacklink(step.body, target)) {
        return true
    }
    return false
}

private fun resolvesBacklink(
    text: String,
    target: String,
    cwd: String,
    exists: (String) -> Boolean,
): Boolean {
    if (text.isBlank()) return false
    if (!text.contains('/') && !text.contains('.')) return false
    return ReferenceResolver.resolve(text, cwd, exists).any { it.path == target }
}

/** A path as workspace-relative, `/`-joined, without a leading `./` or `@`. */
private fun normalizeBacklinkPath(path: String, cwd: String): String {
    var p = path.replace('\\', '/').removePrefix("@")
    val base = cwd.replace('\\', '/').trimEnd('/')
    if (base.isNotEmpty() && p.startsWith("$base/")) p = p.substring(base.length + 1)
    while (p.startsWith("./")) p = p.substring(2)
    return p.trim('/')
}

/** True when [target] occurs in [text] as a whole path, not inside a longer token. */
private fun mentionsBacklink(text: String, target: String): Boolean {
    if (text.isEmpty() || target.isEmpty()) return false
    val hay = text.replace('\\', '/')
    var from = 0
    while (true) {
        val i = hay.indexOf(target, from, ignoreCase = true)
        if (i < 0) return false
        val before = if (i == 0) null else hay[i - 1]
        val afterIdx = i + target.length
        val after = if (afterIdx >= hay.length) null else hay[afterIdx]
        if ((before == null || !isBacklinkPathChar(before)) && (after == null || !isBacklinkPathChar(after))) {
            return true
        }
        from = i + 1
    }
}

private fun isBacklinkPathChar(c: Char): Boolean =
    c.isLetterOrDigit() || c == '/' || c == '.' || c == '_' || c == '-'
