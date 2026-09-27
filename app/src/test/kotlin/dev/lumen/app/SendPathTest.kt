package dev.lumen.app

import dev.lumen.app.ui.model.StepMapper
import dev.spindle.core.agent.AgentConfig
import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.agent.PermissionGate
import dev.spindle.core.agent.QuestionGate
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.provider.openai.OpenAiProvider
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL send path, end to end, against a mock HTTP server: prompt -> OpenAI
 * streaming SSE -> stored message -> UI rows. This is the test that proves
 * "type a prompt and watch it stream" works, without a live API key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendPathTest {

    private val sse = """
        data: {"choices":[{"index":0,"delta":{"content":"Hel"}}]}

        data: {"choices":[{"index":0,"delta":{"content":"lo "}}]}

        data: {"choices":[{"index":0,"delta":{"content":"world"}}]}

        data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

        data: [DONE]

    """.trimIndent()

    @Test
    fun `a prompt streams tokens, persists a message, and becomes UI rows`() = runTest {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sse),
        )
        server.start()

        val store = InMemorySessionStore()
        val bus = EventBus()
        val sid = SessionId(Ids.new("ses"))
        store.createSession(Session(sid, "t", System.getProperty("user.dir"), 0, 0))

        val providers = SimpleProviderRegistry(
            listOf(
                OpenAiProvider(
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "test-key",
                    id = "test",
                    defaultModels = listOf(ModelInfo("test", "mock-model", contextWindow = 100_000)),
                ),
            ),
        )

        val loop = AgentLoop(
            providers = providers,
            tools = DefaultTools.registry(),
            store = store,
            bus = bus,
            permissions = PermissionGate { _, _, _ -> true },
            questions = QuestionGate { _, _, _, _ -> emptyList() },
        )

        loop.prompt(sid, "hi", "test/mock-model", AgentConfig(maxSteps = 3))

        // the request actually went out with auth + model
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertTrue(recorded.getHeader("Authorization")?.startsWith("Bearer ") == true)
        assertTrue(recorded.body.readUtf8().contains("mock-model"))

        // stored, and mapped to rows the UI can draw
        val messages = store.messages(sid)
        assertEquals(2, messages.size) // user + assistant
        val rows = StepMapper.fromMessages(messages)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.body.contains("Hello world") }, "assistant text should be stored: ${rows.map { it.body }}")

        server.shutdown()
    }

    @Test
    fun `a provider error becomes an error state, not a crash`() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Missing API key."}}"""))
        server.start()

        val store = InMemorySessionStore()
        val sid = SessionId(Ids.new("ses"))
        store.createSession(Session(sid, "t", System.getProperty("user.dir"), 0, 0))

        val providers = SimpleProviderRegistry(
            listOf(
                OpenAiProvider(
                    baseUrl = server.url("/v1").toString().trimEnd('/'),
                    apiKey = "",
                    id = "test",
                    defaultModels = listOf(ModelInfo("test", "mock-model")),
                ),
            ),
        )
        val loop = AgentLoop(
            providers = providers,
            tools = DefaultTools.registry(),
            store = store,
            bus = EventBus(),
            permissions = PermissionGate { _, _, _ -> true },
        )

        val result = loop.prompt(sid, "hi", "test/mock-model", AgentConfig(maxSteps = 2))
        // the failure is captured on the message rather than thrown at the caller
        assertTrue(result.error != null || result.finish == dev.spindle.core.model.FinishReason.ERROR)

        server.shutdown()
    }
}
