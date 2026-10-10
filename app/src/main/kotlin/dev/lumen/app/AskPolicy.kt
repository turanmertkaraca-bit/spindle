package dev.lumen.app

import dev.spindle.core.tool.AllowAllPolicy
import dev.spindle.core.tool.ApprovalDecision
import dev.spindle.core.tool.ApprovalPolicy
import dev.spindle.core.tool.ApprovalRequest
import dev.spindle.core.tool.ApprovalRules

/**
 * Interactive approval policy: allow a call when its tool or scope pattern has
 * been explicitly remembered by the user, otherwise ask. The pattern source is
 * a lambda so the persisted set is consulted at decide time, not captured once.
 *
 * Remembered rules are globs: an exact string still matches only itself, while
 * `*`/`**`/`?` widen the rule (e.g. `git *` remembers every git invocation, and
 * `bash` remembers the tool by name). A blank request pattern matches nothing.
 */
class PatternApprovalPolicy(
    private val patterns: () -> Set<String>,
) : ApprovalPolicy {
    override suspend fun decide(request: ApprovalRequest): ApprovalDecision {
        val remembered = patterns()
        val pattern = request.pattern?.takeIf { it.isNotBlank() }
        val allowed = (pattern != null && remembered.any { ApprovalRules.matches(it, pattern) }) ||
            remembered.any { ApprovalRules.matches(it, request.tool) }
        return if (allowed) ApprovalDecision.ALLOW else ApprovalDecision.ASK
    }
}

/**
 * The policy the app runs with. Unattended mode (the default) is allow-all;
 * interactive mode consults the remembered pattern set on every decision.
 */
fun approvalFor(askBeforeTools: Boolean, patterns: () -> Set<String>): ApprovalPolicy =
    if (askBeforeTools) PatternApprovalPolicy(patterns) else AllowAllPolicy
