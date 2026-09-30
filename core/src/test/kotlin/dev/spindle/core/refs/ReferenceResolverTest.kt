package dev.spindle.core.refs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceResolverTest {

    private fun exists(vararg files: String): (String) -> Boolean {
        val set = files.toSet()
        return { it in set }
    }

    @Test
    fun `fenced code blocks are inert`() {
        val text = "src/Before.kt\n```\nsrc/Inside.kt\n```\nsrc/After.kt"
        val refs = ReferenceResolver.resolve(text, "/p") { true }
        assertEquals(listOf("src/Before.kt", "src/After.kt"), refs.map { it.path })
    }

    @Test
    fun `fence toggles even when indented and with an info string`() {
        val text = "```kotlin\nsrc/Inside.kt\n   ```\nsrc/Outside.kt"
        val refs = ReferenceResolver.resolve(text, "/p") { true }
        assertEquals(listOf("src/Outside.kt"), refs.map { it.path })
    }

    @Test
    fun `inline code spans are linkable and carry tight offsets`() {
        val text = "see `src/App.kt` now"
        val refs = ReferenceResolver.resolve(text, "/p", exists("src/App.kt"))
        val ref = refs.single()
        assertEquals("src/App.kt", ref.raw)
        assertEquals("src/App.kt", ref.path)
        assertEquals("src/App.kt", text.substring(ref.start, ref.end))
        assertEquals(text.indexOf("src/App.kt"), ref.start)
    }

    @Test
    fun `bare path is linked only when it exists`() {
        val text = "open src/App.kt now"
        assertEquals(
            listOf("src/App.kt"),
            ReferenceResolver.resolve(text, "/p", exists("src/App.kt")).map { it.path },
        )
        assertTrue(ReferenceResolver.resolve(text, "/p") { false }.isEmpty())
    }

    @Test
    fun `path with line parses line`() {
        val ref = ReferenceResolver.resolve("Main.kt:42", "/p", exists("Main.kt")).single()
        assertEquals("Main.kt:42", ref.raw)
        assertEquals("Main.kt", ref.path)
        assertEquals(42, ref.line)
        assertNull(ref.endLine)
        assertNull(ref.column)
        assertEquals("Main.kt:42".length, ref.end)
    }

    @Test
    fun `path with line and column parses both`() {
        val ref = ReferenceResolver.resolve("x.kt:42:7", "/p", exists("x.kt")).single()
        assertEquals("x.kt", ref.path)
        assertEquals(42, ref.line)
        assertEquals(7, ref.column)
        assertNull(ref.endLine)
    }

    @Test
    fun `hash line range parses endLine`() {
        val ref = ReferenceResolver.resolve("x.kt#L10-L20", "/p", exists("x.kt")).single()
        assertEquals("x.kt", ref.path)
        assertEquals(10, ref.line)
        assertEquals(20, ref.endLine)
        assertEquals("x.kt#L10-L20", ref.raw)
    }

    @Test
    fun `hash single line parses`() {
        val ref = ReferenceResolver.resolve("README.md#L5", "/p", exists("README.md")).single()
        assertEquals(5, ref.line)
        assertNull(ref.endLine)
    }

    @Test
    fun `urls are not linked`() {
        val text = "see https://example.com/a.kt and http://x.io/b.kt"
        assertTrue(ReferenceResolver.resolve(text, "/p") { true }.isEmpty())
    }

    @Test
    fun `markdown link targets are not linked`() {
        val text = "see [the file](src/App.kt) now"
        assertTrue(ReferenceResolver.resolve(text, "/p", exists("src/App.kt")).isEmpty())
    }

    @Test
    fun `dot dot that escapes cwd is rejected without an existence check`() {
        var checks = 0
        val gate: (String) -> Boolean = { checks++; true }
        assertTrue(ReferenceResolver.resolve("../App.kt", "/p", gate).isEmpty())
        assertTrue(ReferenceResolver.resolve("src/../../App.kt", "/p", gate).isEmpty())
        assertEquals(0, checks)
    }

    @Test
    fun `dot dot that stays inside cwd collapses`() {
        val ref = ReferenceResolver.resolve("src/../App.kt", "/p", exists("App.kt")).single()
        assertEquals("App.kt", ref.path)
    }

    @Test
    fun `backslashes normalize to forward slashes`() {
        val ref = ReferenceResolver.resolve("open src\\App.kt now", "/p", exists("src/App.kt")).single()
        assertEquals("src/App.kt", ref.path)
    }

    @Test
    fun `absolute paths resolve under cwd and outside cwd is rejected`() {
        assertEquals(
            listOf("src/App.kt"),
            ReferenceResolver.resolve("/p/src/App.kt", "/p", exists("src/App.kt")).map { it.path },
        )
        assertTrue(ReferenceResolver.resolve("/other/App.kt", "/p") { true }.isEmpty())
    }

    @Test
    fun `bare directory stub and windows drive are rejected`() {
        assertTrue(ReferenceResolver.resolve("open foo/ now", "/p") { true }.isEmpty())
        assertTrue(
            ReferenceResolver.resolve("see C:\\Users\\x\\App.kt", "/p") { true }.isEmpty(),
        )
    }

    @Test
    fun `longest match wins with no overlap`() {
        val text = "src/App.kt:42"
        val refs = ReferenceResolver.resolve(text, "/p", exists("src/App.kt"))
        val ref = refs.single()
        assertEquals("src/App.kt", ref.path)
        assertEquals(42, ref.line)
        assertEquals(text.length, ref.end)
    }

    @Test
    fun `tag marks touched paths and leaves the rest`() {
        val refs = ReferenceResolver.resolve("`src/App.kt` and `src/Other.kt`", "/p") { true }
        val tagged = tag(refs, setOf("src/App.kt"))
        assertTrue(tagged.first { it.path == "src/App.kt" }.touched)
        assertFalse(tagged.first { it.path == "src/Other.kt" }.touched)
    }

    @Test
    fun `tag normalizes touched spellings and handles empty input`() {
        val refs = ReferenceResolver.resolve("src/App.kt", "/p") { true }
        assertTrue(tag(refs, setOf("./src\\App.kt")).single().touched)
        assertTrue(tag(emptyList(), setOf("src/App.kt")).isEmpty())
    }
}
