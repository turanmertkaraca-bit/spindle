package dev.lumen.app.platform

/** One entry in the workspace repo's recent history. */
data class GitCommit(
    val hash: String,
    val shortHash: String,
    val subject: String,
    val author: String,
    val relativeTime: String,
)

/**
 * A read-only snapshot of the LOCAL workspace repository for the GitHub activity
 * view: current branch, origin remote, dirty state and recent commits. [error] is
 * set for an expected failure (git missing, not a repository) while every other
 * field keeps its default, so the UI can render one calm empty state.
 */
data class GitActivity(
    val isRepo: Boolean = false,
    val repo: String = "",
    val branch: String = "",
    val remote: String = "",
    val dirty: Boolean = false,
    val changedFiles: Int = 0,
    val commits: List<GitCommit> = emptyList(),
    val error: String? = null,
)
