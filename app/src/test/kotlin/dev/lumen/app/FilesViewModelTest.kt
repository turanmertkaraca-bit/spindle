package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
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
 * The real file cockpit on a temp workspace: listing order, clamped navigation,
 * create/write/rename/delete, escape refusal, and the editor's line cap. The
 * view model runs on an unconfined main so its init coroutines settle inline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilesViewModelTest {

    private lateinit var workspace: File
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workspace = Files.createTempDirectory("lumen-files").toFile()
        vm = ChatViewModel(
            workspace.toPath(),
            KeyStore(ApplicationProvider.getApplicationContext<Context>()),
            InMemorySessionStore(),
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

    @Test
    fun `openFiles lists directories first then files, name-ascending`() {
        write("b.txt", "b")
        write("a.txt", "a")
        File(workspace, "sub").mkdirs()

        vm.openFiles()

        val files = assertNotNull(vm.state.value.files)
        assertEquals(listOf("sub", "a.txt", "b.txt"), files.entries.map { it.name })
        assertTrue(files.entries.first().isDir)
        assertFalse(files.entries[1].isDir)
    }

    @Test
    fun `enterDir descends and filesUp clamps at the workspace root`() {
        File(workspace, "sub/deep").mkdirs()

        vm.openFiles()
        assertEquals("", vm.state.value.files!!.dir)

        vm.enterDir("sub")
        assertEquals("sub", vm.state.value.files!!.dir)

        vm.enterDir("sub/deep")
        assertEquals("sub/deep", vm.state.value.files!!.dir)

        vm.filesUp()
        assertEquals("sub", vm.state.value.files!!.dir)

        vm.filesUp()
        assertEquals("", vm.state.value.files!!.dir)

        vm.filesUp()
        assertEquals("", vm.state.value.files!!.dir)
    }

    @Test
    fun `saveFile writes utf-8 content and creates parent directories`() {
        vm.openFiles()

        vm.saveFile("nested/new.txt", "hello lumen")

        assertEquals("hello lumen", File(workspace, "nested/new.txt").readText())
        assertNull(vm.state.value.files!!.error)
    }

    @Test
    fun `createFile and createDir create entries and reject bad names`() {
        vm.openFiles()

        vm.createFile("", "new.txt")
        vm.createDir("", "newdir")
        assertTrue(File(workspace, "new.txt").isFile)
        assertTrue(File(workspace, "newdir").isDirectory)

        vm.openFiles()
        assertNull(vm.state.value.files!!.error)
        vm.createFile("", "a/b")
        assertNotNull(vm.state.value.files!!.error)
        assertFalse(File(workspace, "a").exists())
    }

    @Test
    fun `deleteEntry removes a file, refuses a non-empty folder, then recurses`() {
        write("gone.txt", "x")
        write("d/inner.txt", "x")
        vm.openFiles()

        vm.deleteEntry("gone.txt")
        assertFalse(File(workspace, "gone.txt").exists())

        vm.deleteEntry("d")
        assertTrue(File(workspace, "d").exists())
        assertNotNull(vm.state.value.files!!.error)

        vm.deleteEntry("d", recursive = true)
        assertFalse(File(workspace, "d").exists())
    }

    @Test
    fun `renameEntry moves within the same folder`() {
        write("old.txt", "x")
        vm.openFiles()

        vm.renameEntry("old.txt", "new.txt")

        assertFalse(File(workspace, "old.txt").exists())
        assertTrue(File(workspace, "new.txt").isFile)
    }

    @Test
    fun `escape paths are refused and set an error`() {
        vm.openFiles()

        vm.saveFile("../escape.txt", "x")
        assertNotNull(vm.state.value.files!!.error)
        assertFalse(File(workspace.parentFile, "escape.txt").exists())

        vm.openFiles()
        assertNull(vm.state.value.files!!.error)
        vm.openFiles("../")
        assertNotNull(vm.state.value.files!!.error)

        val outside = File(workspace.parentFile, "outside.txt")
        outside.writeText("secret")
        vm.editFile(outside.absolutePath)
        assertNotNull(vm.state.value.editor!!.error)
    }

    @Test
    fun `a large file is capped in the editor and flagged`() {
        val body = buildString {
            for (i in 0 until ChatViewModel.MAX_PEEK_LINES + 50) append("line $i\n")
        }
        write("big.txt", body)
        vm.openFiles()

        vm.editFile("big.txt")

        val editor = assertNotNull(vm.state.value.editor)
        assertTrue(editor.truncated)
        assertEquals(ChatViewModel.MAX_PEEK_LINES, editor.lines.size)
    }
}
