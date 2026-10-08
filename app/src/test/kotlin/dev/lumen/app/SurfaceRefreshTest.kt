package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.SessionId
import dev.spindle.core.refs.FileKind
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The file surfaces the chat relies on: the kind gate for directory mentions,
 * the change-range highlight, mention taps opening the real file, and the
 * debounced surface refresh that keeps Files/editor in step with agent writes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SurfaceRefreshTest {

    private lateinit var workspace: File
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workspace = Files.createTempDirectory("lumen-surface").toFile()
        vm = ChatViewModel(
            workspace.toPath(),
            KeyStore(ApplicationProvider.getApplicationContext<Context>()),
            InMemorySessionStore(),
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun write(rel: String, text: String) {
        val f = File(workspace, rel)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    private fun edit(path: String, start: Int?, end: Int? = null, created: Boolean = false) =
        FileEdit(
            id = "edit-$path-$start",
            sessionId = SessionId("ses"),
            path = path,
            startLine = start,
            endLine = end,
            created = created,
        )

    @Test
    fun `highlightRangeFor is null for empty and newly created files`() {
        assertNull(vm.highlightRangeFor(emptyList()))
        assertNull(vm.highlightRangeFor(listOf(edit("new.kt", 1, 3, created = true))))
    }

    @Test
    fun `highlightRangeFor uses the last edit and falls back to its start line`() {
        assertEquals(10..12, vm.highlightRangeFor(listOf(edit("a.kt", 1, 2), edit("a.kt", 10, 12))))
        assertEquals(7..7, vm.highlightRangeFor(listOf(edit("a.kt", 7, null))))
        assertNull(vm.highlightRangeFor(listOf(edit("a.kt", null, null))))
    }

    @Test
    fun `fileKind reports files and directories and refuses escapes and missing paths`() {
        write("a.txt", "x")
        File(workspace, "sub").mkdirs()

        assertEquals(FileKind.FILE, vm.fileKind("a.txt"))
        assertEquals(FileKind.DIRECTORY, vm.fileKind("sub"))
        assertNull(vm.fileKind("missing.txt"))
        assertNull(vm.fileKind("../outside.txt"))
    }

    @Test
    fun `fileKind refuses a symlink that escapes the workspace`() {
        val outside = Files.createTempDirectory("lumen-outside").toFile()
        val secret = File(outside, "secret.txt").apply { writeText("x") }
        val link = File(workspace, "leak.txt")
        assumeTrue(
            "symlinks unsupported here",
            runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }.isSuccess,
        )

        assertNull(vm.fileKind("leak.txt"), "a symlink out of the workspace must be refused")
    }

    @Test
    fun `editFile highlights an explicit line`() {
        write("a.txt", "one\ntwo\nthree\n")
        vm.editFile("a.txt", 2)
        assertEquals(2..2, assertNotNull(vm.state.value.editor).highlight)
    }

    @Test
    fun `editFile highlights the last recorded change range`() {
        write("a.txt", "one\ntwo\nthree\nfour\n")
        vm.recordChange(edit("a.txt", 2, 3))
        vm.editFile("a.txt")
        assertEquals(2..3, assertNotNull(vm.state.value.editor).highlight)
    }

    @Test
    fun `openFileInFiles lists the parent folder and opens the file at the line`() {
        write("src/a.txt", "one\ntwo\nthree\n")
        vm.openFileInFiles("src/a.txt", 2)

        assertEquals("src", assertNotNull(vm.state.value.files).dir)
        val editor = assertNotNull(vm.state.value.editor)
        assertEquals("src/a.txt", editor.path)
        assertEquals(2..2, editor.highlight)
    }

    @Test
    fun `openFileInFiles opens a directory mention as a folder, not the editor`() {
        write("pkg/a.kt", "x")
        vm.openFileInFiles("pkg")

        assertEquals("pkg", assertNotNull(vm.state.value.files).dir)
        assertNull(vm.state.value.editor)
    }

    @Test
    fun `filesDirAffectedBy matches the folder and its descendants`() {
        assertTrue(vm.filesDirAffectedBy("src", setOf("src/a.txt")))
        assertTrue(vm.filesDirAffectedBy("", setOf("a.txt")))
        // A new directory's first file is nested; the parent list must still refresh.
        assertTrue(vm.filesDirAffectedBy("src", setOf("src/deep/a.txt")))
        assertTrue(vm.filesDirAffectedBy("", setOf("src/a.txt")))
        assertFalse(vm.filesDirAffectedBy("src", setOf("other/a.txt")))
        assertFalse(vm.filesDirAffectedBy("src", emptySet()))
    }

    @Test
    fun `applySurfaceRefresh relists the open dir and bumps the revision`() {
        write("a.txt", "x")
        vm.openFiles()
        val before = vm.state.value.fileRevision

        write("b.txt", "y")
        vm.applySurfaceRefresh(setOf("b.txt"))

        assertTrue(assertNotNull(vm.state.value.files).entries.any { it.name == "b.txt" })
        assertTrue(vm.state.value.fileRevision > before)
    }

    @Test
    fun `applySurfaceRefresh re-reads an open editor after an external write`() {
        write("a.txt", "one\n")
        vm.openFiles()
        vm.editFile("a.txt")

        write("a.txt", "one\ntwo\n")
        vm.applySurfaceRefresh(setOf("a.txt"))

        assertEquals(listOf("one", "two"), assertNotNull(vm.state.value.editor).lines)
    }

    @Test
    fun `scheduleSurfaceRefresh debounces then applies the pending paths`() {
        write("a.txt", "x")
        vm.openFiles()
        val before = vm.state.value.fileRevision

        write("b.txt", "y")
        vm.scheduleSurfaceRefresh(setOf("b.txt"))
        var waited = 0
        while (waited < 2_000 && vm.state.value.fileRevision == before) {
            Thread.sleep(20)
            waited += 20
        }

        assertTrue(assertNotNull(vm.state.value.files).entries.any { it.name == "b.txt" })
        assertTrue(vm.state.value.fileRevision > before)
    }

    @Test
    fun `githubLogin is seeded from the key store and can be refreshed`() {
        val keys = KeyStore(ApplicationProvider.getApplicationContext<Context>())
        keys.githubLogin = "octocat"
        val localVm = ChatViewModel(workspace.toPath(), keys, InMemorySessionStore())

        assertEquals("octocat", localVm.state.value.githubLogin)

        keys.githubLogin = "hubber"
        localVm.refreshGithubLogin()
        assertEquals("hubber", localVm.state.value.githubLogin)
    }

    @Test
    fun `currentApiKey returns the saved key and stays empty when unset`() {
        val keys = KeyStore(ApplicationProvider.getApplicationContext<Context>())
        assertEquals("", ChatViewModel(workspace.toPath(), keys, InMemorySessionStore()).currentApiKey())

        keys.apiKey = "sk-test"
        assertEquals("sk-test", ChatViewModel(workspace.toPath(), keys, InMemorySessionStore()).currentApiKey())
    }
}
