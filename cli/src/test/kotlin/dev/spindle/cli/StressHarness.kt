package dev.spindle.cli

import dev.spindle.core.agent.AgentConfig
import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.agent.PermissionGate
import dev.spindle.core.agent.QuestionGate
import dev.spindle.core.event.AgentEvent
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.SessionState
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.provider.openai.OpenAiProvider
import dev.spindle.store.sqlite.SqliteSessionStore
import dev.spindle.store.sqlite.SqliteSnapshotStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.system.exitProcess

/**
 * Long-session STRESS HARNESS for the spindle engine.
 *
 * This is a `main`-class runnable (NOT a JUnit test) so `:cli:classes` compiles
 * it but CI never runs it. Invoke with the `stress` Gradle task; it needs a live
 * provider key and network:
 *
 *   OPENROUTER_API_KEY=... ./gradlew :cli:stress --no-daemon --args="--turns 25"
 *
 * The key is read from the process environment and never written or printed.
 *
 * It constructs the exact object graph the app/CLI build (`DefaultTools.registry()`,
 * a SQLite session store + snapshot store on a temp DB, `EventBus`, `AgentLoop`,
 * an `OpenAiProvider` pointed at OpenRouter), then runs N turns against ONE
 * session while sampling retained heap, open fds, VmRSS, DB size and row counts
 * between turns. It forces a GC before every memory sample and asserts that the
 * session stays bounded and correct, exiting non-zero on any violated invariant.
 */
object StressHarness {

    private const val MAX_HEAP_MB = 256.0
    private const val HEAP_SLOPE_LIMIT_MB_PER_TURN = 4.0
    private const val HEAP_DRIFT_LIMIT_MB = 128.0
    private const val FD_START_SLACK = 24
    private const val DB_TOTAL_LIMIT_MB = 64.0
    private const val WARMUP_TURNS = 3

    private val DEFAULT_MODELS = listOf(
        ModelInfo(
            providerId = "openrouter", id = "openrouter/auto", label = "auto",
            contextWindow = 200_000, supportsTools = true,
        ),
        ModelInfo(
            providerId = "openrouter", id = "openai/gpt-4o-mini", label = "gpt-4o-mini",
            contextWindow = 128_000, supportsTools = true,
        ),
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val cfg = Config.parse(args)
        val key = System.getenv("OPENROUTER_API_KEY")?.trim().orEmpty()
        if (key.isEmpty()) {
            System.err.println("[stress] OPENROUTER_API_KEY is not set in the environment; aborting.")
            exitProcess(2)
        }
        val outcome = runBlocking { execute(cfg, key) }
        println()
        print(outcome.table)
        println()
        if (outcome.violations.isEmpty()) {
            println("[stress] PASS — long session stable over ${outcome.samples.size - 1} turns")
            exitProcess(0)
        } else {
            println("[stress] FAIL — ${outcome.violations.size} invariant(s) violated:")
            outcome.violations.forEach { println("  - $it") }
            exitProcess(1)
        }
    }

