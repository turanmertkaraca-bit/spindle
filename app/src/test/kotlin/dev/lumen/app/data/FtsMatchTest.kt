package dev.lumen.app.data

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The FTS5 MATCH query builder: lowercase, tokenised, prefix-matched. */
class FtsMatchTest {

    @Test
    fun `tokens are prefix-matched and joined`() {
        assertEquals("foo* bar*", ftsMatch("Foo Bar"))
    }

    @Test
    fun `punctuation splits tokens`() {
        assertEquals("src* app* kt*", ftsMatch("src/app.kt"))
    }

    @Test
    fun `a blank or punctuation-only query has no match expression`() {
        assertNull(ftsMatch("   "))
        assertNull(ftsMatch("!!!"))
    }
}
