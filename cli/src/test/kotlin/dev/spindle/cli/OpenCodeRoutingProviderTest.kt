package dev.spindle.cli

import dev.spindle.core.provider.ChatRequest
import dev.spindle.core.provider.ModelInfo
import dev.spindle.core.provider.Provider
import dev.spindle.core.provider.ProviderEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The composite's model -> adapter dispatch, exercised with in-memory delegate
 * fakes so no network is involved. The wire details of each adapter are covered
 * by the provider modules' MockWebServer tests.
 */
class OpenCodeRoutingProviderTest {

    private class FakeProvider(override val id: String, private val marker: String) : Provider {
        var streamCount = 0
            private set
        var modelCount = 0
            private set

        override suspend fun models(): List<ModelInfo> {
            modelCount++
            return listOf(ModelInfo(providerId = id, id = "$marker-model"))
        }

        override fun stream(request: ChatRequest): Flow<ProviderEvent> {
            streamCount++
            return flowOf(ProviderEvent.TextDelta(marker))
        }
    }

    private fun request(model: String) =
        ChatRequest(model = model, system = "", messages = emptyList())

    private fun subject(chat: FakeProvider, messages: FakeProvider) = OpenCodeRoutingProvider(
        id = "opencode-go",
        apiKey = "test-key",
        userAgent = "spindle/test",
        chat = chat,
        messages = messages,
    )

    @Test
    fun `claude and qwen route to the messages delegate`() = runBlocking {
        for (model in listOf("claude-sonnet-4", "qwen3.8-flash")) {
            val chat = FakeProvider("opencode-go", "chat")
            val messages = FakeProvider("opencode-go", "messages")

            val events = subject(chat, messages).stream(request(model)).toList()

            assertEquals(listOf(ProviderEvent.TextDelta("messages")), events, model)
            assertEquals(0, chat.streamCount, model)
            assertEquals(1, messages.streamCount, model)
        }
    }

    @Test
    fun `chat models route to the chat delegate`() = runBlocking {
        for (model in listOf("deepseek-v4.1-flash", "glm-5.3-flash", "space-bunny-free")) {
            val chat = FakeProvider("opencode-go", "chat")
            val messages = FakeProvider("opencode-go", "messages")

            val events = subject(chat, messages).stream(request(model)).toList()

            assertEquals(listOf(ProviderEvent.TextDelta("chat")), events, model)
            assertEquals(1, chat.streamCount, model)
            assertEquals(0, messages.streamCount, model)
        }
    }

    @Test
    fun `gpt and grok route to an explicit responses failure without hitting a delegate`() = runBlocking {
        for (model in listOf("gpt-5", "grok-4.7")) {
            val chat = FakeProvider("opencode-go", "chat")
            val messages = FakeProvider("opencode-go", "messages")

            val events = subject(chat, messages).stream(request(model)).toList()

            val failure = events.single() as ProviderEvent.Failure
            assertTrue(failure.message.contains("/responses"), failure.message)
            assertTrue(failure.message.contains(model), failure.message)
            assertEquals(0, chat.streamCount, model)
            assertEquals(0, messages.streamCount, model)
        }
    }

    @Test
    fun `models reads the chat catalogue and stamps the composite provider id`() = runBlocking {
        val chat = FakeProvider("opencode-go", "chat")
        val messages = FakeProvider("opencode-go", "messages")

        val models = subject(chat, messages).models()

        assertEquals(listOf("chat-model"), models.map { it.id })
        assertEquals("opencode-go", models.single().providerId)
        assertEquals(1, chat.modelCount)
        assertEquals(0, messages.modelCount)
    }
}
