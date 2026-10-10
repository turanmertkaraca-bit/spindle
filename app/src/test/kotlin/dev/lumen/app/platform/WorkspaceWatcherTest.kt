package dev.lumen.app.platform

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plain JVM tests (no Robolectric): the watcher and its pure helpers only touch
 * [java.io.File], so a real temp directory is enough.
 */
class WorkspaceWatcherTest {

    private fun tempRoot(): File = Files.createTempDirectory("ws-watch").toFile()

    @Test
    fun `snapshot uses workspace-relative forward-slash paths`() {
        val root = tempRoot()
        try {
            File(root, "src").mkdirs()
            File(root, "src/Main.kt").writeText("fun main() {}")
            File(root, "README.md").writeText("hi")

            val snap = snapshot(root)
            assertTrue(snap.containsKey("src/Main.kt"), "nested path expected, got ${snap.keys}")
            assertTrue(snap.containsKey("README.md"))
            assertFalse(snap.keys.any { it.contains('\\') }, "paths must be forward-slash only")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `snapshot skips heavy directories`() {
        val root = tempRoot()
        try {
            for (heavy in HEAVY_DIRS) {
                File(root, "$heavy/artefact.bin").apply { parentFile!!.mkdirs() }.writeText("x")
            }
            File(root, "app/src/keep.kt").apply { parentFile!!.mkdirs() }.writeText("keep")

            val snap = snapshot(root)
            assertTrue(snap.containsKey("app/src/keep.kt"))
            for (heavy in HEAVY_DIRS) {
                assertFalse(snap.keys.any { it.startsWith("$heavy/") }, "must skip $heavy")
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `diff reports created changed and deleted paths`() {
        val before = mapOf("a.kt" to 1L, "b.kt" to 2L, "gone.kt" to 3L)
        val after = mapOf("a.kt" to 1L, "b.kt" to 99L, "new.kt" to 5L)

        assertEquals(setOf("b.kt", "new.kt", "gone.kt"), diff(before, after))
    }

    @Test
    fun `snapshot plus diff sees a real create modify delete`() {
        val root = tempRoot()
        try {
            File(root, "keep.txt").writeText("one")

            val create = snapshot(root)
            File(root, "added.txt").writeText("new")
            assertEquals(setOf("added.txt"), diff(create, snapshot(root)))

            val beforeModify = snapshot(root)
            File(root, "added.txt").writeText("a longer body changes the size fingerprint")
            assertEquals(setOf("added.txt"), diff(beforeModify, snapshot(root)))

            val beforeDelete = snapshot(root)
            File(root, "added.txt").delete()
            assertEquals(setOf("added.txt"), diff(beforeDelete, snapshot(root)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `watcher reports an indirect write within its interval`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = LinkedBlockingQueue<Set<String>>()
        val watcher = WorkspaceWatcher(
            root = root,
            scope = scope,
            intervalMs = 25,
            onChange = { events.put(it) },
        )
        try {
            File(root, "baseline.txt").writeText("baseline")
            watcher.start()

            delay(60)
            File(root, "script-output.txt").writeText("written by a script")

            val observed = withTimeout(5_000) {
                var found: Set<String> = emptySet()
                while (found.isEmpty()) {
                    val event = events.poll()
                    if (event != null && "script-output.txt" in event) {
                        found = event
                    } else {
                        delay(20)
                    }
                }
                found
            }

            assertTrue("script-output.txt" in observed, "expected the indirect write, got $observed")
        } finally {
            watcher.stop()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `snapshotDirs reports directories and never files`() {
        val root = tempRoot()
        try {
            File(root, "src/nested").mkdirs()
            File(root, "src/Main.kt").writeText("x")
            File(root, "top.txt").writeText("x")

            val dirs = snapshotDirs(root)
            assertTrue("src" in dirs, "expected src, got $dirs")
            assertTrue("src/nested" in dirs, "expected src/nested, got $dirs")
            assertFalse(dirs.any { it.endsWith(".kt") || it.endsWith(".txt") }, "no files: $dirs")
            assertFalse(dirs.any { it.contains('\\') }, "paths must be forward-slash only")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `watcher reports a newly created empty directory`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = LinkedBlockingQueue<Set<String>>()
        val watcher = WorkspaceWatcher(root = root, scope = scope, intervalMs = 25) { events.put(it) }
        try {
            File(root, "baseline.txt").writeText("baseline")
            watcher.start()

            delay(60)
            File(root, "newdir").mkdirs()

            val observed = withTimeout(5_000) {
                var found: Set<String> = emptySet()
                while (found.isEmpty()) {
                    val event = events.poll()
                    if (event != null && "newdir" in event) found = event else delay(20)
                }
                found
            }
            assertTrue("newdir" in observed, "an empty new directory must be reported, got $observed")
        } finally {
            watcher.stop()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `double start does not spawn two loops and stop is idempotent`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = LinkedBlockingQueue<Set<String>>()
        val watcher = WorkspaceWatcher(root, scope, intervalMs = 25) { events.put(it) }
        try {
            File(root, "a.txt").writeText("a")
            watcher.start()
            watcher.start()
            delay(60)
            File(root, "b.txt").writeText("b")
            delay(120)

            // Two concurrent loops would both diff the same change and emit twice.
            val bEvents = generateSequence { events.poll() }.count { "b.txt" in it }
            assertTrue(bEvents >= 1, "the change should be observed")
            assertTrue(bEvents <= 1, "a double start must not double-report (got $bEvents)")

            watcher.stop()
            watcher.stop()
        } finally {
            watcher.stop()
            scope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun `snapshotDetailed flags truncation only when a file is dropped`() {
        val root = tempRoot()
        try {
            repeat(3) { i -> File(root, "f$i.txt").writeText("x") }
            // A tree that ends exactly at the cap is complete, not truncated.
            assertFalse(snapshotDetailed(root, maxFiles = 3).truncated)
            assertTrue(snapshotDetailed(root, maxFiles = 2).truncated)
            assertEquals(3, snapshotDetailed(root, maxFiles = 10).entries.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `watcher announces truncation once and still reports diffs`() = runBlocking {
        val root = tempRoot()
        val scope = CoroutineScope(Dispatchers.IO)
        val events = LinkedBlockingQueue<Set<String>>()
        val truncations = LinkedBlockingQueue<Boolean>()
        val watcher = WorkspaceWatcher(root = root, scope = scope, intervalMs = 25) { events.put(it) }
        watcher.maxFiles = 2
        watcher.onTruncated = { truncations.put(it) }
        try {
            File(root, "baseline.txt").writeText("baseline")
            watcher.start()

            delay(60)
            File(root, "a.txt").writeText("a")
            File(root, "b.txt").writeText("b")
            File(root, "c.txt").writeText("c")

            val first = withTimeout(5_000) {
                var signalled: Boolean? = null
                while (signalled == null) {
                    signalled = truncations.poll() ?: run { delay(20); null }
                }
                signalled
            }
            assertTrue(first == true, "crossing the cap must signal truncated=true")

            // The capped walk still delivers the files it managed to see.
            val observed = withTimeout(5_000) {
                var found: Set<String> = emptySet()
                while (found.isEmpty()) {
                    val event = events.poll()
                    if (event != null) found = event else delay(20)
                }
                found
            }
            assertTrue(
                observed.any { it in setOf("a.txt", "b.txt", "c.txt") },
                "a capped walk must still report its changes, got $observed",
            )

            // While the tree stays over the cap there is no second transition.
            delay(150)
            assertTrue(truncations.isEmpty(), "truncation must fire only on the transition")
        } finally {
            watcher.stop()
            scope.cancel()
            root.deleteRecursively()
        }
    }
}
