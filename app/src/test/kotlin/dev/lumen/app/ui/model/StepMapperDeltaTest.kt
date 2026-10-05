package dev.lumen.app.ui.model

import dev.spindle.core.event.AgentEvent
import dev.spindle.core.model.PartId
import dev.spindle.core.model.SessionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Streaming deltas must land as the exact concatenation of their tokens, and a
 * long stream must not pay O(n) per token. [StepMapper.applyDelta] keeps its
 * single-shot behaviour; [StepMapper.DeltaBuffer] is the cheap accumulator the
 * view model keeps for a whole turn. Pure, no Android needed.
 */
class StepMapperDeltaTest {

    private fun step(id: String, body: String) = UiStep(
        id = id, kind = StepKind.ASSISTANT, label = "ASSISTANT", tag = "",
        summary = body, body = body,
    )

    @Test
    fun `applyDelta creates a new row on first sight`() {
        val out = StepMapper.applyDelta(emptyList(), "p1", "hello", StepKind.ASSISTANT, "ASSISTANT")
        assertEquals(1, out.size)
        assertEquals("p1", out[0].id)
        assertEquals(StepKind.ASSISTANT, out[0].kind)
        assertEquals("hello", out[0].body)
        assertEquals("hello", out[0].summary)
        assertEquals(StepMapper.tag("p1"), out[0].tag)
    }

    @Test
    fun `applyDelta grows the existing row and keeps its other fields`() {
        val base = UiStep(
            id = "p1", kind = StepKind.THINKING, label = "THINKING", tag = "t",
            summary = "old", body = "old", running = true, merged = 3,
        )
        val out = StepMapper.applyDelta(listOf(base), "p1", " text", StepKind.THINKING, "THINKING")
        assertEquals(1, out.size)
        assertEquals("old text", out[0].body)
        assertEquals("old text", out[0].summary)
        assertTrue(out[0].running)
        assertEquals(3, out[0].merged)
    }

    @Test
    fun `ten thousand deltas concatenate exactly through the buffer`() {
        val parts = (0 until 10_000).map { "tok$it " }
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(emptyList())
        for (p in parts) buffer.append("p1", p, StepKind.ASSISTANT, "ASSISTANT")
        val rows = buffer.snapshot()
        assertEquals(1, rows.size)
        assertEquals(parts.joinToString(""), rows[0].body)
    }

    @Test
    fun `coalesced buffer matches applying every delta directly`() {
        val deltas = (0 until 500).map { if (it % 3 == 0) " chunk$it " else "x" }
        val direct = deltas.fold(emptyList<UiStep>()) { acc, d ->
            StepMapper.applyDelta(acc, "p1", d, StepKind.ASSISTANT, "ASSISTANT")
        }
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(emptyList())
        for (d in deltas) buffer.append("p1", d, StepKind.ASSISTANT, "ASSISTANT")
        buffer.snapshot()
        // A second burst folds on top, still matching the direct path.
        for (d in deltas) buffer.append("p1", d, StepKind.ASSISTANT, "ASSISTANT")
        val buffered = buffer.snapshot()
        assertEquals(direct.single().body + direct.single().body, buffered.single().body)
    }

    @Test
    fun `hasPending tracks unflushed deltas`() {
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(emptyList())
        assertTrue(!buffer.hasPending())
        buffer.append("p1", "a", StepKind.ASSISTANT, "ASSISTANT")
        assertTrue(buffer.hasPending())
        buffer.snapshot()
        assertTrue(!buffer.hasPending())
    }

    @Test
    fun `transform folds pending text in before a structural change`() {
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(listOf(step("p1", "")))
        buffer.append("p1", "reasoning", StepKind.ASSISTANT, "ASSISTANT")
        val tool = UiStep(
            id = "call:1", kind = StepKind.TOOL, label = "READ", tag = "t",
            summary = "calling…", body = "",
        )
        buffer.transform { it + tool }
        val rows = buffer.snapshot()
        assertEquals(2, rows.size)
        assertEquals("reasoning", rows.first { it.id == "p1" }.body)
        assertEquals("call:1", rows[1].id)
    }

    @Test
    fun `rebase extends a store row the buffered tail is ahead of`() {
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(listOf(step("p1", "hello")))
        buffer.append("p1", " world", StepKind.ASSISTANT, "ASSISTANT")
        buffer.rebase(listOf(step("p1", "hello")))
        assertEquals("hello world", buffer.snapshot().single { it.id == "p1" }.body)
    }

    @Test
    fun `rebase does not duplicate text the store already has`() {
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(listOf(step("p1", "hello")))
        buffer.append("p1", " world", StepKind.ASSISTANT, "ASSISTANT")
        buffer.rebase(listOf(step("p1", "hello world")))
        assertEquals("hello world", buffer.snapshot().single { it.id == "p1" }.body)
    }

    @Test
    fun `rebase replays a part the store snapshot does not know yet`() {
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(emptyList())
        buffer.append("p1", "fresh", StepKind.THINKING, "THINKING")
        buffer.rebase(emptyList())
        val rows = buffer.snapshot()
        assertEquals(1, rows.size)
        assertEquals("fresh", rows[0].body)
        assertEquals(StepKind.THINKING, rows[0].kind)
    }

    @Test
    fun `clearPart drops the accumulated text and allows re-accumulation`() {
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(listOf(step("p1", ""), step("p2", "keep"), step("p3", "")))
        buffer.append("p1", "stale ", StepKind.ASSISTANT, "ASSISTANT")
        buffer.append("p1", "attempt", StepKind.ASSISTANT, "ASSISTANT")
        buffer.append("p2", " more", StepKind.ASSISTANT, "ASSISTANT")

        buffer.clearPart("p1")
        val cleared = buffer.snapshot()
        assertTrue(cleared.none { it.id == "p1" }, "the failed attempt's row must disappear")
        assertEquals("keep more", cleared.single { it.id == "p2" }.body, "other parts are untouched")

        // A retried attempt recreates the row from its own deltas, not the old text.
        buffer.append("p1", "fresh", StepKind.ASSISTANT, "ASSISTANT")
        assertEquals("fresh", buffer.snapshot().single { it.id == "p1" }.body)

        // Clearing a store-backed row with no buffered body also drops it.
        buffer.clearPart("p3")
        assertTrue(buffer.snapshot().none { it.id == "p3" })
    }

    @Test
    fun `applyEvent PartReset drops the failed part row`() {
        val current = listOf(step("p1", "stale"), step("p2", "keep"))
        val out = StepMapper.applyEvent(
            current,
            AgentEvent.PartReset(SessionId("s"), "m", PartId("p1")),
        )
        assertEquals(listOf("p2"), out.map { it.id })
    }

    @Test
    fun `snapshot summarizes with collapsed whitespace and preserves fields`() {
        val buffer = StepMapper.DeltaBuffer()
        buffer.reset(
            listOf(
                UiStep(
                    id = "p1", kind = StepKind.ASSISTANT, label = "ASSISTANT", tag = "t",
                    summary = "", body = "", running = true, messageId = "m1",
                ),
            ),
        )
        buffer.append("p1", "  a \n\n  b  ", StepKind.ASSISTANT, "ASSISTANT")
        val row = buffer.snapshot().single()
        assertEquals("a b", row.summary)
        assertEquals("  a \n\n  b  ", row.body)
        assertTrue(row.running)
        assertEquals("m1", row.messageId)
    }
}
