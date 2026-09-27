package dev.spindle.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/** Decides whether a tool call may run. The UI supplies a real gate later. */
fun interface PermissionGate {
    suspend fun request(tool: String, detail: String, pattern: String?): Boolean
}

object AllowAll : PermissionGate {
    override suspend fun request(tool: String, detail: String, pattern: String?) = true
}
