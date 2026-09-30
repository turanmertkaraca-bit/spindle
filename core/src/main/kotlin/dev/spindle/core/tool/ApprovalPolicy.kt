package dev.spindle.core.tool

/** What to do when a tool wants to run. */
enum class ApprovalDecision { ALLOW, ASK, DENY }

/** A single tool invocation under policy review. */
data class ApprovalRequest(
    val sessionId: String,
    val tool: String,
    /** Human-readable detail (command text, path, …). */
    val detail: String,
    /** The scope key a rule matches on; for bash it is the command, for file tools the path. */
    val pattern: String? = null,
)

/**
 * Decides whether a tool runs, asks, or is refused. This is the decision layer;
 * [PermissionGate] remains the transport that surfaces an ASK to the user. The
 * app used to auto-allow everything — the policy makes that a deliberate choice
 * with persisted "always" memory.
 */
interface ApprovalPolicy {
    suspend fun decide(request: ApprovalRequest): ApprovalDecision

    /** Called after the user answers an ASK, so "always" can be remembered. */
    suspend fun remember(request: ApprovalRequest, decision: ApprovalDecision) = Unit
}

/** Allows every invocation. The pre-policy behaviour, kept for tests and unattended runs. */
object AllowAllPolicy : ApprovalPolicy {
    override suspend fun decide(request: ApprovalRequest) = ApprovalDecision.ALLOW
}

/** Refuses every invocation. Useful for read-only agents. */
object DenyAllPolicy : ApprovalPolicy {
    override suspend fun decide(request: ApprovalRequest) = ApprovalDecision.DENY
}
