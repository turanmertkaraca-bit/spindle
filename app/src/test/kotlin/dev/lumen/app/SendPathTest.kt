package dev.lumen.app

import dev.lumen.app.ui.model.StepMapper
import dev.spindle.core.agent.AgentConfig
import dev.spindle.core.agent.AgentLoop
import dev.spindle.core.agent.PermissionGate
import dev.spindle.core.agent.QuestionGate
import dev.spindle.core.event.EventBus
import dev.spindle.core.model.FinishReason
import dev.spindle.core.model.Ids
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import dev.spindle.core.provider.SimpleProviderRegistry
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.tool.DefaultTools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The REAL send path: prompt -> provider stream -> stored message -> UI rows.
 *
 * Uses an in-process scripted provider rather than HTTP so the test is
 * deterministic and fast; the HTTP adapters have their own tests in
 * :provider-openai. This proves the wiring the app depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SendPathTest {

    private class ScriptedProvider(private val events: List<ProviderEvent>) : Provider {
        override val id = "test"
        val requests = mutableListOf<ChatRequest>()
        override suspend fun models() = listOf(ModelInfo("test", "mock-model", contextWindow = 100_000))
        override fun stream(request: ChatRequest): Flow<ProviderEvent> {
            requests += request
            return events.asFlow()
        }
    }

    @Test
    fun `a prompt streams tokens, persists a message, and becomes UI rows`() = runBlocking {
        val provider = ScriptedProvider(
            listOf(
                ProviderEvent.TextDelta("Hel"),
                ProviderEvent.TextDelta("lo "),
                ProviderEvent.TextDelta("world"),
                ProviderEvent.Finished(FinishReason.STOP),
            ),
        )
        val store = InMemorySessionStore()
        val sid = SessionId(Ids.new("ses"))
        store.createSession(Session(sid, "t", System.getProperty("user.dir"), 0, 0))

        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = DefaultTools.registry(),
            store = store,
            bus = EventBus(),
            permissions = PermissionGate { _, _, _ -> true },
            questions = QuestionGate { _, _, _, _ -> emptyList() },
        )

        loop.prompt(sid, "hi", "test/mock-model", AgentConfig(maxSteps = 3))

        // the request carried the prompt and the model
        assertEquals(1, provider.requests.size)
        assertEquals("mock-model", provider.requests[0].model)

        // stored, and mapped to rows the UI can draw
        val messages = store.messages(sid)
        assertEquals(2, messages.size) // user + assistant
        val rows = StepMapper.fromMessages(messages)
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.body.contains("Hello world") }, "assistant text should be stored: ${rows.map { it.body }}")
    }

    @Test
    fun `a provider error becomes an error state, not a crash`() = runBlocking {
        val provider = ScriptedProvider(listOf(ProviderEvent.Failure("OpenAI HTTP 401: Missing API key.")))
        val store = InMemorySessionStore()
        val sid = SessionId(Ids.new("ses"))
        store.createSession(Session(sid, "t", System.getProperty("user.dir"), 0, 0))

        val loop = AgentLoop(
            providers = SimpleProviderRegistry(listOf(provider)),
            tools = DefaultTools.registry(),
            store = store,
            bus = EventBus(),
            permissions = PermissionGate { _, _, _ -> true },
        )

        val result = loop.prompt(sid, "hi", "test/mock-model", AgentConfig(maxSteps = 2))
        assertEquals(FinishReason.ERROR, result.finish)
        assertTrue(result.error!!.contains("401"))
    }
}
