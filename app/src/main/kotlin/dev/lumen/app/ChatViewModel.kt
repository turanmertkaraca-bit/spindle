package dev.lumen.app

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lumen.app.data.AndroidSessionStore
import dev.lumen.app.data.KeyStore
import dev.lumen.app.data.ProviderCatalogue
import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.StepMapper
import dev.lumen.app.ui.model.UiStep
import dev.spindle.core.agent.AgentConfig
import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.agent.PermissionGate
import dev.spindle.core.agent.QuestionGate
import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.DeltaKind
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Part
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.SessionStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/** One row on the home screen. */
data class SessionRow(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val preview: String,
)

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
    /** The chat currently open, or null on the home screen. */
    val currentSessionId: String? = null,
    /** "system" | "light" | "dark". */
    val theme: String = "system",
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
) : ViewModel() {

    private val bus = EventBus()

    private val _state = MutableStateFlow(
        ChatState(
            model = keys.model,
            provider = keys.provider,
            needsKey = !keys.hasKey,
            theme = keys.theme,
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var runJob: Job? = null

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
            _state.value = _state.value.copy(currentSessionId = id.value, steps = emptyList(), input = "", error = null)
            refreshSessions()
        }
    }

    fun openSession(id: String) {
        viewModelScope.launch {
            val sid = SessionId(id)
            _state.value = _state.value.copy(
                currentSessionId = id,
                steps = enrich(StepMapper.fromMessages(store.messages(sid))),
                input = "",
                error = null,
                busy = false,
            )
        }
    }

    fun closeChat() {
        runJob?.cancel()
        _state.value = _state.value.copy(currentSessionId = null, steps = emptyList(), busy = false, error = null)
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            store.deleteSession(SessionId(id))
            if (_state.value.currentSessionId == id) closeChat()
            refreshSessions()
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
                tools = DefaultTools.registry(),
                store = store,
                bus = bus,
                permissions = PermissionGate { _, _, _ -> true },
                questions = QuestionGate { _, _, options, _ -> listOf(options.firstOrNull() ?: "ok") },
            )
            try {
                loop.prompt(sid, text, keys.model, AgentConfig(maxSteps = 12))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t.toString(), busy = false)
            }
            refreshSessions()
        }
    }

    fun stop() {
        runJob?.cancel()
        _state.value = _state.value.copy(busy = false)
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { ownedStore?.close() }
    }

    private fun oneLine(s: String, max: Int): String =
        s.replace(Regex("\\s+"), " ").trim().let { if (it.length > max) it.take(max) + "…" else it }

    companion object {
        fun Factory(context: Context, workspace: java.io.File): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val store = AndroidSessionStore(context)
                    return ChatViewModel(
                        workspace = workspace.toPath(),
                        keys = KeyStore(context),
                        store = store,
                        ownedStore = store,
                    ) as T
                }
            }
    }
}