    private suspend fun execute(cfg: Config, key: String): Outcome =
        coroutineScope {
            val workDir = (cfg.workDir?.let { Path.of(it) } ?: Files.createTempDirectory("spindle-stress-"))
                .toAbsolutePath()
            Files.createDirectories(workDir)
            val dbPath = cfg.db?.let { Path.of(it).toAbsolutePath() } ?: workDir.resolve("spindle.db")

            println("[stress] model=${cfg.model} turns=${cfg.turns} maxSteps=${cfg.maxSteps}")
            println("[stress] workDir=$workDir")
            println("[stress] db=$dbPath")

            val provider: Provider = OpenAiProvider(
                baseUrl = "https://openrouter.ai/api/v1",
                apiKey = key,
                id = "openrouter",
                defaultModels = DEFAULT_MODELS,
            )
            val registry = SimpleProviderRegistry(listOf(provider))
            val store = SqliteSessionStore.open(dbPath)
            val snapshots = SqliteSnapshotStore.open(dbPath)
            val bus = EventBus()
            val loop = AgentLoop(
                providers = registry,
                tools = DefaultTools.registry(),
                store = store,
                bus = bus,
                permissions = PermissionGate { _, _, _ -> true },
                questions = QuestionGate { _, _, options, _ -> listOf(options.firstOrNull() ?: "ok") },
                snapshots = snapshots,
            )

            val sessionId = SessionId(Ids.new("ses"))
            val now = System.currentTimeMillis()
            // The registry resolves "provider/model"; normalize the same way Main.kt does.
            val slash = cfg.model.indexOf('/')
            val providerId = if (slash > 0) cfg.model.substring(0, slash) else "openrouter"
            val modelId = if (slash > 0) cfg.model.substring(slash + 1) else cfg.model
            store.createSession(
                Session(
                    id = sessionId,
                    cwd = workDir.toString(),
                    createdAt = now,
                    updatedAt = now,
                    model = modelId,
                    providerId = providerId,
                ),
            )

            val events = AtomicLong()
            val subscriber = launch(Dispatchers.Default) {
                bus.events.collect {
                    events.incrementAndGet()
                    if (cfg.subscriberDelayMs > 0) delay(cfg.subscriberDelayMs)
                }
            }

            val samples = ArrayList<Sample>()
            val violations = ArrayList<String>()
            val exceptions = ArrayList<String>()
            val errorTurns = ArrayList<Int>()
            val dbWal = dbPath.resolveSibling(dbPath.fileName.toString() + "-wal")
            val dbShm = dbPath.resolveSibling(dbPath.fileName.toString() + "-shm")

            try {
                samples += sample(0, store, snapshots, sessionId, events.get(), dbPath, dbWal, dbShm)

                for (turn in 1..cfg.turns) {
                    val prompt = promptFor(turn)
                    val startedAt = System.nanoTime()
                    var thrown: Throwable? = null
                    try {
                        loop.prompt(sessionId, prompt, cfg.model, AgentConfig(maxSteps = cfg.maxSteps))
                    } catch (t: Throwable) {
                        thrown = t
                        if (t is kotlinx.coroutines.CancellationException) throw t
                    }
                    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

                    if (thrown != null) {
                        exceptions += "turn $turn: ${thrown.stackTraceToString()}"
                        bus.emit(AgentEvent.Error(sessionId, "turn $turn harness-caught: ${thrown.message}"))
                    }
                    if (store.session(sessionId)?.state == SessionState.ERROR) errorTurns += turn

                    // Force a GC so retained-vs-transient is meaningful.
                    System.gc()
                    delay(60)
                    System.gc()

                    val s = sample(turn, store, snapshots, sessionId, events.get(), dbPath, dbWal, dbShm)
                    samples += s
                    println(row(s, cfg, elapsedMs))
                }

                val table = table(samples, cfg)

                // ---- invariants ----
                val heap = samples.map { it.heapUsedMb }
                val nonZero = samples.drop(1)
                val heapMax = nonZero.maxOfOrNull { it.heapUsedMb } ?: 0.0
                val heapMin = nonZero.minOfOrNull { it.heapUsedMb } ?: 0.0
                val heapLast = nonZero.lastOrNull()?.heapUsedMb ?: 0.0
                val warm = nonZero.filter { it.turn >= WARMUP_TURNS }
                val heapSlope = slopePerTurn(warm.map { it.turn.toDouble() }, warm.map { it.heapUsedMb })

                if (heapMax > MAX_HEAP_MB) {
                    violations += "retained heap peaked at ${fmt(heapMax)} MB (> $MAX_HEAP_MB MB)"
                }
                if (heapSlope > HEAP_SLOPE_LIMIT_MB_PER_TURN) {
                    violations += "retained heap climbing at ${fmt(heapSlope)} MB/turn (> $HEAP_SLOPE_LIMIT_MB_PER_TURN MB/turn)"
                }
                if (heapLast - heapMin > HEAP_DRIFT_LIMIT_MB) {
                    violations += "retained heap drifted ${fmt(heapLast - heapMin)} MB after warmup (> $HEAP_DRIFT_LIMIT_MB MB)"
                }

                val fdStart = samples.first().fdCount
                val fdEnd = samples.last().fdCount
                val fdMax = samples.maxOf { it.fdCount }
                val fdMin = samples.minOf { it.fdCount }
                val nonDecreasing = samples.zipWithNext().all { (a, b) -> b.fdCount >= a.fdCount }
                if (fdEnd - fdStart > FD_START_SLACK) {
                    violations += "fd count grew from $fdStart to $fdEnd (> +$FD_START_SLACK)"
                }
                if (nonDecreasing && fdEnd > fdStart + 2) {
                    violations += "fd count is monotonically non-decreasing ($fdStart -> $fdEnd)"
                }

                val dbStart = samples.first().dbTotalBytes
                val dbEnd = samples.last().dbTotalBytes
                if (dbEnd > DB_TOTAL_LIMIT_MB * 1024 * 1024) {
                    violations += "db total grew to ${fmt(dbEnd / 1024.0 / 1024.0)} MB (> $DB_TOTAL_LIMIT_MB MB)"
                }

                val messages = store.messages(sessionId)
                val userMessages = messages.count { it.role == Role.USER }
                val lastAssistant = messages.lastOrNull { it.role == Role.ASSISTANT }
                val lastAssistantText = lastAssistant?.parts
                    ?.filterIsInstance<Part.Text>()
                    ?.joinToString("") { it.text }
                    ?.trim()
                    .orEmpty()
                val finalState = store.session(sessionId)?.state

                if (exceptions.isNotEmpty()) {
                    exceptions.forEach { violations += "turn threw: $it" }
                }
                if (errorTurns.isNotEmpty()) {
                    violations += "turns ended in ERROR state: $errorTurns"
                }
                if (finalState != SessionState.IDLE) {
                    violations += "final session state is $finalState, expected IDLE"
                }
                if (lastAssistant == null) {
                    violations += "no assistant message was ever persisted"
                } else if (lastAssistantText.isEmpty()) {
                    violations += "final assistant message has no text content"
                }
                if (userMessages != cfg.turns) {
                    violations += "data loss: $userMessages user messages persisted, expected ${cfg.turns}"
                }

                val summary = buildString {
                    appendLine("summary:")
                    appendLine("  heap    min=${fmt(heapMin)}MB max=${fmt(heapMax)}MB last=${fmt(heapLast)}MB slope=${fmt(heapSlope)}MB/turn")
                    appendLine("  fds     start=$fdStart end=$fdEnd min=$fdMin max=$fdMax nonDecreasing=$nonDecreasing")
                    appendLine("  rss     start=${fmt(samples.first().vmRssMb)}MB end=${fmt(samples.last().vmRssMb)}MB")
                    appendLine(
                        "  db      start=${fmt(dbStart / 1024.0)}KB end=${fmt(dbEnd / 1024.0)}KB " +
                            "wal=${fmt(samples.last().walBytes / 1024.0)}KB",
                    )
                    appendLine(
                        "  rows    messages=${samples.last().messages} parts=${samples.last().parts} " +
                            "snapshots=${samples.last().snapshots} todos=${samples.last().todos}",
                    )
                    appendLine("  bus     eventsReceived=${events.get()} subscriberDelayMs=${cfg.subscriberDelayMs}")
                    appendLine("  errors  thrown=${exceptions.size} errorTurns=${errorTurns.size}")
                }

                Outcome(table + "\n" + summary, samples, violations)
            } finally {
                subscriber.cancelAndJoin()
                runCatching { store.close() }
                runCatching { snapshots.close() }
                if (!cfg.keep) {
                    runCatching { workDir.toFile().deleteRecursively() }
                }
            }
        }

