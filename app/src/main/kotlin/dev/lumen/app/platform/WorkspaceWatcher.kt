package dev.lumen.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Directories that never meaningfully contribute to the Changes view but can
 * hold tens of thousands of files (or churn constantly). They are pruned from
 * every snapshot walk.
 */
internal val HEAVY_DIRS = setOf(".git", "node_modules", "build", ".gradle", ".idea", ".spindle")

/** Safety bounds so a pathological tree cannot pin the poller forever. */
private const val MAX_FILES = 20_000
private const val MAX_DEPTH = 40

/** A bounded walk's result: its entries plus whether the file cap cut it short. */
internal data class Snapshot(
    val entries: Map<String, Long>,
    /** True when the tree held more files than [snapshotDetailed]'s cap allowed. */
    val truncated: Boolean,
)

/**
 * Walk the regular files under [root] and return a map of workspace-relative
 * paths (always forward-slash separated) to a cheap fingerprint
 * (`lastModified() xor size`). Directories whose name is in [skip], symlinks,
 * and trees deeper than [MAX_DEPTH] or larger than [MAX_FILES] are ignored.
 *
 * This is deliberately pure and JVM-testable: it only touches the passed
 * [File] tree and never throws — an unreadable or missing [root] yields an
 * empty map.
 */
internal fun snapshot(root: File, skip: Set<String> = HEAVY_DIRS): Map<String, Long> =
    snapshotDetailed(root, skip).entries

/**
 * The full result of [snapshot]'s walk. [truncated] is true when a file was
 * skipped because the walk had already reached [maxFiles], so the caller can
 * warn that the listing is partial. [maxFiles] is a test seam.
 */
internal fun snapshotDetailed(
    root: File,
    skip: Set<String> = HEAVY_DIRS,
    maxFiles: Int = MAX_FILES,
): Snapshot {
    val out = HashMap<String, Long>()
    val rootPath = root.absoluteFile.toPath().normalize()
    if (!rootPath.toFile().isDirectory) return Snapshot(out, truncated = false)

    val stack = ArrayDeque<Pair<File, Int>>()
    stack.addLast(rootPath.toFile() to 0)
    var truncated = false

    walk@ while (stack.isNotEmpty()) {
        val (dir, depth) = stack.removeLast()
        if (depth > MAX_DEPTH) continue
        val children = dir.listFiles() ?: continue
        for (child in children) {
            val path = child.toPath()
            if (Files.isSymbolicLink(path)) continue
            if (child.isDirectory) {
                if (child.name in skip) continue
                stack.addLast(child to depth + 1)
            } else if (child.isFile) {
                // Check the cap before recording, so a tree that ends exactly at
                // the cap is not mislabelled as truncated.
                if (out.size >= maxFiles) {
                    truncated = true
                    break@walk
                }
                val rel = rootPath.relativize(path.normalize())
                    .toString()
                    .replace(File.separatorChar, '/')
                if (rel.isEmpty()) continue
                out[rel] = child.lastModified() xor child.length()
            }
        }
    }
    return Snapshot(out, truncated)
}

/**
 * Workspace-relative directories under [root] (forward-slash separated),
 * skipping [skip], symlinks, and trees deeper than [MAX_DEPTH]. Files are
 * ignored: this exists only so the Files view can re-list when a script creates
 * a directory that the file-only [snapshot] would otherwise not notice.
 */
internal fun snapshotDirs(root: File, skip: Set<String> = HEAVY_DIRS): Set<String> {
    val out = HashSet<String>()
    val rootPath = root.absoluteFile.toPath().normalize()
    if (!rootPath.toFile().isDirectory) return out

    val stack = ArrayDeque<Pair<File, Int>>()
    stack.addLast(rootPath.toFile() to 0)

    while (stack.isNotEmpty()) {
        val (dir, depth) = stack.removeLast()
        if (depth > MAX_DEPTH) continue
        val children = dir.listFiles() ?: continue
        for (child in children) {
            if (!child.isDirectory || Files.isSymbolicLink(child.toPath())) continue
            if (child.name in skip) continue
            val rel = rootPath.relativize(child.toPath().normalize())
                .toString()
                .replace(File.separatorChar, '/')
            if (rel.isEmpty()) continue
            out += rel
            stack.addLast(child to depth + 1)
        }
    }
    return out
}

/**
 * Paths that were added, removed, or whose fingerprint changed between two
 * snapshots.
 */
internal fun diff(before: Map<String, Long>, after: Map<String, Long>): Set<String> {
    val changed = HashSet<String>()
    for ((path, fingerprint) in after) {
        if (before[path] != fingerprint) changed += path
    }
    for (path in before.keys) {
        if (path !in after) changed += path
    }
    return changed
}

/**
 * Polls [root] and reports indirect workspace writes — files changed by a
 * script the agent ran rather than through the app's own edit tools — so the
 * Changes view can surface them.
 *
 * The watcher is dumb on purpose: it keeps one previous snapshot and emits the
 * [diff] on each tick. It never emits on the initial snapshot, never throws,
 * and is safe to [start]/[stop] from any thread.
 */
class WorkspaceWatcher(
    private val root: File,
    private val scope: CoroutineScope,
    private val intervalMs: Long = 1500,
    private val onChange: (Set<String>) -> Unit,
) {
    /**
     * Fired only when the walk crosses into or out of truncation (the tree held
     * more files than [maxFiles]). No-op by default; set before [start].
     */
    var onTruncated: (Boolean) -> Unit = {}

    /** Test seam: the file cap for the walk. Lower it to exercise truncation. */
    internal var maxFiles: Int = MAX_FILES

    private val started = AtomicBoolean(false)
    private var job: Job? = null

    @Volatile
    private var previous: Map<String, Long> = emptyMap()

    @Volatile
    private var previousDirs: Set<String> = emptySet()

    @Volatile
    private var previousTruncated: Boolean = false

    /** Take the baseline snapshot and begin polling. A second call is a no-op. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        val baseline = snapshotDetailed(root, maxFiles = maxFiles)
        previous = baseline.entries
        previousDirs = snapshotDirs(root)
        if (baseline.truncated != previousTruncated) {
            previousTruncated = baseline.truncated
            runCatching { onTruncated(baseline.truncated) }
        }
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                val current = withContext(Dispatchers.IO) { snapshotDetailed(root, maxFiles = maxFiles) }
                val currentDirs = withContext(Dispatchers.IO) { snapshotDirs(root) }
                // Include directory adds/removes so a newly created (possibly
                // empty) folder still triggers a Files re-list.
                val changed = diff(previous, current.entries) +
                    (currentDirs - previousDirs) +
                    (previousDirs - currentDirs)
                previous = current.entries
                previousDirs = currentDirs
                // Only a genuine transition is worth telling the UI about.
                if (current.truncated != previousTruncated) {
                    previousTruncated = current.truncated
                    runCatching { onTruncated(current.truncated) }
                }
                if (changed.isNotEmpty()) {
                    runCatching { onChange(changed) }
                }
            }
        }
    }

    /** Stop polling. Idempotent; [start] may be called again afterwards. */
    fun stop() {
        started.set(false)
        job?.cancel()
        job = null
        // A fresh start re-announces truncation rather than inheriting stale state.
        previousTruncated = false
    }
}
