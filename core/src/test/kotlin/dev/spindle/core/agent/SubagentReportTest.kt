package dev.spindle.core.agent

import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The subagent report contract: **a report always says what happened.**
 *
 * Every case here corresponds to a real failure the owner reported as "subagents
 * don't work". They are pure-logic tests on purpose — the whole reason these bugs
 * survived three rounds of fixes is that the report was assembled inline in the
 * spawner from store reads, where none of these conditions could be exercised.
 */
class SubagentReportTest {

    private fun text(t: String) = Part.Text(PartId("t"), t)
    private fun reasoning(t: String) = Part.Reasoning(PartId("r"), t)

    private fun evidence(
        final: List<Part> = emptyList(),
        earlier: List<List<Part>> = emptyList(),
        tools: List<SubagentReport.ToolUse> = emptyList(),
        files: List<SubagentReport.FileChange> = emptyList(),
        error: String? = null,
        stopReason: String? = null,
    ) = SubagentReport.Evidence(final, earlier, tools, files, error, stopReason)

    // ---- 1. the clean case ----

    @Test
    fun `a substantial final answer is returned verbatim`() {
        // No trailing separator: the report trims surrounding whitespace, and that
        // is intended (a stray trailing space is not information).
        val answer = (1..30).joinToString(" ") { "FINDING$it" }
        val report = SubagentReport.build(evidence(final = listOf(text(answer))))
        assertEquals(answer, report, "a real written answer must pass through untouched")
    }

    // ---- 2. stopped mid-run ----

    @Test
    fun `an earlier substantial answer is used when the child stopped on a tool call`() {
        val findings = "The retry logic lives in Retry.kt:44 and caps backoff at 2 minutes. ".repeat(4)
        val report = SubagentReport.build(
            evidence(
                // Final turn: a tool call, so no text at all.
                final = emptyList(),
                earlier = listOf(listOf(text(findings))),
            ),
        )
        assertTrue(report.contains("Retry.kt:44"), report)
        assertTrue(report.contains("stopped before writing a final answer"), report)
    }

    @Test
    fun `a short mid-run narration is not passed off as the answer`() {
        // The live reproduction: 233k tokens and the "report" was one line.
        val narration = "Let me check the rest of KeyStoreTest and ChatViewModel."
        val report = SubagentReport.build(evidence(final = listOf(text(narration))))
        assertFalse(report.trim() == narration, "a progress line must not be the report")
    }

    // ---- 3. reasoning-only models: the bug nobody had fixed ----

    /**
     * A reasoning model (`deepseek-r1`, `o1`, `opencode-go`) streams
     * `ReasoningDelta` and may never emit a `Part.Text`. The old code read only
     * text parts, so such a child reported "no text" after doing the whole job.
     */
    @Test
    fun `a reasoning-only child reports its reasoning instead of nothing`() {
        val thought = "The bug is that make_move moved the rook in bb but not in occ. " +
            "That desyncs slider attacks. Fix: update occ alongside bb."
        val report = SubagentReport.build(evidence(final = listOf(reasoning(thought))))
        assertTrue(report.contains("make_move"), report)
        assertTrue(report.contains("produced no written answer"), report)
        assertTrue(report.contains("reasoning"), report)
    }

    @Test
    fun `reasoning is trimmed rather than dumped whole`() {
        val huge = "x".repeat(SubagentReport.REASONING_PREVIEW_CHARS * 3)
        val report = SubagentReport.build(evidence(final = listOf(reasoning(huge))))
        assertTrue(report.length < huge.length, "reasoning must be bounded, got ${report.length}")
        assertTrue(report.contains("reasoning trimmed"), report)
    }

    @Test
    fun `prose beats reasoning when both exist`() {
        val prose = (1..40).joinToString(" ") { "ANSWER$it" }
        val report = SubagentReport.build(
            evidence(final = listOf(text(prose), reasoning("some thinking"))),
        )
        assertEquals(prose, report, "a written answer must win over reasoning")
    }

    // ---- 4. did the work, wrote nothing ----