    private suspend fun sample(
        turn: Int,
        store: SqliteSessionStore,
        snapshots: SqliteSnapshotStore,
        sessionId: SessionId,
        events: Long,
        dbPath: Path,
        dbWal: Path,
        dbShm: Path,
    ): Sample {
        val rt = Runtime.getRuntime()
        val heapUsed = (rt.totalMemory() - rt.freeMemory())
        val db = fileSize(dbPath)
        val wal = fileSize(dbWal)
        val shm = fileSize(dbShm)
        val messages = store.messages(sessionId)
        return Sample(
            turn = turn,
            heapUsedMb = heapUsed / 1024.0 / 1024.0,
            heapMaxMb = rt.maxMemory() / 1024.0 / 1024.0,
            vmRssMb = vmRssBytes() / 1024.0 / 1024.0,
            fdCount = fdCount(),
            dbBytes = db,
            walBytes = wal,
            shmBytes = shm,
            messages = messages.size,
            parts = messages.sumOf { it.parts.size },
            snapshots = snapshots.forSession(sessionId).size,
            todos = store.todos(sessionId).size,
            events = events,
        )
    }

    private fun row(s: Sample, cfg: Config, elapsedMs: Long): String = String.format(
        Locale.US,
        "turn %2d | heap %7.1fMB | rss %7.1fMB | fds %3d | db %6.1fKB | wal %6.1fKB | msgs %3d | parts %4d | snaps %3d | todos %2d | events %5d | %6dms",
        s.turn, s.heapUsedMb, s.vmRssMb, s.fdCount,
        s.dbBytes / 1024.0, s.walBytes / 1024.0,
        s.messages, s.parts, s.snapshots, s.todos, s.events, elapsedMs,
    )

