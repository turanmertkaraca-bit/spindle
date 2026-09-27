package dev.spindle.cli

import dev.spindle.core.agent.AgentConfig
import dev.spindle.core.agent.AgentLoop
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
import dev.spindle.provider.anthropic.AnthropicProvider
import dev.spindle.provider.openai.OpenAiProvider
import dev.spindle.store.sqlite.SqliteSessionStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

/**
 * Headless harness. This is also the target the `live.yml` workflow drives.
 *
 *   ./gradlew :cli:run --args="--model deepseek/deepseek-flash --prompt 'say hi'"
 *   ./gradlew :cli:run --args="--list-models"
 */
fun main(args: Array<String>) = runBlocking {
    val opts = Args.parse(args)

    if (opts.listModels) {
        val registry = SimpleProviderRegistry(buildProviders())
        registry.models().sortedBy { "${it.providerId}/${it.id}" }.forEach {
            println("${it.providerId}/${it.id}\t${it.contextWindow}\ttools=${it.supportsTools}\treason=${it.supportsReasoning}")
        }
        return@runBlocking
    }

    val providers = buildProviders()
    if (providers.isEmpty()) {
        System.err.println("No providers configured. Set DEEPSEEK_API_KEY, OPENROUTER_API_KEY, OPENCODE_API_KEY or ANTHROPIC_API_KEY.")
        return@runBlocking
    }
    val registry = SimpleProviderRegistry(providers)
    val store = SqliteSessionStore.open(Paths.db(opts.db))
    val bus = EventBus()

    val modelRef = opts.model ?: defaultModel(providers)
    val pwd = Path.of(opts.cwd ?: System.getProperty("user.dir")).absolute()
    if (!Files.isDirectory(pwd)) {
        System.err.println("cwd is not a directory: $pwd"); return@runBlocking
    }

    val loop = AgentLoop(
        providers = registry,
        tools = DefaultTools.registry(),
        store = store,
        bus = bus,
        permissions = { tool, _, _ -> opts.autoApprove || run {
            print("\n[permission] allow $tool? [y/N] "); System.out.flush()
            readLine()?.trim()?.lowercase() in setOf("y", "yes")
        } },
        questions = QuestionGate { _, question, options, _ ->
            println("\n[question] $question")
            options.forEachIndexed { i, o -> println("  ${i + 1}) $o") }
            print("answer (number or text): "); System.out.flush()
            val a = readLine()?.trim().orEmpty()
            val n = a.toIntOrNull()
            if (n != null && n in 1..options.size) listOf(options[n - 1]) else listOf(a)
        },
    )

    val sessionId = SessionId(Ids.new("ses"))
    val now = System.currentTimeMillis()
    store.createSession(
        Session(id = sessionId, cwd = pwd.toString(), createdAt = now, updatedAt = now,
            model = modelRef.substringAfter('/'), providerId = modelRef.substringBefore('/')),
    )

    val printer = launch { render(bus) }

    if (opts.prompt != null) {
        runOnce(loop, sessionId, opts.prompt, modelRef, opts)
    } else {
        println("spindle cli — model=$modelRef cwd=$pwd (type a prompt, blank line to quit)")
        while (true) {
            print("> "); System.out.flush()
            val line = readLine() ?: break
            if (line.isBlank()) break
            runOnce(loop, sessionId, line, modelRef, opts)
        }
    }

    delay(100)
    printer.cancel()
    store.close()
}

private suspend fun runOnce(loop: AgentLoop, sessionId: SessionId, prompt: String, modelRef: String, opts: Args) {
    try {
        loop.prompt(sessionId, prompt, modelRef, AgentConfig(maxSteps = opts.maxSteps))
    } catch (e: Throwable) {
        System.err.println("\n[loop error] ${e.message}")
    }
    println()
}

private suspend fun render(bus: EventBus) {
    var lineStarted = false
    bus.events.collect { e ->
        when (e) {
            is AgentEvent.StateChanged -> {
                if (e.state == dev.spindle.core.model.SessionState.RUNNING && lineStarted) { println(); lineStarted = false }
                println("[${e.state.name.lowercase()}]")
            }
            is AgentEvent.PartDelta -> {
                if (e.kind == DeltaKind.TEXT) { print(e.delta); lineStarted = true }
                // reasoning is intentionally not streamed in the CLI
            }
            is AgentEvent.ToolFinished -> {
                if (lineStarted) { println(); lineStarted = false }
                val r = e.result
                val head = r.output.lineSequence().firstOrNull().orEmpty().take(120)
                println("[tool ${if (r.isError) "ERR" else "ok"}] $head")
            }
            is AgentEvent.Error -> println("[error] ${e.message}")
            is AgentEvent.QuestionAsked -> println("\n[question] ${e.question}")
            else -> Unit
        }
    }
}

