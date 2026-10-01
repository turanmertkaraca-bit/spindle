package dev.lumen.app.ui.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * The render path de-dupes step ids last-wins; a duplicate LazyColumn key is a
 * hard crash, so the rule is pinned here.
 */
class StepMapperDedupeTest {

    private fun step(id: String, body: String) = UiStep(
        id = id, kind = StepKind.ASSISTANT, label = "ASSISTANT", tag = "", summary = body, body = body,
    )

    @Test
    fun `a duplicated id keeps the last occurrence in place`() {
        val out = StepMapper.dedupeById(listOf(step("a", "old"), step("b", "b"), step("a", "new")))
        assertEquals(listOf("b", "a"), out.map { it.id }, "order is preserved, later wins")
        assertEquals("new", out.last().body)
    }

    @Test
    fun `distinct ids are untouched`() {
        val input = listOf(step("a", "a"), step("b", "b"), step("c", "c"))
        assertEquals(input, StepMapper.dedupeById(input))
    }

    @Test
    fun `a single step is returned as-is`() {
        val one = step("a", "a")
        assertEquals(listOf(one), StepMapper.dedupeById(listOf(one)))
    }
}
