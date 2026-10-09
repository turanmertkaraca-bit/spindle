package dev.spindle.server

import dev.spindle.core.model.FinishReason
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the HTTP surface against an in-memory store and a scripted provider:
 * create a session, send a prompt, then read the stored messages back. Proves
 * the browser preview talks to the same code path as the app, with no network.
 */
class LumenServerTest {

    private class ScriptedProvider(private val events: List<ProviderEvent>) : Provider {
        override val id = "test"
        override suspend fun models() = listOf(ModelInfo("test", "mock-model", contextWindow = 100_000))
        override fun stream(request: ChatRequest): Flow<ProviderEvent> {
            return events.asFlow()
        }
    }

    @Test
    fun `create, send, and read back a turn over HTTP`() {
        val store = InMemorySessionStore()
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.TextDelta("Hel"),
                ProviderEvent.TextDelta("lo"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val web = java.nio.file.Files.createTempDirectory("lumen-web").also {
            java.nio.file.Files.writeString(it.resolve("index.html"), "<html>ok</html>")
        }
        val server = LumenServer(
            store = store,
            providers = listOf(provider),
            webRoot = web,
            cwd = java.nio.file.Path.of(System.getProperty("user.dir")),
        )
        val port = server.start(0)
        try {
            val base = "http://127.0.0.1:$port"

            val created = post("$base/api/sessions", """{"model":"test/mock-model"}""")
            assertTrue(created.contains("\"id\""), created)
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
            assertTrue(id.startsWith("ses"), id)

            val sent = post("$base/api/sessions/$id/send", """{"text":"hi"}""")
            assertTrue(sent.contains("\"ok\":true"), sent)

            // The turn runs on a background coroutine; poll until it lands.
            var body = ""
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                body = get("$base/api/sessions/$id")
                if (body.contains("Hello")) break
                Thread.sleep(100)
            }
            assertTrue(body.contains("Hello"), "assistant text should be stored: $body")
            assertTrue(body.contains("user"), "the user message should round-trip: $body")

            val html = get("$base/")
            assertTrue(html.contains("<html>ok</html>"), html)

            val models = get("$base/api/models")
            assertTrue(models.contains("test/mock-model"), models)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `empty text is rejected`() {
        val web = java.nio.file.Files.createTempDirectory("lumen-web2")
        val server = LumenServer(
            store = InMemorySessionStore(),
            providers = emptyList(),
            webRoot = web,
            cwd = java.nio.file.Path.of(System.getProperty("user.dir")),
        )
        val port = server.start(0)
        try {
            val base = "http://127.0.0.1:$port"
            // no provider configured -> creating is fine, sending a blank text is not
            val created = post("$base/api/sessions", "{}")
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
            val res = postRaw("$base/api/sessions/$id/send", """{"text":"   "}""")
            assertTrue(res.first == 400, "expected 400, got ${res.first}: ${res.second}")
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a Last-Event-ID reconnect replays the missed ring`() {
        val store = InMemorySessionStore()
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.TextDelta("reply-token"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val web = java.nio.file.Files.createTempDirectory("lumen-web-sse")
        val server = LumenServer(
            store = store,
            providers = listOf(provider),
            webRoot = web,
            cwd = java.nio.file.Path.of(System.getProperty("user.dir")),
        )
        val port = server.start(0)
        try {
            val base = "http://127.0.0.1:$port"
            val created = post("$base/api/sessions", """{"model":"test/mock-model"}""")
            val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(created)!!.groupValues[1]
            post("$base/api/sessions/$id/send", """{"text":"ping"}""")

            // The turn runs on a background coroutine; wait until it has emitted
            // its events (and persisted the assistant text) before reconnecting.
            var persisted = ""
            val deadline = System.currentTimeMillis() + 8_000
            while (System.currentTimeMillis() < deadline) {
                persisted = get("$base/api/sessions/$id")
                if (persisted.contains("reply-token")) break
                Thread.sleep(50)
            }
            assertTrue(persisted.contains("reply-token"), "turn should have landed: $persisted")

            // A fresh subscriber that says it saw nothing must catch up from the
            // ring: hello first, then replayed frames each tagged with an id.
            val body = readSse("$base/api/events", "Last-Event-ID" to "0")
            assertTrue(body.contains("\"hello\":true"), body)
            assertTrue(Regex("(?m)^id: \\d+").containsMatchIn(body), "replay frames need seq ids:\n$body")
            assertTrue(body.contains("data: "), "replay frames need data lines:\n$body")
        } finally {
            server.stop()
        }
    }

    private fun get(url: String): String =
        java.net.URI(url).toURL().openStream().use { it.readBytes().toString(Charsets.UTF_8) }

    /** Open an SSE stream, read until we can see the hello + an id-bearing frame. */
    private fun readSse(url: String, header: Pair<String, String>): String {
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty(header.first, header.second)
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        val sb = StringBuilder()
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 4_000
                while (System.currentTimeMillis() < deadline) {
                    val n = try { input.read(buf) } catch (_: Throwable) { -1 }
                    if (n < 0) break
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                    if (sb.contains("\"hello\":true") && Regex("(?m)^id: \\d+").containsMatchIn(sb)) break
                }
            }
        } finally {
            conn.disconnect()
        }
        return sb.toString()
    }

    private fun post(url: String, body: String): String = postRaw(url, body).second

    private fun postRaw(url: String, body: String): Pair<Int, String> {
        val conn = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        conn.disconnect()
        return code to text
    }
}

