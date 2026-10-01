package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.model.Message
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.PartId
import dev.spindle.core.model.Role
import dev.spindle.core.model.Session
import dev.spindle.core.model.SessionId
import dev.spindle.core.model.TodoItem
import dev.spindle.core.model.TodoStatus
import dev.spindle.core.store.InMemorySessionStore
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertTrue

/**
 * The live todo board wired through the view model: a store change is picked up
 * when the timeline rebuilds, and the list is dropped when the chat closes.
 * Robolectric + an unconfined main so the launched coroutines settle inline,
 * matching the [SessionSurfacesTest] discipline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TodoBoardTest {

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

    private fun tmp(): File = Files.createTempDirectory("lumen-todos").toFile()

    private fun seed(store: InMemorySessionStore, dir: File, sid: SessionId) = runBlocking {
        store.createSession(Session(sid, "t", dir.path, 0, 0))
        store.appendMessage(
            Message(
                MessageId("m1"), sid, Role.ASSISTANT,
                parts = listOf(Part.Text(PartId("p1"), "working…")), createdAt = 1,
            ),
        )
    }

    @Test
    fun `todos refresh from the store on a rebuild`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_todo")
        seed(store, dir, sid)

        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.openSession(sid.value)
        assertTrue(vm.state.value.todos.isEmpty(), "a fresh session has no todos")

        runBlocking {
            store.setTodos(
                sid,
                listOf(
                    TodoItem("1", "read the layout rule", TodoStatus.DONE),
                    TodoItem("2", "patch the redirect guard", TodoStatus.IN_PROGRESS),
                ),
            )
        }
        // A timeline rebuild (the same path a ToolFinished/PartUpdated takes)
        // must re-read the list.
        vm.rewindTo("m1")

        val todos = vm.state.value.todos
        assertEquals(2, todos.size)
        assertEquals(TodoStatus.DONE, todos[0].status)
        assertEquals("patch the redirect guard", todos[1].content)
    }

    @Test
    fun `closing a chat clears the todo list`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_close")
        seed(store, dir, sid)

        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.openSession(sid.value)
        runBlocking { store.setTodos(sid, listOf(TodoItem("1", "only task", TodoStatus.PENDING))) }
        runBlocking { vm.refreshTodos() }
        assertEquals(1, vm.state.value.todos.size)

        vm.closeChat()
        assertTrue(vm.state.value.todos.isEmpty(), "closing the chat drops the board")
    }
}
