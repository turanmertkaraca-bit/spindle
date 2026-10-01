package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.model.FileEdit
import dev.spindle.core.model.RunChanges
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.Snapshot
import dev.spindle.core.store.InMemorySessionStore
import dev.spindle.core.store.InMemorySnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real revert path: a recorded snapshot is written back to disk and the
 * change row for that path is dropped; a missing snapshot is an error, not a
 * crash. The view model runs on an unconfined main so the launched coroutines
 * complete inline, no scheduler juggling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RevertTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun keys(): KeyStore =
        KeyStore(ApplicationProvider.getApplicationContext<Context>())

    /** Seed the private [ChatState.changes] so the drop is exercised for real. */
    private fun seedChanges(vm: ChatViewModel, changes: RunChanges) {
        val field = ChatViewModel::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(vm) as MutableStateFlow<ChatState>
        flow.value = flow.value.copy(changes = changes)
    }

    @Test
    fun `revert restores the file and drops the change row`() {
        val dir = Files.createTempDirectory("lumen-revert").toFile()
        val store = InMemorySessionStore()
        val snapshots = InMemorySnapshotStore()
        val vm = ChatViewModel(dir.toPath(), keys(), store, snapshots = snapshots)

        val sid = SessionId("ses_revert")
        runBlocking { store.createSession(Session(sid, "t", dir.path, 0, 0)) }
        vm.openSession(sid.value)

        val file = File(dir, "a.txt")
        file.writeText("changed\n")
        runBlocking {
            snapshots.record(Snapshot(id = "s1", sessionId = sid, path = "a.txt", content = "original\n", sha256 = "", createdAt = 0))
        }
        val edit = FileEdit(id = "e1", sessionId = sid, path = "a.txt")
        seedChanges(vm, RunChanges(edits = listOf(edit)))
        assertTrue(vm.state.value.changes.edits.any { it.path == "a.txt" })

        vm.revert(edit)

        assertEquals("original\n", file.readText())
        assertTrue(vm.state.value.changes.edits.none { it.path == "a.txt" })
        assertEquals(null, vm.state.value.error)
    }

    @Test
    fun `revert with no recorded snapshot surfaces an error`() {
        val dir = Files.createTempDirectory("lumen-revert-none").toFile()
        val store = InMemorySessionStore()
        val vm = ChatViewModel(dir.toPath(), keys(), store, snapshots = InMemorySnapshotStore())

        val sid = SessionId("ses_none")
        runBlocking { store.createSession(Session(sid, "t", dir.path, 0, 0)) }
        vm.openSession(sid.value)

        vm.revert(FileEdit(id = "e1", sessionId = sid, path = "a.txt"))
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `revert without a snapshot store surfaces an error`() {
        val dir = Files.createTempDirectory("lumen-revert-nostore").toFile()
        val vm = ChatViewModel(dir.toPath(), keys(), InMemorySessionStore())

        vm.revert(FileEdit(id = "e1", sessionId = SessionId("ses_x"), path = "a.txt"))
        assertNotNull(vm.state.value.error)
    }
}
