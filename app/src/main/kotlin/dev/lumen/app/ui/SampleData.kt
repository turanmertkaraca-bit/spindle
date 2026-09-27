package dev.lumen.app.ui

import dev.lumen.app.ui.model.StepKind
import dev.lumen.app.ui.model.UiStep

/** The demo content: the same 5-step run used everywhere, plus a subagent. */
fun sampleDemoSteps(): List<UiStep> = listOf(
    UiStep(
        id = "1", kind = StepKind.YOU, label = "YOU", tag = "3fa91c",
        summary = "make the tray x stop clipping and keep my scroll when i come back",
        body = "make the tray x stop clipping and keep my scroll when i come back",
    ),
    UiStep(
        id = "2", kind = StepKind.THINKING, label = "THINKING", tag = "b07e22",
        summary = "the remove-badge draws past the chip bounds with a negative margin",
        body = "the remove-badge draws past the chip bounds with a negative margin - that's what clips.",
    ),
    UiStep(
        id = "3", kind = StepKind.TOOL, label = "1 RUN TESTS", tag = "c1d4a0",
        summary = "571 tests - 0 failed",
        body = "gradle testDebugUnitTest",
        rows = listOf("compile" to "assembleDebug", "test" to "571 passed"),
    ),
    UiStep(
        id = "4", kind = StepKind.TOOL, label = "2 EDIT - ChatActivity.java", tag = "9e0f73",
        summary = "moved the x badge inside the 64dp chip",
        body = "moved the x badge inside the 64dp chip",
        rows = listOf("read" to "lines 2216-2243", "apply" to "+2 -2", "verify" to "re-render"),
    ),
    UiStep(
        id = "5", kind = StepKind.SUBAGENT, label = "SUBAGENT", tag = "47ab10",
        summary = "explore · check every tray for the same clip",
        body = "explore · check every tray for the same clip",
        rows = listOf("scan" to "3 trays found", "diff" to "2 need the inset", "report" to "moved badge out of bounds"),
    ),
    UiStep(
        id = "6", kind = StepKind.ASSISTANT, label = "ASSISTANT", tag = "e2c9f1",
        summary = "Fixed. The badge sits inside the chip now.",
        body = "Fixed. The badge sits inside the chip now, and the scroll position is left alone.",
    ),
)
