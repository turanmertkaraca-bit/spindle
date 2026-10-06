package dev.spindle.tool

import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.tool.SubagentResult
import dev.spindle.core.tool.SubagentSpec
import dev.spindle.core.tool.ToolContext
import dev.spindle.core.tool.ToolProgress
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val searchJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun searchObj(text: String): JsonObject = searchJson.parseToJsonElement(text) as JsonObject

/** Minimal ToolContext that records permission calls and needs no sandbox. */
private class SearchContext(private val allowed: Boolean = true) : ToolContext {
    override val sessionId = SessionId("search-session")
    override val cwd = Files.createTempDirectory("spindle-websearch-test")
    override val session = Session(id = sessionId, cwd = cwd.toString(), createdAt = 0L, updatedAt = 0L)
    var requests: Int = 0
    var lastTool: String? = null
    var lastDetail: String? = null
    var lastPattern: String? = null

    override suspend fun requestPermission(tool: String, detail: String, pattern: String?): Boolean {
        requests++
        lastTool = tool
        lastDetail = detail
        lastPattern = pattern
        return allowed
    }

    override suspend fun ask(question: String, options: List<String>, multiple: Boolean): List<String> =
        emptyList()

    override fun emit(event: ToolProgress) {}
    override suspend fun checkAborted() {}
    override suspend fun setTodos(todos: List<TodoItem>) {}
    override suspend fun todos(): List<TodoItem> = emptyList()
    override suspend fun subagent(spec: SubagentSpec): SubagentResult =
        SubagentResult(sessionId, "", ok = false)
}

class WebSearchToolTest {
    @Test
    fun specDeclaresWebsearchContract() {
        val spec = WebSearchTool().spec
        assertEquals("websearch", spec.name)
        assertTrue(spec.description.isNotBlank())

        val schema = searchJson.parseToJsonElement(spec.parametersJson) as JsonObject
        assertEquals("object", (schema["type"] as JsonPrimitive).content)
        val properties = schema["properties"] as JsonObject
        assertEquals(setOf("query", "count", "recency"), properties.keys)
        val required = (schema["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertEquals(listOf("query"), required)
        assertEquals("false", (schema["additionalProperties"] as JsonPrimitive).content)
    }

    @Test
    fun missingQueryIsError() = runTest {
        val outcome = WebSearchTool { error("must not fetch") }.run(searchObj("{}"), SearchContext())
        assertTrue(outcome.isError)
        assertTrue(outcome.output.contains("query"), outcome.output)
    }

    @Test
    fun blankQueryIsError() = runTest {
        val outcome = WebSearchTool { error("must not fetch") }
            .run(searchObj("""{"query":"   "}"""), SearchContext())
        assertTrue(outcome.isError)
        assertTrue(outcome.output.contains("blank", ignoreCase = true), outcome.output)
    }

    @Test
    fun permissionDeniedIsErrorAndSkipsFetch() = runTest {
        var fetches = 0
        val ctx = SearchContext(allowed = false)
        val outcome = WebSearchTool { fetches++; SAMPLE }.run(searchObj("""{"query":"kotlin"}"""), ctx)

        assertTrue(outcome.isError)
        assertTrue(outcome.output.contains("denied", ignoreCase = true), outcome.output)
        assertEquals(0, fetches)
        assertEquals(1, ctx.requests)
        assertEquals("websearch", ctx.lastTool)
        assertEquals("kotlin", ctx.lastDetail)
        assertEquals("html.duckduckgo.com", ctx.lastPattern)
    }

    @Test
    fun happyPathRendersNumberedResults() = runTest {
        var requested: URI? = null
        val ctx = SearchContext()
        val outcome = WebSearchTool { requested = it; SAMPLE }
            .run(searchObj("""{"query":"kotlin language","count":2}"""), ctx)

        assertFalse(outcome.isError, outcome.output)
        assertTrue(outcome.output.contains("1. Kotlin Programming Language — https://kotlinlang.org/"), outcome.output)
        assertTrue(outcome.output.contains("  Kotlin is a concise & safe language."), outcome.output)
        assertTrue(outcome.output.contains("2. Example 'site' — https://example.com/page?a=1"), outcome.output)
        assertEquals("2", outcome.metadata["results"])
        assertEquals("html.duckduckgo.com", requested?.host)
        assertTrue(requested?.query?.contains("q=kotlin+language") == true, requested?.query)
    }

    @Test
    fun recencyIsPassedForKnownWordsAndIgnoredOtherwise() = runTest {
        var requested: URI? = null
        val tool = WebSearchTool { requested = it; SAMPLE }

        tool.run(searchObj("""{"query":"news","recency":"week"}"""), SearchContext())
        assertTrue(requested?.query?.contains("df=w") == true, requested?.query)

        requested = null
        tool.run(searchObj("""{"query":"news","recency":"bogus"}"""), SearchContext())
        assertFalse(requested?.query?.contains("df=") == true, requested?.query)
    }

    @Test
    fun countIsClampedToTheLimit() = runTest {
        val tool = WebSearchTool { MANY }
        val one = tool.run(searchObj("""{"query":"x","count":1}"""), SearchContext())
        assertEquals("1", one.metadata["results"])
        assertFalse(one.output.contains("\n2. "), one.output)

        val many = tool.run(searchObj("""{"query":"x","count":999}"""), SearchContext())
        assertEquals("3", many.metadata["results"])
    }

    @Test
    fun networkFailureIsError() = runTest {
        val outcome = WebSearchTool { throw RuntimeException("boom") }
            .run(searchObj("""{"query":"x"}"""), SearchContext())
        assertTrue(outcome.isError)
        assertTrue(outcome.output.contains("Search failed"), outcome.output)
        assertTrue(outcome.output.contains("boom"), outcome.output)
    }

    @Test
    fun unparseablePageIsError() = runTest {
        val outcome = WebSearchTool { "<html><body>nothing here</body></html>" }
            .run(searchObj("""{"query":"x"}"""), SearchContext())
        assertTrue(outcome.isError)
        assertTrue(outcome.output.contains("No results", ignoreCase = true), outcome.output)
        assertEquals("false", outcome.metadata["blocked"])
    }

    @Test
    fun challengePageIsReportedAsBlockedNotNoResults() = runTest {
        val challenge = """
            <html><body>
              <div class="anomaly-modal__box" data-testid="anomaly-modal">
                Unfortunately, bots use DuckDuckGo too.
              </div>
              <form id="challenge-form" action="//duckduckgo.com/anomaly.js"></form>
            </body></html>
        """.trimIndent()
        val outcome = WebSearchTool { challenge }
            .run(searchObj("""{"query":"kotlin"}"""), SearchContext())
        assertTrue(outcome.isError)
        assertTrue(outcome.output.contains("blocked", ignoreCase = true), outcome.output)
        assertTrue(!outcome.output.contains("No results", ignoreCase = true), outcome.output)
        assertEquals("true", outcome.metadata["blocked"])
    }

    private companion object {
        val SAMPLE = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F">Kotlin Programming Language</a>
            <a class="result__snippet">Kotlin is a concise &amp; safe language.</a>
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage%3Fa%3D1">Example &#39;site&#39;</a>
            <a class="result__snippet">A &lt;test&gt; snippet</a>
        """.trimIndent()

        val MANY = buildString {
            repeat(3) { i ->
                append("""<a class="result__a" href="https://example.com/$i">Result $i</a>""")
                append("""<a class="result__snippet">snippet $i</a>""")
            }
        }
    }
}
