package dev.spindle.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.spindle.core.agent.AgentConfig
import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.agent.PermissionGate
import dev.spindle.core.agent.QuestionGate
import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.SessionStore
import dev.spindle.provider.anthropic.AnthropicProvider
import dev.spindle.provider.openai.OpenAiProvider
import dev.spindle.store.sqlite.SqliteSessionStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A tiny local server that puts the real agent behind HTTP + Server-Sent Events,
 * so a browser page can drive it. This exists to iterate on UI ideas quickly:
 * edit `web/index.html`, refresh, and you are looking at the real backend.
 *
 * Endpoints:
 *   GET  /                       -> serves web/index.html (and siblings)
 *   GET  /api/models             -> configured providers + models
 *   GET  /api/sessions           -> recent sessions
 *   POST /api/sessions           -> create a session  { model? }
 *   GET  /api/sessions/{id}      -> stored messages (the rebuild path)
 *   POST /api/sessions/{id}/send -> run a turn         { text, model? }
 *   POST /api/sessions/{id}/stop -> cancel the running turn
 *   GET  /api/events             -> SSE stream of WireEvent (all sessions)
 */
class LumenServer(
    private val store: SessionStore,
    private val providers: List<Provider>,
    private val webRoot: Path,
    private val cwd: Path,
    private val autoApprove: Boolean = true,
) {
    private val bus = EventBus()
    private val json = Json { prettyPrint = false; encodeDefaults = true; ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One live SSE subscriber per connected browser tab. */
    private val subscribers = CopyOnWriteArrayList<SseSubscriber>()

    /**
     * Serializes "add subscriber + replay" against [broadcast], so a reconnect
     * catches up exactly once: no event is lost in the gap and none is sent
     * twice. [SseSubscriber.lastSent] is the second line of defence.
     */
    private val subLock = Any()

    /** The running turn per session, so /stop and re-send behave. */
    private val runs = ConcurrentHashMap<String, Job>()
    private val runLock = Mutex()

    private lateinit var http: HttpServer

    fun start(port: Int, host: String = "127.0.0.1"): Int {
        http = HttpServer.create(InetSocketAddress(host, port), 0)
        http.executor = Executors.newFixedThreadPool(8)
        http.createContext("/", ::handle)
        http.start()
        // Fan the sequenced bus out to every subscriber, so each SSE frame
        // carries the id a reconnecting client can resume from.
        scope.launch { bus.sequenced.collect { entry -> broadcast(entry) } }
        return http.address.port
    }

    fun stop() {
        runCatching { http.stop(0) }
        subscribers.forEach { it.close() }
        scope.launch { }
    }

    private fun broadcast(entry: EventBus.Entry) {
        val payload = payloadOf(entry.event)
        synchronized(subLock) { subscribers.forEach { it.send(payload, entry.seq) } }
    }

    private fun payloadOf(event: AgentEvent): String =
        json.encodeToString(WireEvent.serializer(), WireEvent.of(event))

    // ---- routing ----

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path
            val method = exchange.requestMethod.uppercase()
            when {
                path == "/api/events" && method == "GET" -> sse(exchange)
                path == "/api/models" && method == "GET" -> models(exchange)
                path == "/api/sessions" && method == "GET" -> sessions(exchange)
                path == "/api/sessions" && method == "POST" -> createSession(exchange)
                path.matches(Regex("/api/sessions/[^/]+")) && method == "GET" -> sessionMessages(exchange, path)
                path.matches(Regex("/api/sessions/[^/]+/send")) && method == "POST" -> send(exchange, path)
                path.matches(Regex("/api/sessions/[^/]+/stop")) && method == "POST" -> stop(exchange, path)
                method == "GET" -> staticFile(exchange, path)
                else -> respond(exchange, 404, """{"error":"not found"}""")
            }
        } catch (t: Throwable) {
            respond(exchange, 500, """{"error":${JsonPrimitive(t.message ?: t.toString()).toString()}}""")
        } finally {
            runCatching { exchange.close() }
        }
    }

    // ---- endpoints ----

    private fun models(exchange: HttpExchange) = runBlocking {
        val registry = SimpleProviderRegistry(providers)
        val models = registry.models().sortedBy { it.providerId + "/" + it.id }
        val arr = models.joinToString(",") { m ->
            buildJsonObject {
                put("ref", "${m.providerId}/${m.id}")
                put("provider", m.providerId)
                put("id", m.id)
                put("label", m.label)
                put("contextWindow", m.contextWindow)
                put("supportsTools", m.supportsTools)
                put("supportsReasoning", m.supportsReasoning)
            }.toString()
        }
        respondJson(exchange, """{"models":[$arr],"hasKey":${providers.any { hasKey(it) }}}""")
    }

    private fun sessions(exchange: HttpExchange) = runBlocking {
        val rows = store.sessions().map { s ->
            val preview = runCatching { previewOf(store.messages(s.id)) }.getOrDefault("")
            SessionView(s.id.value, s.title.ifBlank { "chat" }, s.updatedAt, preview)
        }
        respondJson(exchange, json.encodeToString(kotlinx.serialization.builtins.ListSerializer(SessionView.serializer()), rows))
    }

    private fun createSession(exchange: HttpExchange) = runBlocking {
        val body = readJson(exchange)
        val now = System.currentTimeMillis()
        val modelRef = body?.get("model")?.jsonPrimitive?.contentOrNull().orEmpty().ifBlank { defaultModelRef() }
        val id = SessionId(Ids.new("ses"))
        store.createSession(
            Session(
                id = id, title = "new chat", cwd = cwd.toString(),
                createdAt = now, updatedAt = now,
                model = modelRef.substringAfter('/', modelRef),
                providerId = modelRef.substringBefore('/', providers.firstOrNull()?.id ?: "opencode-go"),
            ),
        )
        respondJson(exchange, """{"id":${JsonPrimitive(id.value).toString()}}""")
    }

    private fun sessionMessages(exchange: HttpExchange, path: String) = runBlocking {
        val id = SessionId(path.removePrefix("/api/sessions/"))
        val s = store.session(id)
        if (s == null) {
            respond(exchange, 404, """{"error":"no such session"}""")
            return@runBlocking
        }
        val views = store.messages(id).map { MessageView.of(it) }
        respondJson(
            exchange,
            buildJsonObject {
                put("id", s.id.value)
                put("title", s.title)
                put("model", s.model ?: "")
                put("providerId", s.providerId ?: "")
                put(
                    "messages", kotlinx.serialization.json.JsonArray(
                        views.map {
                            json.encodeToJsonElement(MessageView.serializer(), it)
                        },
                    ),
                )
            }.toString(),
        )
    }

    private fun send(exchange: HttpExchange, path: String) = runBlocking {
        val id = SessionId(path.removePrefix("/api/sessions/").removeSuffix("/send"))
        val body = readJson(exchange)
        val text = body?.get("text")?.jsonPrimitive?.contentOrNull().orEmpty()
        if (text.isBlank()) {
            respond(exchange, 400, """{"error":"empty text"}""")
            return@runBlocking
        }
        if (providers.isEmpty()) {
            respond(exchange, 409, """{"error":"no provider configured; set an API key env var"}""")
            return@runBlocking
        }
        val session = store.session(id)
        if (session == null) {
            respond(exchange, 404, """{"error":"no such session"}""")
            return@runBlocking
        }
        val modelRef = body?.get("model")?.jsonPrimitive?.contentOrNull().orEmpty().ifBlank {
            listOf(session.providerId, session.model).filterNotNull().joinToString("/").ifBlank { defaultModelRef() }
        }

        runLock.withLock {
            runs[id.value]?.cancel()
            val job = scope.launch {
                val self = kotlin.coroutines.coroutineContext[kotlinx.coroutines.Job]
                try {
                    store.updateSession(session.copy(title = titleFrom(text, session.title), updatedAt = System.currentTimeMillis()))
                    val loop = AgentLoop(
                        providers = SimpleProviderRegistry(providers),
                        tools = DefaultTools.registry(),
                        store = store,
                        bus = bus,
                        permissions = PermissionGate { _, _, _ -> autoApprove },
                        questions = QuestionGate { _, _, options, _ -> listOf(options.firstOrNull() ?: "ok") },
                    )
                    loop.prompt(id, text, modelRef, AgentConfig(maxSteps = 16))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // A user stop is not an error and must not be reported as one;
                    // the loop already emits the terminal IDLE. Rethrow so the
                    // cancellation is not swallowed.
                    throw e
                } catch (t: Throwable) {
                    bus.emit(AgentEvent.Error(id, t.message ?: t.toString()))
                    bus.emit(AgentEvent.StateChanged(id, dev.spindle.core.model.SessionState.IDLE))
                } finally {
                    // Remove only our own mapping: a superseding run may already
                    // own runs[id]; removing unconditionally would drop its handle.
                    self?.let { runs.remove(id.value, it) }
                }
            }
            runs[id.value] = job
        }
        respondJson(exchange, """{"ok":true,"model":${JsonPrimitive(modelRef).toString()}}""")
    }

    private fun stop(exchange: HttpExchange, path: String) = runBlocking {
        val id = path.removePrefix("/api/sessions/").removeSuffix("/stop")
        val job = runs.remove(id)
        if (job != null) {
            // The loop's cancellation handler emits the single terminal IDLE;
            // emitting another here would duplicate it.
            job.cancelAndJoin()
            respondJson(exchange, """{"ok":true}""")
        } else {
            respondJson(exchange, """{"ok":false,"reason":"not running"}""")
        }
    }

    // ---- SSE ----

    private fun sse(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("Connection", "keep-alive")
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(200, 0)
        val out = exchange.responseBody
        val sub = SseSubscriber(out)
        sub.send("""{"hello":true}""")
        // A reconnecting client sends the id of the last frame it saw. Add it to
        // the live set and replay anything newer from the ring under [subLock],
        // atomically with broadcast, so it neither misses a frame nor sees one
        // twice. An unparseable/absent header means a fresh subscriber.
        val lastEventId = exchange.requestHeaders.getFirst("Last-Event-ID")?.trim()?.toLongOrNull()
        synchronized(subLock) {
            subscribers += sub
            if (lastEventId != null) {
                bus.replayAfter(lastEventId).forEach { entry -> sub.send(payloadOf(entry.event), entry.seq) }
            }
        }
        try {
            // Hold the connection open until the client goes away.
            while (!sub.closed) {
                Thread.sleep(1000)
                sub.ping()
            }
        } catch (_: Throwable) {
        } finally {
            subscribers -= sub
            runCatching { out.close() }
        }
    }

    private inner class SseSubscriber(private val out: java.io.OutputStream) {
        @Volatile var closed = false
        private val lock = Any()

        /** Highest seq already written; frames at or below it are dropped. */
        private var lastSent = 0L

        fun send(payload: String, seq: Long? = null) {
            synchronized(lock) {
                if (closed) return
                if (seq != null) {
                    if (seq <= lastSent) return
                    lastSent = seq
                }
                runCatching {
                    val frame = buildString {
                        if (seq != null) append("id: ").append(seq).append('\n')
                        append("data: ").append(payload).append("\n\n")
                    }
                    out.write(frame.toByteArray(Charsets.UTF_8))
                    out.flush()
                }.onFailure { closed = true }
            }
        }

        fun ping() {
            synchronized(lock) {
                if (closed) return
                runCatching { out.write(": ping\n\n".toByteArray()); out.flush() }.onFailure { closed = true }
            }
        }

        fun close() {
            closed = true
            runCatching { out.close() }
        }
    }

    // ---- static files ----

    private fun staticFile(exchange: HttpExchange, path: String) {
        val rel = if (path == "/" || path.isBlank()) "index.html" else path.removePrefix("/")
        val target = webRoot.resolve(rel).normalize()
        if (!target.startsWith(webRoot.normalize()) || !Files.isRegularFile(target)) {
            respond(exchange, 404, "not found")
            return
        }
        val bytes = Files.readAllBytes(target)
        exchange.responseHeaders.add("Content-Type", contentType(target))
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun contentType(p: Path): String = when (p.fileName.toString().substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        else -> "application/octet-stream"
    }

    // ---- helpers ----

    private fun hasKey(p: Provider): Boolean = runCatching {
        p.javaClass.getDeclaredField("apiKey").apply { isAccessible = true }.get(p).toString().isNotBlank()
    }.getOrDefault(true)

    private fun defaultModelRef(): String = when {
        providers.any { it.id == "deepseek" } -> "deepseek/deepseek-flash"
        providers.any { it.id == "opencode-go" } -> "opencode-go/deepseek-v4.1-flash"
        providers.any { it.id == "opencode" } -> "opencode/deepseek-v4.1-flash"
        providers.isNotEmpty() -> {
            val p = providers.first()
            "${p.id}/" + runBlocking {
                runCatching { p.models().firstOrNull()?.id }.getOrNull() ?: "default"
            }
        }
        else -> "opencode-go/deepseek-v4.1-flash"
    }

    private fun titleFrom(text: String, current: String): String =
        if (current.isNotBlank() && current != "new chat") current
        else text.replace(Regex("\\s+"), " ").trim().take(48)

    private fun readJson(exchange: HttpExchange): JsonObject? {
        val body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
        if (body.isBlank()) return null
        return runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    }

    private fun respondJson(exchange: HttpExchange, body: String) {
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        respond(exchange, 200, body)
    }

    private fun respond(exchange: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Access-Control-Allow-Origin", "*")
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}

private fun JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

/** Build providers from the same environment variables the CLI uses. */
fun buildProviders(): List<Provider> {
    val out = ArrayList<Provider>()
    env("DEEPSEEK_API_KEY")?.let {
        out += OpenAiProvider(
            baseUrl = "https://api.deepseek.com", apiKey = it, id = "deepseek",
            defaultModels = listOf(
                model("deepseek", "deepseek-flash", 1_000_000, reasoning = true),
                model("deepseek", "deepseek-v4-pro", 1_000_000, reasoning = true),
            ),
        )
    }
    val ocKey = env("OPENCODE_API_KEY") ?: ""
    val ocUa = env("SPINDLE_USER_AGENT") ?: "spindle/0.1"
    env("OPENROUTER_API_KEY")?.let {
        out += OpenAiProvider(baseUrl = "https://openrouter.ai/api/v1", apiKey = it, id = "openrouter")
    }
    out += OpenAiProvider(
        baseUrl = "https://opencode.ai/zen/go/v1", apiKey = ocKey, id = "opencode-go",
        userAgent = ocUa,
        defaultModels = listOf(
            model("opencode-go", "deepseek-v4.1-flash", 1_000_000, reasoning = true),
            model("opencode-go", "glm-5.3-flash", 200_000),
        ),
    )
    env("ANTHROPIC_API_KEY")?.let {
        out += AnthropicProvider(baseUrl = "https://api.anthropic.com", apiKey = it, id = "anthropic")
    }
    return out
}

private fun model(pid: String, id: String, ctx: Int, reasoning: Boolean = false) =
    dev.spindle.core.provider.ModelInfo(
        providerId = pid, id = id, label = id, contextWindow = ctx, supportsReasoning = reasoning,
    )

private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

/** Interactive provider gate used when no key is set: free routing still works. */
fun openStore(db: Path): SqliteSessionStore {
    Files.createDirectories(db.toAbsolutePath().parent)
    return SqliteSessionStore.open(db)
}

fun defaultDb(): Path =
    Path.of(System.getenv("SPINDLE_DB") ?: (System.getProperty("java.io.tmpdir") + "/spindle/spindle-server.db"))

fun defaultWebRoot(): Path {
    val candidates = listOf(
        Path.of(System.getenv("SPINDLE_WEB") ?: ""),
        Path.of(System.getProperty("user.dir"), "web"),
        Path.of(System.getProperty("user.dir"), "..", "web"),
        Path.of(System.getProperty("user.dir"), "..", "..", "web"),
    )
    return candidates.firstOrNull { Files.isRegularFile(it.resolve("index.html")) }
        ?: Path.of(System.getProperty("user.dir"), "web").also { Files.createDirectories(it) }
}

/** The `web/` directory shipped with the repo, found relative to the module. */
fun repoWebRoot(): Path {
    val candidates = listOf(
        File(System.getProperty("user.dir"), "web"),
        File(System.getProperty("user.dir"), "../web"),
        File(System.getProperty("user.dir"), "../../web"),
    )
    return candidates.firstOrNull { File(it, "index.html").isFile }?.toPath()
        ?: File(System.getProperty("user.dir"), "web").toPath()
}
