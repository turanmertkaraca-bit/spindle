package dev.lumen.app.platform

import android.util.Log
import java.io.File

/**
 * Reaps guest processes left behind by a previous app process.
 *
 * ## Why this exists
 *
 * The owner's report: *"sometimes the terminal gets locked as well, restarting
 * doesn't fix it somehow"*. Two distinct causes produce that exact symptom, and
 * only one of them is the admission gate:
 *
 * 1. **Slot starvation** — commands and the terminal shared one flat pool, so the
 *    agent could take every permit. Restarting did not help because the next run
 *    re-took them immediately. Fixed by the terminal reservation in
 *    `ResourcePolicy`.
 * 2. **Orphaned processes** — guest shells and `proot` trees live under
 *    `filesDir`, and they are children of the *app process*. When Android kills
 *    the app (a low-memory kill, or "Force stop"), the children are not
 *    necessarily reaped, and `setsid` deliberately detaches them from the app's
 *    process group so that a group TERM during a *normal* run does not also kill
 *    the app. The result is a `proot`/shell tree that keeps the workspace, a lock
 *    file, or a port, and outlives every restart. A new app process then starts a
 *    second shell against the same state and appears to be "locked".
 *
 * This object addresses (2). It runs once per process, early, and removes guest
 * processes whose parent is no longer alive.
 *
 * ## Why it is conservative
 *
 * Killing the wrong process would be far worse than leaving an orphan alive, so
 * every rule here is chosen to *not* match:
 *
 * - Only processes whose executable lives under this app's own `filesDir` are
 *   candidates. Another app's sandbox, or a system process, is never touched —
 *   and on Android an app cannot signal those anyway.
 * - Only processes that are **orphaned or reparented** are matched: a live parent
 *   that is still alive means the tree is in use by a running instance, and
 *   killing it would break exactly the work we are trying to protect.
 * - `pid 1` and anything whose pid cannot be parsed is skipped.
 *
 * Reading `/proc` is allowed on an Android app for its own uid's processes, which
 * is all this needs.
 */
internal object OrphanReaper {

    private const val TAG = "OrphanReaper"

    /** One `PID PPID` pair from `/proc`, with the executable it is running. */
    internal data class ProcEntry(val pid: Int, val ppid: Int, val exe: String?)

    /**
     * Pids that must be signalled: candidates whose parent is dead.
     *
     * Pure so it is a JVM test. [selfPid] is excluded along with its own children,
     * because a live app process deliberately owns a live shell.
     */
    internal fun orphans(
        procs: List<ProcEntry>,
        selfPid: Int,
        aliveParents: Set<Int>,
        root: String,
    ): List<Int> {
        val normalisedRoot = root.trimEnd('/') + "/"
        return procs.asSequence()
            // Only guests: the executable must live inside our own sandbox root.
            .filter { it.exe != null && it.exe.startsWith(normalisedRoot) }
            .filter { it.pid > 1 && it.pid != selfPid }
            // A parent that is still alive means the tree is in use.
            .filter { it.ppid !in aliveParents }
            // A parent of 0 cannot be resolved (kernel threads) — never a guest.
            .filter { it.ppid > 0 }
            .map { it.pid }
            .distinct()
            .toList()
    }

    /**
     * Kill this app's orphaned guest processes. Returns how many were signalled.
     *
     * Never throws: this runs on the boot path and a failure to reap must not stop
     * the app from starting.
     */
    fun reap(root: File, selfPid: Int = currentPid()): Int = runCatching {
        val alive = livePids()
        val candidates = readProcs().let { procs ->
            orphans(procs, selfPid, alive, root.absolutePath)
        }
        if (candidates.isEmpty()) return 0
        var killed = 0
        for (pid in candidates) {
            // TERM first so `proot --kill-on-exit` can reap its tracees, then KILL.
            if (signal("TERM", pid) && signal("KILL", pid)) killed++
        }
        Log.w(TAG, "reaped ${killed} orphaned guest process(es) from a previous run")
        killed
    }.getOrElse { 0 }

    private fun signal(name: String, pid: Int): Boolean = runCatching {
        Runtime.getRuntime().exec(arrayOf("kill", "-$name", "$pid"))
            .waitFor(SIGNAL_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        true
    }.getOrDefault(false)

    /** Pids that currently exist according to `/proc`. */
    private fun livePids(): Set<Int> = runCatching {
        File("/proc").listFiles()
            ?.mapNotNull { it.name.toIntOrNull() }
            ?.toSet()
            ?: emptySet()
    }.getOrDefault(emptySet())

    /** `PID PPID EXE` for every process readable by this uid. */
    private fun readProcs(): List<ProcEntry> = runCatching {
        File("/proc").listFiles()?.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val stat = readStat(pid) ?: return@mapNotNull null
            ProcEntry(pid = pid, ppid = stat, exe = readExe(pid))
        } ?: emptyList()
    }.getOrDefault(emptyList())

    private fun readStat(pid: Int): Int? = runCatching {
        parsePpid(File("/proc/$pid/stat").readText())
    }.getOrNull()

    /**
     * The parent pid from the contents of `/proc/<pid>/stat`.
     *
     * The comm field is parenthesised and **may itself contain spaces and `)`**,
     * so the split must start after the LAST `)`. Splitting from the front is the
     * classic bug: a process named `(Web Content)` shifts every later field and
     * makes the ppid read as `Content)` — which parses to null, so the orphan is
     * never matched and the lock survives every restart. Extracted and internal so
     * this is a unit test rather than a hope.
     */
    internal fun parsePpid(stat: String): Int? {
        val close = stat.lastIndexOf(')')
        if (close < 0 || close >= stat.length - 1) return null
        // After `)`: " state ppid pgrp session tty ..."
        return stat.substring(close + 1).trim().split(' ').getOrNull(1)?.toIntOrNull()
    }

    private fun readExe(pid: Int): String? = runCatching {
        File("/proc/$pid/exe").canonicalPath
    }.getOrNull()

    private fun currentPid(): Int = runCatching {
        File("/proc/self").canonicalFile.name.toIntOrNull() ?: 0
    }.getOrDefault(0)

    private const val SIGNAL_WAIT_MS = 300L
}
