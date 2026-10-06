package dev.lumen.app.ui.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The timeline joins a run of tool-ish rows (tools, subagents, folded reasoning)
 * so consecutive calls read as one connected strand instead of floating cards.
 * Pure, so no Android is needed.
 */
class StepMapperRunTest {

    private fun step(id: String, kind: StepKind) = UiStep(
        id = id, kind = kind, label = kind.name, tag = "tag$id",
        summary = "s$id", body = "body $id",
    )

    @Test
    fun `a tool thinking tool run links every row below the first`() {
        val out = StepMapper.linkRuns(
            listOf(
                step("t1", StepKind.TOOL),
                step("th", StepKind.THINKING),
                step("t2", StepKind.TOOL),
            ),
        )
        assertFalse(out[0].linkedAbove, "the first row of a run has nothing above it")
        assertTrue(out[1].linkedAbove)
        assertTrue(out[2].linkedAbove)
    }

    @Test
    fun `a tool followed by an assistant answer is not linked`() {
        val out = StepMapper.linkRuns(
            listOf(step("t", StepKind.TOOL), step("a", StepKind.ASSISTANT)),
        )
        assertFalse(out[1].linkedAbove)
    }

    @Test
    fun `an assistant answer followed by a tool is not linked`() {
        val out = StepMapper.linkRuns(
            listOf(step("a", StepKind.ASSISTANT), step("t", StepKind.TOOL)),
        )
        assertFalse(out[1].linkedAbove)
    }

    @Test
    fun `user and question rows break the run`() {
        val withUser = StepMapper.linkRuns(
            listOf(step("t", StepKind.TOOL), step("u", StepKind.YOU), step("t2", StepKind.TOOL)),
        )
        assertFalse(withUser[1].linkedAbove)
        assertFalse(withUser[2].linkedAbove)

        val withQuestion = StepMapper.linkRuns(
            listOf(step("t", StepKind.TOOL), step("q", StepKind.QUESTION), step("t2", StepKind.TOOL)),
        )
        assertFalse(withQuestion[1].linkedAbove)
        assertFalse(withQuestion[2].linkedAbove)
    }

    @Test
    fun `subagents also belong to a run`() {
        val out = StepMapper.linkRuns(
            listOf(step("t", StepKind.TOOL), step("s", StepKind.SUBAGENT)),
        )
        assertTrue(out[1].linkedAbove)
    }

    @Test
    fun `a short or empty list is left untouched`() {
        val single = listOf(step("only", StepKind.TOOL))
        assertFalse(StepMapper.linkRuns(single)[0].linkedAbove)
        assertTrue(StepMapper.linkRuns(emptyList()).isEmpty())
    }
}
