package dev.spindle.provider.openai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure unit tests for the OpenCode per-model routing table. No network: the
 * table must resolve to the documented (baseUrl, path, protocol, auth) tuple.
 */
class OpenCodeRoutesTest {

    @Test
    fun `zen and go base urls are distinct and only opencode ids resolve`() {
        assertEquals("https://opencode.ai/zen/v1", OpenCodeRouter.baseUrlFor("opencode"))
        assertEquals("https://opencode.ai/zen/go/v1", OpenCodeRouter.baseUrlFor("opencode-go"))
        assertNull(OpenCodeRouter.baseUrlFor("deepseek"))
        assertNull(OpenCodeRouter.baseUrlFor("openrouter"))
        assertFailsWith<IllegalArgumentException> { OpenCodeRouter.route("deepseek", "deepseek-flash") }
    }

    @Test
    fun `claude family routes to messages via x-api-key on both gateways`() {
        for (id in listOf("claude-sonnet-4", "claude-opus-4-5", "claude-fable-5", "anthropic/claude-3.5-sonnet")) {
            val zen = OpenCodeRouter.route("opencode", id)
            assertEquals(WireProtocol.MESSAGES, zen.protocol, id)
            assertEquals("https://opencode.ai/zen/v1", zen.baseUrl, id)
            assertEquals("/messages", zen.path, id)
            assertEquals(AuthStyle.X_API_KEY, zen.auth, id)
            assertTrue(!zen.requiresSessionHeader, id)
            assertTrue(!zen.requiresCustomUserAgent, id)

            val go = OpenCodeRouter.route("opencode-go", id)
            assertEquals(WireProtocol.MESSAGES, go.protocol, id)
            assertEquals("https://opencode.ai/zen/go/v1", go.baseUrl, id)
            assertEquals("/messages", go.path, id)
            assertEquals(AuthStyle.X_API_KEY, go.auth, id)
            assertTrue(go.requiresSessionHeader, "Go requires x-opencode-session: $id")
            assertTrue(go.requiresCustomUserAgent, "Go requires a custom User-Agent: $id")
        }
    }

    @Test
    fun `qwen routes to messages`() {
        for (id in listOf("qwen3.7-max", "qwen3.8-flash", "qwen-2.5-coder")) {
            assertEquals(WireProtocol.MESSAGES, OpenCodeRouter.wireFor(id), id)
            assertEquals("/messages", OpenCodeRouter.route("opencode", id).path, id)
        }
    }

    @Test
    fun `gpt and grok route to responses with bearer auth`() {
        for (id in listOf("gpt-5", "gpt-5-codex", "gpt-6-astra", "grok-4.7", "grok-build-0.1")) {
            val zen = OpenCodeRouter.route("opencode", id)
            assertEquals(WireProtocol.RESPONSES, zen.protocol, id)
            assertEquals("/responses", zen.path, id)
            assertEquals(AuthStyle.BEARER, zen.auth, id)

            val go = OpenCodeRouter.route("opencode-go", id)
            assertEquals(WireProtocol.RESPONSES, go.protocol, id)
            assertEquals("https://opencode.ai/zen/go/v1", go.baseUrl, id)
            assertTrue(go.requiresSessionHeader, id)
            assertTrue(go.requiresCustomUserAgent, id)
        }
    }

    @Test
    fun `everything else keeps chat completions with bearer auth`() {
        for (id in listOf(
            "deepseek-v4.1-flash",
            "deepseek-v4-pro",
            "glm-5.3-flash",
            "kimi-k2.7-code",
            "big-pickle",
            "space-bunny-free",
            "gemini-3.6-flash",
            "minimax-m3",
        )) {
            val zen = OpenCodeRouter.route("opencode", id)
            assertEquals(WireProtocol.CHAT_COMPLETIONS, zen.protocol, id)
            assertEquals("/chat/completions", zen.path, id)
            assertEquals(AuthStyle.BEARER, zen.auth, id)
            assertEquals("https://opencode.ai/zen/v1", zen.baseUrl, id)

            val go = OpenCodeRouter.route("opencode-go", id)
            assertEquals(WireProtocol.CHAT_COMPLETIONS, go.protocol, id)
            assertEquals("https://opencode.ai/zen/go/v1", go.baseUrl, id)
            assertTrue(go.requiresSessionHeader, id)
            assertTrue(go.requiresCustomUserAgent, id)
        }
    }

    @Test
    fun `classification does not misfire on unrelated vendor tokens`() {
        // "opus" must not be read as an OpenAI o-series token, and a model merely
        // containing "gpt" as a substring of another word must not reroute.
        assertEquals(WireProtocol.CHAT_COMPLETIONS, OpenCodeRouter.wireFor("glm-5.3-flash"))
        assertEquals(WireProtocol.CHAT_COMPLETIONS, OpenCodeRouter.wireFor("big-pickle"))
        assertEquals(WireProtocol.CHAT_COMPLETIONS, OpenCodeRouter.wireFor("kimi-k2.7-code"))
    }
}
