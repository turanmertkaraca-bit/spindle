package dev.spindle.server

import java.nio.file.Files
import java.nio.file.Path

/**
 * `./gradlew :server:run` — starts the local preview server.
 *
 * Flags:
 *   --port <n>       (default 8123)
 *   --cwd <dir>      working directory handed to the agent's tools (default: repo root)
 *   --web <dir>      directory with index.html (default: ./web)
 *   --db <file>      sqlite session store (default: tmp)
 *   --no-auto        ask for tool permission in the console instead of allowing
 *
 * Then open http://127.0.0.1:<port> — it serves `web/index.html`, which talks to
 * the real agent over /api + /events (SSE). Edit the HTML and refresh; there is
 * no build step.
 */
fun main(args: Array<String>) {
    var port = 8123
    var cwd = System.getProperty("user.dir")
    var web = repoWebRoot().toString()
    var db = defaultDb().toString()
    var auto = true

    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> port = args.getOrNull(++i)?.toIntOrNull() ?: port
            "--cwd" -> cwd = args.getOrNull(++i) ?: cwd
            "--web" -> web = args.getOrNull(++i) ?: web
            "--db" -> db = args.getOrNull(++i) ?: db
            "--no-auto" -> auto = false
            "--help", "-h" -> {
                println(
                    """
                    spindle server — local UI preview backed by the real agent
                      --port <n>    default 8123
                      --cwd <dir>   tool working directory
                      --web <dir>   directory with index.html
                      --db <file>   sqlite store
                      --no-auto     prompt for tool permissions
                    """.trimIndent(),
                )
                return
            }
        }
        i++
    }

    val providers = buildProviders()
    if (providers.isEmpty()) {
        System.err.println(
            "No providers configured. Set DEEPSEEK_API_KEY, OPENROUTER_API_KEY, " +
                "OPENCODE_API_KEY or ANTHROPIC_API_KEY and try again.",
        )
    }
    val webRoot = Path.of(web)
    if (!Files.isRegularFile(webRoot.resolve("index.html"))) {
        System.err.println("Warning: no index.html in $webRoot — edit --web or add web/index.html")
    }

    val store = openStore(Path.of(db))
    val server = LumenServer(
        store = store,
        providers = providers,
        webRoot = webRoot,
        cwd = Path.of(cwd).toAbsolutePath(),
        autoApprove = auto,
    )
    val bound = server.start(port)
    println("spindle server listening on http://127.0.0.1:$bound")
    println("  web root : $webRoot")
    println("  cwd      : ${Path.of(cwd).toAbsolutePath()}")
    println("  providers: ${providers.joinToString(", ") { it.id }.ifBlank { "(none)" }}")
    println("open the URL, then edit web/index.html and refresh — no rebuild needed")
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { server.stop(); store.close() } })
    Thread.currentThread().join()
}
