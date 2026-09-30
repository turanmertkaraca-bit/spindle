package dev.spindle.core.store

import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import kotlinx.coroutines.test.runTest
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RevertTest {

    private fun snapshot(path: String, content: String, id: String = "snap_1") = Snapshot(
        id = id,
        sessionId = SessionId("ses_1"),
        path = path,
        content = content,
        sha256 = "hash",
        createdAt = 0L,
    )

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("revert-test")
        try {
            block(dir)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `revert restores recorded content over the current file`() = withTempDir { cwd ->
        val file = cwd.resolve("src/Main.kt")
        Files.createDirectories(file.parent)
        Files.writeString(file, "new", StandardCharsets.UTF_8)

        val result = Reverter.revert(snapshot("src/Main.kt", "old"), cwd)

        assertIs<RevertResult.Restored>(result)
        assertEquals("old", Files.readString(file, StandardCharsets.UTF_8))
    }

    @Test
    fun `revert creates missing parent directories`() = withTempDir { cwd ->
        val result = Reverter.revert(snapshot("a/b/c.txt", "deep"), cwd)

        assertIs<RevertResult.Restored>(result)
        assertEquals("deep", Files.readString(cwd.resolve("a/b/c.txt"), StandardCharsets.UTF_8))
    }

    @Test
    fun `revert creates a file that is currently missing`() = withTempDir { cwd ->
        val result = Reverter.revert(snapshot("fresh.txt", "hello"), cwd)

        assertIs<RevertResult.Restored>(result)
        assertEquals("hello", Files.readString(cwd.resolve("fresh.txt"), StandardCharsets.UTF_8))
    }

    @Test
    fun `revert refuses a path that escapes cwd`() = withTempDir { cwd ->
        val result = Reverter.revert(snapshot("../escape.txt", "x"), cwd)

        val failed = assertIs<RevertResult.Failed>(result)
        assertTrue(failed.reason.contains("escape"))
        assertTrue(!Files.exists(cwd.parent.resolve("escape.txt")))
    }

    @Test
    fun `revert refuses an absolute path`() = withTempDir { cwd ->
        val result = Reverter.revert(snapshot("/tmp/evil-revert.txt", "x"), cwd)

        assertIs<RevertResult.Failed>(result)
        assertTrue(!Files.exists(Path.of("/tmp/evil-revert.txt")))
    }

    @Test
    fun `revertLatest returns no snapshot when none was recorded`() = runTest {
        val cwd = Files.createTempDirectory("revert-test")
        try {
            val result = Reverter.revertLatest(InMemorySnapshotStore(), SessionId("ses_1"), "a.kt", cwd)

            val failed = assertIs<RevertResult.Failed>(result)
            assertEquals("no snapshot", failed.reason)
        } finally {
            cwd.toFile().deleteRecursively()
        }
    }

    @Test
    fun `revertLatest loads and restores the most recent snapshot`() = runTest {
        val cwd = Files.createTempDirectory("revert-test")
        try {
            val store = InMemorySnapshotStore()
            val sid = SessionId("ses_1")
            store.record(snapshot("a.txt", "first", id = "s1"))
            store.record(snapshot("a.txt", "second", id = "s2"))

            val result = Reverter.revertLatest(store, sid, "a.txt", cwd)

            assertIs<RevertResult.Restored>(result)
            assertEquals("second", Files.readString(cwd.resolve("a.txt"), StandardCharsets.UTF_8))
        } finally {
            cwd.toFile().deleteRecursively()
        }
    }
}