    private fun table(samples: List<Sample>, cfg: Config): String = buildString {
        appendLine(
            "turn | heapMB | maxMB | rssMB | fds | dbKB | walKB | msgs | parts | snaps | todos | events",
        )
        for (s in samples) {
            appendLine(
                String.format(
                    Locale.US,
                    "%4d | %6.1f | %5.0f | %5.1f | %3d | %5.1f | %5.1f | %4d | %5d | %5d | %5d | %6d",
                    s.turn, s.heapUsedMb, s.heapMaxMb, s.vmRssMb, s.fdCount,
                    s.dbBytes / 1024.0, s.walBytes / 1024.0,
                    s.messages, s.parts, s.snapshots, s.todos, s.events,
                ),
            )
        }
    }

    private fun promptFor(turn: Int): String = when ((turn - 1) % 6) {
        0 -> "Reply with exactly the single word: pong"
        1 -> "Call the write tool once to write the file stress/note.txt with content exactly \"hello turn $turn\". Do not call any other tool."
        2 -> "Call the read tool once to read stress/note.txt, then reply with its exact contents."
        3 -> "Call the edit tool once to replace \"hello\" with \"hi\" in stress/note.txt. Do not call any other tool."
        4 -> "Call the bash tool once to run the command: echo stress-$turn . Then reply with the command output."
        else -> "Call the todowrite tool once to set the todo list to a single item with content \"turn $turn\" and status \"completed\". Do not call any other tool."
    }

    private fun fileSize(p: Path): Long = try {
        if (Files.exists(p)) Files.size(p) else 0L
    } catch (_: Throwable) {
        0L
    }

    private fun fdCount(): Int = try {
        Files.list(Path.of("/proc/self/fd")).use { it.count().toInt() }
    } catch (_: Throwable) {
        try {
            java.io.File("/proc/self/fd").list()?.size ?: -1
        } catch (_: Throwable) {
            -1
        }
    }

    private fun vmRssBytes(): Long = try {
        Files.readAllLines(Path.of("/proc/self/status"))
            .firstOrNull { it.startsWith("VmRSS:") }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.let { it * 1024 } ?: 0L
    } catch (_: Throwable) {
        0L
    }

    /** Least-squares slope of y per unit x. */
    private fun slopePerTurn(xs: List<Double>, ys: List<Double>): Double {
        if (xs.size < 2) return 0.0
        val n = xs.size
        val mx = xs.average()
        val my = ys.average()
        var num = 0.0
        var den = 0.0
        for (i in 0 until n) {
            num += (xs[i] - mx) * (ys[i] - my)
            den += (xs[i] - mx) * (xs[i] - mx)
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)

    private data class Sample(
        val turn: Int,
        val heapUsedMb: Double,
        val heapMaxMb: Double,
        val vmRssMb: Double,
        val fdCount: Int,
        val dbBytes: Long,
        val walBytes: Long,
        val shmBytes: Long,
        val messages: Int,
        val parts: Int,
        val snapshots: Int,
        val todos: Int,
        val events: Long,
    ) {
        val dbTotalBytes: Long get() = dbBytes + walBytes + shmBytes
    }

    private data class Outcome(
        val table: String,
        val samples: List<Sample>,
        val violations: List<String>,
    )

    private data class Config(
        val turns: Int,
        val maxSteps: Int,
        val model: String,
        val workDir: String?,
        val db: String?,
        val subscriberDelayMs: Long,
        val keep: Boolean,
    ) {
        companion object {
            fun parse(args: Array<String>): Config {
                fun str(name: String): String? {
                    val i = args.indexOf(name)
                    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
                }

                return Config(
                    turns = str("--turns")?.toIntOrNull()?.coerceAtLeast(1) ?: 25,
                    maxSteps = str("--max-steps")?.toIntOrNull()?.coerceAtLeast(1) ?: 4,
                    model = str("--model") ?: "openrouter/auto",
                    workDir = str("--work-dir"),
                    db = str("--db"),
                    subscriberDelayMs = str("--subscriber-delay-ms")?.toLongOrNull()?.coerceAtLeast(0) ?: 3L,
                    keep = args.contains("--keep"),
                )
            }
        }
    }
}
