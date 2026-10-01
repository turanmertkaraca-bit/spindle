package dev.lumen.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lumen.app.data.AndroidSessionStore
import dev.lumen.app.data.AndroidSnapshotStore
import dev.lumen.app.data.KeyStore
import dev.lumen.app.data.ProviderCatalogue
import dev.lumen.app.platform.AndroidShellExecutor
import dev.lumen.app.platform.AndroidTerminal
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.StepMapper
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.Usage
import dev.spindle.core.store.RevertResult
import dev.spindle.core.store.Reverter
import dev.spindle.core.store.SnapshotStore
import dev.spindle.core.tool.ShellExecutor
import dev.spindle.tool.HostShellExecutor
import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.agent.PermissionGate
import dev.spindle.core.agent.QuestionGate
import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.DeltaKind
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.SearchHit
import dev.spindle.core.store.SessionSearch
import dev.spindle.core.store.SessionStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** One row on the home screen. */
data class SessionRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
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
    /** Structured file changes the agent made in this session. */
    val changes: RunChanges = RunChanges.EMPTY,
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
    private val ownedStore: AutoCloseable? = null,
    private val snapshots: SnapshotStore? = null,
    private val ownedSnapshots: AutoCloseable? = null,
    private val shell: ShellExecutor? = null,
) : ViewModel() {

    private val bus = EventBus()

    private val _state = MutableStateFlow(
        ChatState(
            model = keys.model,
            provider = keys.provider,
            needsKey = !keys.hasKey,
            theme = keys.theme,
            askBeforeTools = keys.askBeforeTools,
            agentMode = keys.agentMode,
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var runJob: Job? = null

    /** The one interactive shell behind the terminal screen. */
    private val terminal = AndroidTerminal(workspace.toFile(), shell)

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

    init {
        viewModelScope.launch { refreshSessions() }
        viewModelScope.launch { collectEvents() }
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
            _state.value = _state.value.copy(
                currentSessionId = id.value,
                steps = emptyList(),
                input = "",
                error = null,
                usage = Usage(),
                changes = RunChanges.EMPTY,
            )
            refreshSessions()
        }
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            val sid = SessionId(id)
            val messages = store.messages(sid)
            _state.value = _state.value.copy(
                currentSessionId = id,
                steps = enrich(StepMapper.fromMessages(messages)),
                input = "",
                error = null,
                busy = false,
                usage = messages.fold(Usage()) { acc, m -> acc + m.usage },
                changes = RunChanges.EMPTY,
            )
        }
    }

    fun closeChat() {
        runJob?.cancel()
        clearAsks()
        _state.value = _state.value.copy(
            currentSessionId = null,
            steps = emptyList(),
            busy = false,
            error = null,
            usage = Usage(),
            changes = RunChanges.EMPTY,
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
            _state.value = _state.value.copy(
                steps = enrich(StepMapper.fromMessages(messages)),
                usage = messages.fold(Usage()) { acc, m -> acc + m.usage },
                changes = RunChanges.EMPTY,
                error = null,
            )
        }
    }

    /**
     * Full-text search over the store, when it advertises [SessionSearch]. A
     * blank query clears the results; a store without search is a no-op for the
     * results (the query text still tracks the field).
     */
    fun searchSessions(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        val searchable = store as? SessionSearch ?: return
        if (query.isBlank()) {
            _state.value = _state.value.copy(search = null)
            return
        }
        viewModelScope.launch {
            val hits = runCatching { searchable.search(query) }.getOrDefault(emptyList())
            if (_state.value.searchQuery == query) {
                _state.value = _state.value.copy(search = hits)
            }
        }
    }

    private suspend fun refreshSessions() {
        val rows = store.sessions().map { s ->
            val preview = runCatching { store.latestMessage(s.id) }.getOrNull()
                ?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }
                ?.let { oneLine(it, 90) } ?: ""
            SessionRow(s.id.value, s.title.ifBlank { "chat" }, s.updatedAt, preview)
        }
        _state.value = _state.value.copy(sessions = rows)
    }

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

    // ---- chat ----

    private fun providersFor(provider: String, key: String): SimpleProviderRegistry =
        ProviderCatalogue.registry(provider, key)

    private suspend fun collectEvents() {
        bus.events.collect { e ->
            val current = _state.value.currentSessionId
            if (current == null || e.sessionId.value != current) return@collect
            when (e) {
                is AgentEvent.PartDelta -> {
                    val kind = if (e.kind == DeltaKind.REASONING) StepKind.THINKING else StepKind.ASSISTANT
                    val label = if (kind == StepKind.THINKING) "THINKING" else "ASSISTANT"
                    _state.value = _state.value.copy(
                        steps = StepMapper.applyDelta(_state.value.steps, e.partId.value, e.delta, kind, label),
                    )
                }
                is AgentEvent.PartUpdated -> rebuild(SessionId(current))
                is AgentEvent.ToolFinished -> rebuild(SessionId(current))
                is AgentEvent.ToolCallStarted, is AgentEvent.Progress -> {
                    _state.value = _state.value.copy(steps = StepMapper.applyEvent(_state.value.steps, e))
                }
                is AgentEvent.UsageUpdated -> _state.value = _state.value.copy(usage = e.usage)
                is AgentEvent.FileEdited -> _state.value =
                    _state.value.copy(changes = _state.value.changes + e.edit)
                is AgentEvent.StateChanged -> {
                    val busy = e.state == dev.spindle.core.model.SessionState.RUNNING
                    _state.value = _state.value.copy(busy = busy)
                    if (!busy) refreshSessions()
                }
                is AgentEvent.Error -> _state.value = _state.value.copy(error = e.message)
                else -> Unit
            }
        }
    }

    private suspend fun rebuild(sid: SessionId) {
        _state.value = _state.value.copy(steps = enrich(StepMapper.fromMessages(store.messages(sid))))
    }

    /**
     * Surface what a subagent actually did by inlining its child steps. The
     * collapsed preview is the first 8 rows; the full transcript is fetched on
     * demand ([expandSubagent]) and cached, because [enrich] runs on every
     * streaming rebuild and must not read every child session each time.
     */
    private val childCache = mutableMapOf<String, List<UiStep>>()

    /** Subagent ids the user has explicitly expanded; kept open across rebuilds. */
    private val expandedChildren = mutableSetOf<String>()

    private suspend fun enrich(steps: List<UiStep>): List<UiStep> = steps.map { step ->
        val child = step.childId ?: return@map step
        val kids = childCache.getOrPut(child) {
            runCatching { StepMapper.fromMessages(store.messages(SessionId(child))) }.getOrDefault(emptyList())
        }
        step.copy(
            rows = kids.take(8).map { it.label to it.summary },
            childSteps = if (child in expandedChildren) kids else emptyList(),
        )
    }

    /** Load a subagent's full transcript (on tap) and attach it to the row. */
    fun expandSubagent(step: UiStep) {
        val child = step.childId ?: return
        // Match on `childId`, not `id`: groupSteps rewrites a folded row's id to
        // the preceding THINKING row's id, so `id` may not exist in the raw list.
        fun patch(f: (UiStep) -> UiStep) {
            _state.value = _state.value.copy(
                steps = _state.value.steps.map { if (it.childId == child) f(it) else it },
            )
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
            val kids = runCatching { StepMapper.fromMessages(store.messages(SessionId(child))) }
                .getOrDefault(emptyList())
            childCache[child] = kids
            // Re-attach in both the raw list and the enriched view; enrich() will
            // also pick this up on the next rebuild via childCache.
            patch { it.copy(childSteps = kids, childLoading = false) }
        }
    }

    fun onInput(v: String) { _state.value = _state.value.copy(input = v) }

    fun send() {
        val currentId = _state.value.currentSessionId ?: return
        val sid = SessionId(currentId)
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.busy) return
        val key = keys.apiKey
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(needsKey = true, input = "")
            return
        }

        // Optimistic echo + busy, both SYNCHRONOUS with the tap, so the user
        // sees their own bubble and the stop affordance instantly instead of
        // waiting for the agent's first event round-trip.
        _state.value = _state.value.copy(
            input = "",
            error = null,
            busy = true,
            steps = _state.value.steps + StepMapper.optimisticUser(text),
        )

        runJob = viewModelScope.launch {
            // Title a fresh chat from its first message.
            runCatching {
                val s = store.session(sid)
                if (s != null && (s.title == "new chat" || s.title.isBlank())) {
                    store.updateSession(s.copy(title = oneLine(text, 48), updatedAt = System.currentTimeMillis()))
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
                loop.prompt(sid, text, keys.model, _state.value.agentMode)
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t.toString(), busy = false)
            }
            refreshSessions()
        }
    }

    fun stop() {
        runJob?.cancel()
        clearAsks()
        _state.value = _state.value.copy(busy = false, ask = null)
    }

    override fun onCleared() {
        super.onCleared()
        terminal.shutdown()
        runCatching { ownedStore?.close() }
        runCatching { ownedSnapshots?.close() }
    }

    private fun oneLine(s: String, max: Int): String =
        s.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }

    companion object {
        /** Upper bound on peeked lines; longer files are truncated with a note. */
        const val MAX_PEEK_LINES = 2000

        /** Upper bound on one folder's listing; extra entries are dropped. */
        const val MAX_FILES_ENTRIES = 2000

        /** Upper bound on retained terminal chunks, so a chatty shell cannot grow forever. */
        const val MAX_TERMINAL_CHUNKS = 2000

        fun Factory(context: Context, workspace: java.io.File): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val store = AndroidSessionStore(context)
                    val snapshots = AndroidSnapshotStore(context)
                    return ChatViewModel(
                        workspace = workspace.toPath(),
                        keys = KeyStore(context),
                        store = store,
                        ownedStore = store,
                        snapshots = snapshots,
                        ownedSnapshots = snapshots,
                        shell = AndroidShellExecutor(context),
                    ) as T
                }
            }
    }
}