private fun buildProviders(): List<Provider> {
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
    env("OPENROUTER_API_KEY")?.let {
        out += OpenAiProvider(baseUrl = "https://openrouter.ai/api/v1", apiKey = it, id = "openrouter")
    }
    // OpenCode Zen + Go. Free routing works without a key; paid routes need OPENCODE_API_KEY.
    val ocKey = env("OPENCODE_API_KEY") ?: ""
    val ocUa = env("SPINDLE_USER_AGENT") ?: "spindle/0.1"
    out += OpenAiProvider(
        baseUrl = "https://opencode.ai/zen/v1", apiKey = ocKey, id = "opencode",
        userAgent = ocUa,
        defaultModels = listOf(
            model("opencode", "deepseek-v4.1-flash", 1_000_000, reasoning = true),
            model("opencode", "glm-5.3-flash", 200_000),
            model("opencode", "kimi-k2.7-code", 200_000),
            model("opencode", "big-pickle", 128_000),
            model("opencode", "space-bunny-free", 128_000),
        ),
    )
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
    ModelInfo(providerId = pid, id = id, label = id, contextWindow = ctx, supportsReasoning = reasoning)

private fun env(k: String) = System.getenv(k)?.takeIf { it.isNotBlank() }

private fun defaultModel(providers: List<Provider>): String = when {
    providers.any { it.id == "deepseek" } -> "deepseek/deepseek-flash"
    providers.any { it.id == "opencode" } -> "opencode/deepseek-v4.1-flash"
    else -> "${providers.first().id}/" + (providers.first().let { it })
        .let { "deepseek-flash" }
}

private object Paths {
    fun db(arg: String?): Path {
        val p = arg?.let { Path.of(it) } ?: Path.of(System.getProperty("java.io.tmpdir"), "spindle", "spindle.db")
        p.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        return p
    }
}

private data class Args(
    val provider: String?,
    val model: String?,
    val prompt: String?,
    val cwd: String?,
    val db: String?,
    val maxSteps: Int?,
    val autoApprove: Boolean,
    val listModels: Boolean,
) {
    companion object {
        fun parse(a: Array<String>): Args {
            var provider: String? = null; var model: String? = null; var prompt: String? = null
            var cwd: String? = null; var db: String? = null; var maxSteps: Int? = null
            var auto = false; var list = false
            var i = 0
            while (i < a.size) {
                when (a[i]) {
                    "--provider" -> provider = a.getOrNull(++i)
                    "--model" -> model = a.getOrNull(++i)
                    "--prompt" -> prompt = a.getOrNull(++i)
                    "--cwd" -> cwd = a.getOrNull(++i)
                    "--db" -> db = a.getOrNull(++i)
                    "--max-steps" -> maxSteps = a.getOrNull(++i)?.toIntOrNull()
                    "--yes", "--auto-approve" -> auto = true
                    "--list-models" -> list = true
                    "--help", "-h" -> { printHelp(); kotlin.system.exitProcess(0) }
                    else -> if (!a[i].startsWith("--")) prompt = a[i]
                }
                i++
            }
            val ref = when {
                model != null && model!!.contains('/') -> model
                model != null && provider != null -> "$provider/$model"
                model != null -> model
                else -> null
            }
            return Args(provider, ref, prompt, cwd, db, maxSteps, auto, list)
        }

        private fun printHelp() {
            println(
                """
                spindle cli
                  --provider <id>     deepseek | openrouter | opencode | opencode-go | anthropic
                  --model <id>        bare id or provider/model
                  --prompt <text>     one-shot prompt (omit for interactive)
                  --cwd <dir>         working directory for tools (default: cwd)
                  --db <file>         sqlite session store (default: tmp)
                  --max-steps <n>     cap agent iterations
                  --yes               auto-approve tool permissions
                  --list-models       list models from configured providers
                """.trimIndent(),
            )
        }
    }
}
