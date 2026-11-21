package dev.lumen.app.platform

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Orphan reaping, tested as pure decisions.
 *
 * The owner's report was *"the terminal gets locked and restarting doesn't fix
 * it"*. Two causes produce that symptom; the admission reservation fixes slot
 * starvation, and these cover the other: a guest tree that outlived the app
 * process because `setsid` detached it on purpose.
 *
 * The matcher is the dangerous part — killing a live process would be far worse
 * than leaving an orphan — so every rule is asserted in the "must not match"
 * direction as well as the "must match" one.
 */
class OrphanReaperTest {

    private val root = "/data/user/0/dev.lumen.app/files"

    private fun entry(pid: Int, ppid: Int, exe: String?) = OrphanReaper.ProcEntry(pid, ppid, exe)

    @Test
    fun `a guest whose parent is gone is reaped`() {
        val procs = listOf(entry(pid = 500, ppid = 400, exe = "$root/debian/rootfs/bin/bash"))
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = setOf(100), root = root)
        assertEquals(listOf(500), orphans)
    }

    @Test
    fun `a guest whose parent is alive is left alone`() {
        // This is the rule that protects a running shell: a live parent means the
        // tree is in use, and killing it would break the work being protected.
        val procs = listOf(entry(pid = 500, ppid = 400, exe = "$root/debian/rootfs/bin/bash"))
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = setOf(400), root = root)
        assertTrue(orphans.isEmpty(), "a guest with a live parent must not be reaped: $orphans")
    }

    @Test
    fun `the app process itself is never reaped`() {
        val procs = listOf(entry(pid = 100, ppid = 1, exe = "$root/debian/rootfs/bin/bash"))
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = emptySet(), root = root)
        assertTrue(orphans.isEmpty(), "the reaper must never signal its own pid")
    }

    @Test
    fun `a process outside the sandbox root is never touched`() {
        val procs = listOf(
            entry(pid = 501, ppid = 400, exe = "/system/bin/sh"),
            entry(pid = 502, ppid = 400, exe = "/data/user/0/dev.other.app/files/debian/bin/bash"),
            // The classic prefix bug: a sibling directory that merely starts with
            // the same characters must not be treated as inside the root.
            entry(pid = 503, ppid = 400, exe = "${root}evil/bin/bash"),
        )
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = setOf(100), root = root)
        assertTrue(orphans.isEmpty(), "only our own sandbox may be reaped, got $orphans")
    }

    @Test
    fun `an unreadable executable is skipped rather than guessed at`() {
        // `/proc/<pid>/exe` is unreadable for another uid's process, so a null exe
        // means "cannot prove ownership" and must not be a candidate.
        val procs = listOf(entry(pid = 500, ppid = 400, exe = null))
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = setOf(100), root = root)
        assertTrue(orphans.isEmpty(), "an unattributable process must never be killed")
    }

    @Test
    fun `pid 1 and unresolved parents are skipped`() {
        val procs = listOf(
            entry(pid = 1, ppid = 0, exe = "$root/debian/rootfs/bin/bash"),
            entry(pid = 504, ppid = 0, exe = "$root/debian/rootfs/bin/bash"),
        )
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = emptySet(), root = root)
        assertTrue(orphans.isEmpty(), "init and parentless entries are not orphans of ours: $orphans")
    }

    @Test
    fun `each pid is reported once`() {
        val procs = listOf(
            entry(pid = 500, ppid = 400, exe = "$root/debian/rootfs/bin/bash"),
            entry(pid = 500, ppid = 400, exe = "$root/debian/rootfs/bin/bash"),
        )
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = setOf(100), root = root)
        assertEquals(listOf(500), orphans, "a duplicate entry must not be signalled twice")
    }

    @Test
    fun `several orphans are all reaped`() {
        val procs = listOf(
            entry(pid = 500, ppid = 400, exe = "$root/debian/rootfs/bin/bash"),
            entry(pid = 501, ppid = 400, exe = "$root/alpine/bin/sh"),
            entry(pid = 502, ppid = 400, exe = "$root/debian/rootfs/usr/bin/proot"),
        )
        val orphans = OrphanReaper.orphans(procs, selfPid = 100, aliveParents = setOf(100), root = root)
        assertEquals(listOf(500, 501, 502), orphans)
    }

    // ---- /proc/<pid>/stat parsing ---------------------------------------

    @Test
    fun `ppid is parsed from a normal stat line`() {
        //  pid  (comm)  state ppid ...
        assertEquals(400, OrphanReaper.parsePpid("500 (bash) S 400 500 500 0 -1"))
    }

    /**
     * The comm field can contain spaces and `)`; splitting on whitespace from the
     * front silently yields null here, so the orphan is never matched and the lock
     * survives every restart. This is the regression.
     */
    @Test
    fun `ppid is parsed when the process name contains spaces and parentheses`() {
        assertEquals(400, OrphanReaper.parsePpid("500 (Web Content) S 400 500 500 0 -1"))
        assertEquals(400, OrphanReaper.parsePpid("500 (my ) weird ) prog) S 400 500"))
    }

    @Test
    fun `a malformed stat line yields null rather than a wrong pid`() {
        assertNull(OrphanReaper.parsePpid(""))
        assertNull(OrphanReaper.parsePpid("garbage"))
        assertNull(OrphanReaper.parsePpid("500 (bash)"))
        assertNull(OrphanReaper.parsePpid("500 (bash) S"))
        assertNull(OrphanReaper.parsePpid("500 (bash) S notanumber"))
    }

    @Test
    fun `reaping a root with no processes is a harmless no-op`() {
        // The boot path must never throw, whatever /proc looks like.
        assertEquals(0, OrphanReaper.reap(root = java.io.File(root), selfPid = 100))
    }
}
