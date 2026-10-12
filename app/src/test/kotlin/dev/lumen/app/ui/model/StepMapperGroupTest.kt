package dev.lumen.app.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A think block folds into the answer or tool that follows it, so the UI can
 * collapse it once the turn has a real body. Pure, so no Android is needed.
 */
class StepMapperGroupTest {

    private fun step(id: String, kind: StepKind, body: String = "body $id") = UiStep(
        id = id, kind = kind, label = kind.name, tag = "tag$id",
        summary = "s$id", body = body,
    )

    @Test
    fun `thinking folds into the following assistant answer`() {
        val out = StepMapper.groupSteps(
            listOf(
                step("u", StepKind.YOU),
                step("t", StepKind.THINKING, "reasoning here"),
                step("a", StepKind.ASSISTANT, "the answer"),
            ),
        )
        assertEquals(2, out.size)
        assertEquals(StepKind.ASSISTANT, out[1].kind)
        assertEquals("reasoning here", out[1].think)
        assertEquals("tagt", out[1].thinkTag)
        assertEquals("the answer", out[1].body)
    }

    @Test
    fun `the merged row keeps the thinking row's id so the list key never churns`() {
        // Mid-stream: only reasoning exists yet.
        val thinkingOnly = StepMapper.groupSteps(
            listOf(step("u", StepKind.YOU), step("t", StepKind.THINKING, "reasoning")),
        )
        assertEquals("t", thinkingOnly[1].id)

        // A moment later the first answer token arrives and folds in.
        val answered = StepMapper.groupSteps(
            listOf(
                step("u", StepKind.YOU),
                step("t", StepKind.THINKING, "reasoning"),
                step("a", StepKind.ASSISTANT, "the answer"),
            ),
        )
        // Same key as the thinking-only frame: the LazyColumn item grows in place
        // instead of being removed and re-inserted (which caused a scroll jump).
        assertEquals("t", answered[1].id)
    }

    @Test
    fun `thinking folds into a following tool call`() {
        val out = StepMapper.groupSteps(
            listOf(step("t", StepKind.THINKING, "let me check"), step("k", StepKind.TOOL, "read file")),
        )
        assertEquals(1, out.size)
        assertEquals(StepKind.TOOL, out[0].kind)
        assertEquals("let me check", out[0].think)
        assertEquals("read file", out[0].body)
    }

    @Test
    fun `thinking folds into a following subagent`() {
        val out = StepMapper.groupSteps(
            listOf(step("t", StepKind.THINKING, "delegate"), step("s", StepKind.SUBAGENT, "task")),
        )
        assertEquals(1, out.size)
        assertEquals("delegate", out[0].think)
    }

    @Test
    fun `a lone thinking row is left expanded`() {
        val out = StepMapper.groupSteps(listOf(step("u", StepKind.YOU), step("t", StepKind.THINKING, "still working")))
        assertEquals(2, out.size)
        assertEquals(StepKind.THINKING, out[1].kind)
        assertNull(out[1].think)
        assertEquals("still working", out[1].body)
    }

    @Test
    fun `an answer with an empty body still folds the thinking`() {
        val out = StepMapper.groupSteps(
            listOf(step("t", StepKind.THINKING, "reasoning"), step("a", StepKind.ASSISTANT, "")),
        )
        assertEquals(1, out.size)
        assertEquals(StepKind.ASSISTANT, out[0].kind)
        assertEquals("a", out[0].id)
        assertEquals("reasoning", out[0].think)
        assertEquals("", out[0].body)
    }

    @Test
    fun `thinking folds into a running tool with a blank body`() {
        val out = StepMapper.groupSteps(
            listOf(
                step("t", StepKind.THINKING, "let me check"),
                UiStep("k", StepKind.TOOL, "READ", "tagk", "calling…", "", running = true),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(StepKind.TOOL, out[0].kind)
        assertEquals("k", out[0].id)
        assertEquals("let me check", out[0].think)
        assertEquals("tagt", out[0].thinkTag)
        assertEquals("", out[0].body)
    }

    @Test
    fun `thinking folds into a finished tool with a blank body`() {
        val out = StepMapper.groupSteps(
            listOf(
                step("t", StepKind.THINKING, "let me check"),
                UiStep("k", StepKind.TOOL, "READ", "tagk", "no output", ""),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(StepKind.TOOL, out[0].kind)
        assertEquals("k", out[0].id)
        assertEquals("let me check", out[0].think)
        assertEquals("tagt", out[0].thinkTag)
    }

    @Test
    fun `a blank thinking followed by a host does not create a think`() {
        val out = StepMapper.groupSteps(
            listOf(
                step("t", StepKind.THINKING, ""),
                UiStep("k", StepKind.TOOL, "READ", "tagk", "calling…", "", running = true),
            ),
        )
        assertEquals(1, out.size)
        assertEquals(StepKind.TOOL, out[0].kind)
        assertEquals("k", out[0].id)
        assertNull(out[0].think)
    }

    @Test
    fun `a blank thinking with no host stays as the placeholder`() {
        val out = StepMapper.groupSteps(listOf(step("t", StepKind.THINKING, "")))
        assertEquals(1, out.size)
        assertEquals(StepKind.THINKING, out[0].kind)
        assertEquals("", out[0].body)
        assertNull(out[0].think)
    }

    @Test
    fun `consecutive reasoning rows combine`() {
        val out = StepMapper.groupSteps(
            listOf(step("t1", StepKind.THINKING, "one"), step("t2", StepKind.THINKING, "two"), step("a", StepKind.ASSISTANT, "go")),
        )
        assertEquals(1, out.size)
        assertTrue(out[0].think!!.contains("one"))
        assertTrue(out[0].think!!.contains("two"))
    }

    @Test
    fun `a user turn breaks the fold`() {
        val out = StepMapper.groupSteps(
            listOf(step("t", StepKind.THINKING, "reasoning"), step("u", StepKind.YOU, "next question")),
        )
        assertEquals(2, out.size)
        assertEquals(StepKind.THINKING, out[0].kind)
        assertEquals(StepKind.YOU, out[1].kind)
    }
}
