package dev.spindle.core.model

import kotlinx.serialization.Serializable

/**
 * One structured file change produced by a mutating tool (`write`, `edit`,
 * `apply_patch`). Unlike a scraped tool result, this carries the exact line
 * range and counts so the UI can render and revert a real diff without
 * re-parsing output.
 */
@Serializable
data class FileEdit(
    val id: String,
    val sessionId: SessionId,
    val path: String,
    val messageId: MessageId? = null,
    /** 1-based first changed line in the new text, when known. */
    val startLine: Int? = null,
    /** 1-based last changed line in the new text, when known. */
    val endLine: Int? = null,
    val added: Int = 0,
    val removed: Int = 0,
    val unifiedDiff: String = "",
    /** True when this edit created the file (no snapshot to restore to). */
    val created: Boolean = false,
    val at: Long = 0L,
)

/**
 * The accumulated changes of one run (or one session), in arrival order.
 * Pure aggregation, so it is JVM-testable and cheap to recompute in the UI.
 */
@Serializable
data class RunChanges(
    val edits: List<FileEdit> = emptyList(),
) {
    val fileCount: Int get() = edits.map { it.path }.distinct().size
    val editCount: Int get() = edits.size
    val added: Int get() = edits.sumOf { it.added }
    val removed: Int get() = edits.sumOf { it.removed }

    fun byFile(): Map<String, List<FileEdit>> = edits.groupBy { it.path }

    operator fun plus(edit: FileEdit): RunChanges = copy(edits = edits + edit)

    companion object {
        val EMPTY = RunChanges()
    }
}

/**
 * A point-in-time copy of a file taken before a mutating tool runs, so any
 * edit the agent makes can be undone. Content is stored whole: a phone project
 * is small, and a partial snapshot cannot restore a deletion.
 */
@Serializable
data class Snapshot(
    val id: String,
    val sessionId: SessionId,
    val path: String,
    val content: String,
    val sha256: String,
    val createdAt: Long,
)
