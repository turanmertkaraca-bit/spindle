package dev.lumen.app.ui.model

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Consecutive tool (and subagent) calls become one droplet. Pure and fast, no
 * Android needed.
 */
class StepMapperMergeTest {

    private fun step(id: String, kind: StepKind) = UiStep(
        id = id, kind = kind, label = kind.name, tag = id,
        summary = "s$id", body = "b$id",
    )

    @Test
    fun `consecutive tools merge into one droplet`() {
        val merged = StepMapper.mergeAdjacent(
            listOf(step("a", StepKind.TOOL), step("b", StepKind.TOOL), step("c", StepKind.TOOL)),
        )
        assertEquals(1, merged.size)
        assertEquals(3, merged[0].merged)
    }

    @Test
    fun `a non-tool step breaks the run`() {
        val merged = StepMapper.mergeAdjacent(
            listOf(
                step("a", StepKind.TOOL), step("b", StepKind.TOOL),
                step("c", StepKind.THINKING),
                step("d", StepKind.TOOL), step("e", StepKind.TOOL),
            ),
        )
        assertEquals(3, merged.size)
        assertEquals(2, merged[0].merged)
        assertEquals(StepKind.THINKING, merged[1].kind)
        assertEquals(2, merged[2].merged)
    }

    @Test
    fun `subagents merge too and promote the cluster`() {
        val merged = StepMapper.mergeAdjacent(
            listOf(step("a", StepKind.TOOL), step("b", StepKind.SUBAGENT)),
        )
        assertEquals(1, merged.size)
        assertEquals(StepKind.SUBAGENT, merged[0].kind)
        assertEquals(2, merged[0].merged)
    }

    @Test
    fun `ordinary messages never merge`() {
        val merged = StepMapper.mergeAdjacent(
            listOf(step("a", StepKind.YOU), step("b", StepKind.ASSISTANT), step("c", StepKind.ASSISTANT)),
        )
        assertEquals(3, merged.size)
        assertEquals(1, merged[0].merged)
    }
}
