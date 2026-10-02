package dev.spindle.core.store

import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Outcome of a file revert: either the snapshot was written, or a short reason. */
sealed interface RevertResult {
    object Restored : RevertResult
    data class Failed(val reason: String) : RevertResult
}

/**
 * Pure file-revert engine: writes a recorded [Snapshot] back to disk so the
 * Changes UI can offer "undo". Independent of Android and the tool layer, so it
 * is exercised directly in JVM tests.
 */
object Reverter {

    /**
     * Restore [snapshot] into [cwd]. [Snapshot.path] is a forward-slash relative
     * path and is refused if it normalizes outside [cwd] (`..` or absolute
     * paths), mirroring the tool layer's `resolveInsideCwd`. Missing parent
     * directories are created; the content is written as UTF-8.
     */
    fun revert(snapshot: Snapshot, cwd: Path): RevertResult {
        val base = cwd.toAbsolutePath().normalize()
        val target = base.resolve(snapshot.path).normalize()
        if (target != base && !target.startsWith(base)) {
            return RevertResult.Failed("path escapes cwd: ${snapshot.path}")
        }
        return try {
            val realBase = base.toRealPath()
            if (!insideRealBase(target, realBase)) {
                return RevertResult.Failed("path escapes cwd: ${snapshot.path}")
            }
            target.parent?.let { Files.createDirectories(it) }
            Files.writeString(target, snapshot.content, StandardCharsets.UTF_8)
            RevertResult.Restored
        } catch (e: Exception) {
            RevertResult.Failed("write failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /**
     * True when [target]'s deepest existing ancestor resolves inside [realBase].
     * Catches symlinked paths that pass the lexical check but point outside cwd;
     * a broken symlink cannot be resolved and is rejected.
     */
    private fun insideRealBase(target: Path, realBase: Path): Boolean {
        var p: Path? = target
        while (p != null && !Files.exists(p, LinkOption.NOFOLLOW_LINKS)) p = p.parent
        val existing = p ?: return false
        val real = try {
            existing.toRealPath()
        } catch (e: Exception) {
            return false
        }
        return real == realBase || real.startsWith(realBase)
    }

    /** Load the latest snapshot for [path] and revert it, or `Failed("no snapshot")`. */
    suspend fun revertLatest(
        store: SnapshotStore,
        sessionId: SessionId,
        path: String,
        cwd: Path,
    ): RevertResult {
        val snapshot = store.latest(sessionId, path) ?: return RevertResult.Failed("no snapshot")
        return revert(snapshot, cwd)
    }
}
