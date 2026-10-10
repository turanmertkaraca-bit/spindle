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

    @Test
    fun `a wildcard rule remembers a family of patterns`() = runBlocking {
        val policy = PatternApprovalPolicy { setOf("git *") }
        // The request() pattern is "git", which "git *" does not cover.
        assertEquals(ApprovalDecision.ASK, policy.decide(request()))
        assertEquals(
            ApprovalDecision.ALLOW,
            policy.decide(ApprovalRequest("ses", "bash", "git status", "git status")),
        )
        assertEquals(
            ApprovalDecision.ASK,
            policy.decide(ApprovalRequest("ses", "bash", "npm install", "npm install")),
        )
    }

    @Test
    fun `a rule can match the tool name itself`() = runBlocking {
        val policy = PatternApprovalPolicy { setOf("ba*") }
        assertEquals(
            ApprovalDecision.ALLOW,
            policy.decide(ApprovalRequest("ses", "bash", "anything", "some command")),
        )
        assertEquals(
            ApprovalDecision.ASK,
            policy.decide(ApprovalRequest("ses", "read", "a.kt", "a.kt")),
        )
    }

    @Test
    fun `a blank request pattern never matches a pattern rule`() = runBlocking {
        // "git *" cannot match the tool "read", so only a real pattern could
        // allow this; a null/blank pattern must therefore stay an ASK.
        val policy = PatternApprovalPolicy { setOf("git *") }
        assertEquals(ApprovalDecision.ASK, policy.decide(ApprovalRequest("ses", "read", "x", null)))
        assertEquals(ApprovalDecision.ASK, policy.decide(ApprovalRequest("ses", "read", "x", "   ")))
    }
}
