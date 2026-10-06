package dev.spindle.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WebSearchParserTest {
    @Test
    fun parsesTitlesSnippetsAndUnwrapsRedirectUrls() {
        val hits = WebSearchParser.parse(SAMPLE_HTML, 10)

        assertEquals(2, hits.size)

        val first = hits[0]
        assertEquals("Kotlin Programming Language", first.title)
        assertEquals("https://kotlinlang.org/", first.url)
        assertEquals("Kotlin is a concise & safe language.", first.snippet)

        val second = hits[1]
        assertEquals("Example 'site'", second.title)
        assertEquals("https://example.com/page?a=1", second.url)
        assertEquals("A <test> snippet", second.snippet)
    }

    @Test
    fun decodesEntitiesInTitles() {
        val html = """
            <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.com">
              Tom &amp; Jerry &#39;quotes&#39; &#x2713;
            </a>
        """.trimIndent()

        val hits = WebSearchParser.parse(html, 10)
        assertEquals(1, hits.size)
        assertEquals("Tom & Jerry 'quotes' ✓", hits[0].title)
    }

    @Test
    fun noResultsPageYieldsEmptyList() {
        val html = """
            <html><body>
              <div class="no-results">No results found for "zzzz".</div>
              <a href="https://duckduckgo.com/">Back</a>
            </body></html>
        """.trimIndent()

        assertTrue(WebSearchParser.parse(html, 10).isEmpty())
    }

    @Test
    fun unclosedAnchorYieldsNothingWithoutCrashing() {
        val html = """<a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fx.com">Unclosed"""
        assertTrue(WebSearchParser.parse(html, 10).isEmpty())
    }

    @Test
    fun unquotedAttributesAreStillParsed() {
        val html = """<a class=result__a href=https://example.com/raw>Raw</a>"""
        val hits = WebSearchParser.parse(html, 10)
        assertEquals(1, hits.size)
        assertEquals("Raw", hits[0].title)
        assertEquals("https://example.com/raw", hits[0].url)
    }

    @Test
    fun respectsLimit() {
        val html = buildString {
            repeat(5) { i ->
                append("""<a class="result__a" href="https://example.com/$i">Result $i</a>""")
                append("""<a class="result__snippet">snippet $i</a>""")
            }
        }

        val hits = WebSearchParser.parse(html, 3)
        assertEquals(3, hits.size)
        assertEquals("Result 0", hits[0].title)
        assertEquals("Result 2", hits[2].title)
    }

    @Test
    fun ignoresNonResultAnchors() {
        val html = """
            <a class="nav-link" href="https://duckduckgo.com/">Prev</a>
            <a class="result__a" href="https://direct.example/">Direct</a>
            <a class="result__snippet">A direct hit.</a>
        """.trimIndent()

        val hits = WebSearchParser.parse(html, 10)
        assertEquals(1, hits.size)
        assertEquals("https://direct.example/", hits[0].url)
        assertEquals("A direct hit.", hits[0].snippet)
    }

    @Test
    fun isChallengePageDetectsAnomalyMarkup() {
        val challenge = """
            <html><body>
              <div class="anomaly-modal__box" data-testid="anomaly-modal">
                Unfortunately, bots use DuckDuckGo too.
              </div>
              <form id="challenge-form" action="//duckduckgo.com/anomaly.js"></form>
            </body></html>
        """.trimIndent()
        assertTrue(WebSearchParser.isChallengePage(challenge))
        assertTrue(WebSearchParser.isChallengePage("<img src=\"../assets/anomaly/images/challenge/x.jpg\">"))
    }

    @Test
    fun isChallengePageIsFalseForRealResultsAndNoResultPages() {
        assertTrue(!WebSearchParser.isChallengePage(SAMPLE_HTML))
        assertTrue(!WebSearchParser.isChallengePage("""<div class="no-results">No results found.</div>"""))
    }

    @Test
    fun unwrapUrlHandlesProtocolRelativeDirectAndRedirect() {
        assertEquals("https://x.com/", WebSearchParser.unwrapUrl("//x.com/"))
        assertEquals(
            "https://example.com/q?a=1&b=2",
            WebSearchParser.unwrapUrl("https://example.com/q?a=1&b=2"),
        )
        assertEquals(
            "https://wrapped.example/path",
            WebSearchParser.unwrapUrl(
                "//duckduckgo.com/l/?uddg=https%3A%2F%2Fwrapped.example%2Fpath&rut=z",
            ),
        )
    }

    @Test
    fun unwrapUrlRejectsNonHttpSchemes() {
        assertEquals("", WebSearchParser.unwrapUrl("javascript:alert(1)"))
        assertEquals("", WebSearchParser.unwrapUrl("data:text/html,<h1>x</h1>"))
        assertEquals("", WebSearchParser.unwrapUrl("ftp://example.com/x"))
    }

    @Test
    fun parseOfAdversarialAnchorSoupIsLinear() {
        val html = "<a>".repeat(20_000) + "x".repeat(20_000)
        val started = System.nanoTime()
        val hits = WebSearchParser.parse(html, 10)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(hits.isEmpty())
        assertTrue(elapsedMs < 3_000, "parse took ${elapsedMs}ms")
    }

    private companion object {
        val SAMPLE_HTML = """
            <html><body>
            <div class="result results_links results_links_deep web-result">
              <div class="links_main links_deep result__body">
                <h2 class="result__title">
                  <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F&amp;rut=abc">Kotlin Programming Language</a>
                </h2>
                <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fkotlinlang.org%2F">Kotlin is a concise &amp; safe language.</a>
              </div>
            </div>
            <div class="result results_links">
              <div class="result__body">
                <h2 class="result__title">
                  <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage%3Fa%3D1&amp;rut=x">Example &#39;site&#39;</a>
                </h2>
                <a class="result__snippet" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fpage%3Fa%3D1">A &lt;test&gt; snippet</a>
              </div>
            </div>
            </body></html>
        """.trimIndent()
    }
}
