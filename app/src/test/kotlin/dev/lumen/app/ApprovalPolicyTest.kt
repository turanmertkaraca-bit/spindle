package dev.lumen.app

import dev.spindle.core.tool.ApprovalDecision
import dev.spindle.core.tool.ApprovalRequest
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

/** The app-side approval decision: unattended is allow-all, otherwise pattern-gated. */
class ApprovalPolicyTest {

    private fun request() = ApprovalRequest(
        sessionId = "ses",
        tool = "bash",
        detail = "git status",
        pattern = "git",
    )

    @Test
    fun `interactive mode asks unless the pattern is remembered`() = runBlocking {
        val remembered = mutableSetOf<String>()
        val policy = PatternApprovalPolicy { remembered }

        assertEquals(ApprovalDecision.ASK, policy.decide(request()))

        remembered += "git"
        assertEquals(ApprovalDecision.ALLOW, policy.decide(request()))

        // Patterns are consulted at decision time, so a later grant is seen too.
        remembered.clear()
        assertEquals(ApprovalDecision.ASK, policy.decide(request()))
    }

    @Test
    fun `a tool with no pattern can be remember-allowed by tool name`() = runBlocking {
        val policy = PatternApprovalPolicy { setOf("read") }
        assertEquals(ApprovalDecision.ALLOW, policy.decide(ApprovalRequest("ses", "read", "a.kt", null)))
    }

    @Test
    fun `ask before tools off is allow-all`() = runBlocking {
        val policy = approvalFor(askBeforeTools = false) { setOf("nothing") }
        assertEquals(ApprovalDecision.ALLOW, policy.decide(request()))
    }
}
