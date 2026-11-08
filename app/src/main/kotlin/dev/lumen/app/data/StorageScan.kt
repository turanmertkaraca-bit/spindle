package dev.lumen.app.data

import java.io.File

/**
 * One entry in a storage drill-down: a file or a directory the user can see the
 * size of and act on.
 *
 * [bytes] is the recursive size for a directory, the length for a file.
 */
data class StorageChild(
    val path: String,
    val name: String,
    val bytes: Long,
    val isDirectory: Boolean,
    /** Files in this subtree, so a 4 GB folder of 200k tiny files is legible. */
    val fileCount: Long = 0,
)

/**
 * Finds what is actually big inside a directory tree.
 *
 * ## Why this exists
 *
 * The owner's report was *"the app uses 10GB, I need to inspect and delete
 * precisely"*. The storage screen could only show **named categories** — total
 * per rootfs, per cache — which answers "which area" but not "which file". With
 * a 10 GB budget that is not enough: the actionable question is almost always
 * "which subdirectory of the workspace is 6 GB", and the screen could not say.
 *
 * ## Design constraints that are easy to get wrong
 *
 * 1. **Never follow symlinks.** A guest rootfs routinely contains symlinks to
 *    `/proc`, `/sys` or outside the sandbox (proot builds them for
 *    compatibility). Following them either loops forever or reports sizes from
 *    outside the app's own storage, which would make the numbers a lie.
 * 2. **Bound the work.** A 10 GB tree of small files is hundreds of thousands of
 *    `stat` calls. A deadline keeps the scan off the user's critical path; a
 *    partial answer is still useful, and [StorageScan.truncated] says so rather
 *    than silently under-reporting.
 * 3. **Never throw.** An unreadable directory (permissions in a guest rootfs)
 *    must not break the whole screen; it is skipped.
 * 4. **Report the aggregate, not just leaves.** Zero-length files bloat a naive
 *    "top files" list; grouping by directory is what makes a big tree readable.
 */
object StorageScan {

    /** Default bound on one drill-down scan. */
    const val DEFAULT_BUDGET_MS = 4_000L

    /** How many children a drill-down returns by default. */
    const val DEFAULT_LIMIT = 40

    /**
     * Result of scanning one directory.
     *
     * [truncated] means the deadline was hit before every descendant was visited,
     * so the numbers are a **lower bound**. Surfacing that is the difference
     * between "the app says 4 GB" and "the app says at least 4 GB", and the second
     * is honest.
     */
    data class Result(
        val children: List<StorageChild>,
        val totalBytes: Long,
        val truncated: Boolean,
    )

    /**
     * List the immediate children of [dir], each with its recursive size, largest
     * first.
     *
     * Directories and files are both returned: a user clearing space needs to see
     * "workspace/projectA 6.2 GB" and "workspace/notes.md 4 KB" in the same list,
     * sorted by what matters.
     */
    fun children(
        dir: File,
        limit: Int = DEFAULT_LIMIT,
        budgetMs: Long = DEFAULT_BUDGET_MS,
        now: () -> Long = System::currentTimeMillis,
    ): Result {
        val deadline = now() + budgetMs
        if (!dir.isDirectory) return Result(emptyList(), 0L, false)

        val entries = runCatching { dir.listFiles() }.getOrNull() ?: return Result(emptyList(), 0L, false)
        val out = ArrayList<StorageChild>(entries.size)
        var truncated = false

        for (entry in entries) {
            if (now() > deadline) {
                truncated = true
                break
            }
            val measured = measure(entry, deadline, now)
            if (measured.truncated) truncated = true
            out += StorageChild(
                path = entry.absolutePath,
                name = entry.name,
                bytes = measured.bytes,
                isDirectory = entry.isDirectory,
                fileCount = measured.files,
            )
        }

        out.sortWith(compareByDescending<StorageChild> { it.bytes }.thenBy { it.name })
        return Result(
            children = if (out.size > limit) out.subList(0, limit).toList() else out,
            totalBytes = out.sumOf { it.bytes },
            truncated = truncated || out.size > limit,
        )
    }

    /** Recursive size + file count, without following symlinks, bounded by [deadline]. */
    fun measure(
        entry: File,
        deadline: Long,
        now: () -> Long = System::currentTimeMillis,
    ): Measured {
        // A symlink is counted as a link, never traversed: see the class doc.
        if (isSymlink(entry)) {
            return Measured(bytes = 0L, files = 0L, truncated = false)
        }
        if (entry.isFile) return Measured(entry.length(), 1L, false)
        if (!entry.isDirectory) return Measured(0L, 0L, false)

        var bytes = 0L
        var files = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(entry)
        while (stack.isNotEmpty()) {
            if (now() > deadline) return Measured(bytes, files, truncated = true)
            val current = stack.removeLast()
            val listed = runCatching { current.listFiles() }.getOrNull() ?: continue
            for (child in listed) {
                if (isSymlink(child)) continue
                when {
                    child.isDirectory -> stack.addLast(child)
                    child.isFile -> {
                        bytes += runCatching { child.length() }.getOrDefault(0L)
                        files++
                    }
                }
            }
        }
        return Measured(bytes, files, false)
    }

    /** Result of [measure]. */
    data class Measured(val bytes: Long, val files: Long, val truncated: Boolean)

    /**
     * True when [file] is a symbolic link.
     *
     * `File.isSymlink` does not exist on all API levels, and `canonicalPath`
     * resolves the whole chain (expensive and can throw), so compare the absolute
     * path with its canonical form — the check the JDK documents for this purpose.
     */
    fun isSymlink(file: File): Boolean = runCatching {
        file.absolutePath != file.canonicalPath
    }.getOrDefault(true)
}