    /**
     * Several real failures looked exactly like this: the child wrote the files and
     * the parent was told "produced no text", so the work was redone from scratch.
     */
    @Test
    fun `a child that changed files but wrote no prose reports its changes`() {
        val report = SubagentReport.build(
            evidence(
                files = listOf(
                    SubagentReport.FileChange("chess/style.js", created = true),
                    SubagentReport.FileChange("chess/README.md", created = false),
                ),
            ),
        )
        assertTrue(report.contains("style.js"), report)
        assertTrue(report.contains("README.md"), report)
        assertTrue(report.contains("+ chess/style.js"), "created files should be marked: $report")
        assertTrue(report.contains("~ chess/README.md"), "modified files should be marked: $report")
        assertTrue(report.contains("did work"), report)
    }

    @Test
    fun `a child that only ran tools reports which tools`() {
        val report = SubagentReport.build(
            evidence(
                tools = listOf(
                    SubagentReport.ToolUse("bash", ok = true, detail = "npm test"),
                    SubagentReport.ToolUse("read", ok = false, detail = "missing.txt"),
                ),
            ),
        )
        assertTrue(report.contains("bash"), report)
        assertTrue(report.contains("npm test"), report)
        assertTrue(report.contains("read"), report)
    }

    @Test
    fun `the file list is capped so a large run stays readable`() {
        val many = (1..100).map { SubagentReport.FileChange("f$it.txt", created = true) }
        val report = SubagentReport.build(evidence(files = many))
        assertTrue(report.contains("…and ${100 - SubagentReport.MAX_LISTED_EDITS} more"), report)
    }

    // ---- 5. nothing happened ----

    @Test
    fun `a child that did nothing says so and why`() {
        val report = SubagentReport.build(evidence(stopReason = "the step budget"))
        assertTrue(report.contains("without producing a report"), report)
        assertTrue(report.contains("step budget"), report)
    }

    @Test
    fun `an error is always surfaced`() {
        val report = SubagentReport.build(evidence(error = "429 rate limited"))
        assertTrue(report.contains("429"), report)
    }

    /**
     * The overall property, stated once: there is no input that produces an empty
     * or placeholder-only report.
     */
    @Test
    fun `no combination of inputs yields an empty report`() {
        val cases = listOf(
            evidence(),
            evidence(final = listOf(text(""))),
            evidence(final = listOf(reasoning(""))),
            evidence(final = listOf(text("ok"))),
            evidence(stopReason = "the tool-call budget"),
            evidence(error = "boom"),
            evidence(tools = listOf(SubagentReport.ToolUse("bash", true, null))),
        )
        cases.forEachIndexed { i, e ->
            val report = SubagentReport.build(e)
            assertTrue(report.isNotBlank(), "case $i produced a blank report")
            assertFalse(
                report.startsWith("(subagent produced no text)"),
                "case $i fell back to the old placeholder: $report",
            )
        }
    }

    // ---- flattening a stored transcript ----

    @Test
    fun `evidenceFrom separates the final message from earlier ones`() {
        val messages = listOf(
            dev.spindle.core.model.Message(
                id = dev.spindle.core.model.MessageId("m1"),
                sessionId = dev.spindle.core.model.SessionId("s"),
                role = dev.spindle.core.model.Role.USER,
                parts = listOf(text("do the thing")),
                createdAt = 0,
            ),
            dev.spindle.core.model.Message(
                id = dev.spindle.core.model.MessageId("m2"),
                sessionId = dev.spindle.core.model.SessionId("s"),
                role = dev.spindle.core.model.Role.ASSISTANT,
                parts = listOf(text("earlier findings ".repeat(20))),
                createdAt = 1,
            ),
            dev.spindle.core.model.Message(
                id = dev.spindle.core.model.MessageId("m3"),
                sessionId = dev.spindle.core.model.SessionId("s"),
                role = dev.spindle.core.model.Role.ASSISTANT,
                parts = listOf(text("final answer")),
                createdAt = 2,
            ),
        )
        val e = SubagentReport.evidenceFrom(messages)
        assertEquals("final answer", e.finalParts.filterIsInstance<Part.Text>().single().text)
        assertEquals(1, e.earlierAssistantParts.size)
        assertTrue(
            e.earlierAssistantParts.single().filterIsInstance<Part.Text>().single().text.contains("earlier findings"),
        )
    }
}
