package dev.lumen.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import dev.lumen.app.data.AndroidSessionStore
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
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.SessionStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/** What the screen needs to draw, in one immutable snapshot. */
data class ChatState(
    val steps: List<UiStep> = emptyList(),
    val busy: Boolean = false,
    val input: String = "",
    val error: String? = null,
    val model: String = "",
    /** True until an API key is saved — the screen shows the key form instead. */
    val needsKey: Boolean = true,
)

/**
 * Owns the agent loop and turns it into UI state. One session, one store, one
 * bus — the same objects the backend tests exercise, so what runs here is the
 * code that is proven.
 */
class ChatViewModel(
    private val workspace: Path,
    private val keys: KeyStore,
    private val store: SessionStore,
    private val ownedStore: AutoCloseable? = null,
) : ViewModel() {

    private val bus = EventBus()
    private val sessionId = SessionId(Ids.new("ses"))

    private val _state = MutableStateFlow(
        ChatState(
            model = keys.model,
            needsKey = !keys.hasKey,
        ),
    )
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var runJob: Job? = null

    init {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            store.createSession(
                Session(
                    id = sessionId,
                    title = "chat",
                    cwd = workspace.toString(),
                    createdAt = now,
                    updatedAt = now,
                    model = keys.model.substringAfter('/', keys.model),
                    providerId = keys.model.substringBefore('/', "opencode-go"),
                ),
            )
            _state.value = _state.value.copy(steps = StepMapper.fromMessages(store.messages(sessionId)))
            collectEvents()
        }
    }

    /** Called by the key screen. Persists and re-enables sending. */
    fun saveKey(provider: String, key: String) {
        keys.provider = provider
        keys.apiKey = key
        keys.model = KeyStore.defaultModel(provider)
        _state.value = _state.value.copy(needsKey = !keys.hasKey, model = keys.model, error = null)
    }

    fun clearKey() {
        keys.apiKey = null
        _state.value = _state.value.copy(needsKey = true, error = null)
    }

    private fun providersFor(provider: String, key: String): SimpleProviderRegistry {
        val ua = "lumen/0.1"
        val list = buildList {
            add(
                dev.spindle.provider.openai.OpenAiProvider(
                    baseUrl = "https://opencode.ai/zen/go/v1",
                    apiKey = key, id = "opencode-go", userAgent = ua,
                    defaultModels = listOf(
                        ModelInfo("opencode-go", "deepseek-v4.1-flash", contextWindow = 1_000_000, supportsReasoning = true),
                        ModelInfo("opencode-go", "glm-5.3-flash", contextWindow = 200_000),
                        ModelInfo("opencode-go", "kimi-k2.7-code", contextWindow = 200_000),
                    ),
                ),
            )
            add(
                dev.spindle.provider.openai.OpenAiProvider(
                    baseUrl = "https://api.deepseek.com",
                    apiKey = key, id = "deepseek", userAgent = ua,
                    defaultModels = listOf(
                        ModelInfo("deepseek", "deepseek-flash", contextWindow = 1_000_000, supportsReasoning = true),
                    ),
                ),
            )
            add(
                dev.spindle.provider.openai.OpenAiProvider(
                    baseUrl = "https://openrouter.ai/api/v1",
                    apiKey = key, id = "openrouter", userAgent = ua,
                ),
            )
        }
        return SimpleProviderRegistry(list)
    }

    private suspend fun collectEvents() {
        bus.events.collect { e ->
            when (e) {
                is AgentEvent.PartDelta -> {
                    val kind = if (e.kind == DeltaKind.REASONING) StepKind.THINKING else StepKind.ASSISTANT
                    val label = if (kind == StepKind.THINKING) "THINKING" else "ASSISTANT"
                    _state.value = _state.value.copy(
                        steps = StepMapper.applyDelta(_state.value.steps, e.partId.value, e.delta, kind, label),
                    )
                }
                is AgentEvent.PartUpdated -> {
                    // after the turn, rebuild from the store — the source of truth
                    _state.value = _state.value.copy(steps = StepMapper.fromMessages(store.messages(sessionId)))
                }
                is AgentEvent.ToolFinished -> {
                    _state.value = _state.value.copy(steps = StepMapper.fromMessages(store.messages(sessionId)))
                }
                is AgentEvent.StateChanged -> {
                    val busy = e.state == dev.spindle.core.model.SessionState.RUNNING
                    _state.value = _state.value.copy(busy = busy)
                }
                is AgentEvent.Error -> {
                    _state.value = _state.value.copy(error = e.message)
                }
                else -> Unit
            }
        }
    }

    fun onInput(v: String) { _state.value = _state.value.copy(input = v) }

    fun send() {
        val text = _state.value.input.trim()
        if (text.isEmpty() || _state.value.busy) return
        val key = keys.apiKey
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(needsKey = true, input = "")
            return
        }
        _state.value = _state.value.copy(input = "", error = null)

        runJob = viewModelScope.launch {
            val loop = AgentLoop(
                providers = providersFor(keys.provider, key),
                tools = DefaultTools.registry(),
                store = store,
                bus = bus,
                permissions = PermissionGate { _, _, _ -> true },
                questions = QuestionGate { _, _, options, _ ->
                    // no UI gate yet: take the first option, or acknowledge
                    listOf(options.firstOrNull() ?: "ok")
                },
            )
            try {
                loop.prompt(sessionId, text, keys.model, AgentConfig(maxSteps = 12))
            } catch (t: Throwable) {
                _state.value = _state.value.copy(error = t.message ?: t.toString(), busy = false)
            }
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
