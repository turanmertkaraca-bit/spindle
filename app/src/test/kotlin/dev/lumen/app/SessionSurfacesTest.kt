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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The session surfaces wired through the view model: full-text search, fork at
 * head, and rewind-to-message. Robolectric + an unconfined main so the launched
 * coroutines settle inline, matching the RevertTest discipline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionSurfacesTest {

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

    private fun tmp(): File = Files.createTempDirectory("lumen-sessions").toFile()

    @Test
    fun `searchSessions surfaces hits from the store and clears on blank`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_search")
        runBlocking {
            store.createSession(Session(sid, "t", dir.path, 0, 0))
            store.appendMessage(
                Message(
                    id = MessageId("m1"), sessionId = sid, role = Role.ASSISTANT,
                    parts = listOf(Part.Text(PartId("p1"), "the needle is here")), createdAt = 1,
                ),
            )
        }

        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.searchSessions("needle")

        val hits = vm.state.value.search
        assertNotNull(hits, "a searchable store should populate results")
        assertEquals(1, hits.size)
        assertEquals("m1", hits.single().messageId)
        assertEquals("needle", vm.state.value.searchQuery)

        vm.searchSessions("")
        assertNull(vm.state.value.search, "a blank query clears the results")
    }

    @Test
    fun `forkSession creates a child session with copied messages`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_parent")
        runBlocking {
            store.createSession(Session(sid, "parent", dir.path, 0, 0))
            store.appendMessage(
                Message(
                    MessageId("m1"), sid, Role.USER,
                    parts = listOf(Part.Text(PartId("p1"), "hello")), createdAt = 1,
                ),
            )
            store.appendMessage(
                Message(
                    MessageId("m2"), sid, Role.ASSISTANT,
                    parts = listOf(Part.Text(PartId("p2"), "world")), createdAt = 2,
                ),
            )
        }

        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.forkSession(sid.value)

        val kids = runBlocking { store.sessions(includeChildren = true) }.filter { it.parentId == sid }
        assertEquals(1, kids.size, "forking should create one child")
        assertEquals(2, runBlocking { store.messages(kids.single().id) }.size, "the fork copies every message")
        assertEquals(kids.single().id.value, vm.state.value.currentSessionId, "the fork opens")
    }

    @Test
    fun `rewindTo drops the tail and rebuilds the timeline`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        val sid = SessionId("ses_rewind")
        runBlocking {
            store.createSession(Session(sid, "t", dir.path, 0, 0))
            for (i in 1..3) {
                store.appendMessage(
                    Message(
                        MessageId("m$i"), sid, Role.ASSISTANT,
                        parts = listOf(Part.Text(PartId("p$i"), "answer $i")), createdAt = i.toLong(),
                    ),
                )
            }
        }

        val vm = ChatViewModel(dir.toPath(), keys(), store)
        vm.openSession(sid.value)
        assertEquals(3, vm.state.value.steps.size)

        vm.rewindTo("m2")
        assertEquals(2, runBlocking { store.messages(sid) }.size, "the tail past m2 is gone")
        assertEquals(2, vm.state.value.steps.size, "the timeline is rebuilt from the store")
    }

    @Test
    fun `pin floats a session to the top`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        runBlocking {
            store.createSession(Session(SessionId("ses_old"), "old", dir.path, 0, 100))
            store.createSession(Session(SessionId("ses_new"), "new", dir.path, 0, 200))
        }
        val vm = ChatViewModel(dir.toPath(), keys(), store)
        assertEquals(listOf("ses_new", "ses_old"), vm.state.value.sessions.map { it.id })

        vm.setPinned("ses_old", true)
        assertEquals(
            listOf("ses_old", "ses_new"),
            vm.state.value.sessions.map { it.id },
            "a pinned session sorts above a more recently updated one",
        )
        assertTrue(vm.state.value.sessions.first().pinned)
    }

    @Test
    fun `archive hides a session and the toggle reveals it`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        runBlocking { store.createSession(Session(SessionId("ses_a"), "a", dir.path, 0, 1)) }
        val vm = ChatViewModel(dir.toPath(), keys(), store)
        assertEquals(1, vm.state.value.sessions.size)

        vm.setArchived("ses_a", true)
        assertTrue(vm.state.value.sessions.isEmpty(), "archived is hidden by default")

        vm.setShowArchived(true)
        assertEquals(1, vm.state.value.sessions.size)
        assertTrue(vm.state.value.sessions.single().archived)
    }

    @Test
    fun `rename trims and updates the stored title`() {
        val dir = tmp()
        val store = InMemorySessionStore()
        runBlocking { store.createSession(Session(SessionId("ses_r"), "old", dir.path, 0, 1)) }
        val vm = ChatViewModel(dir.toPath(), keys(), store)

        vm.renameSession("ses_r", "  renamed  ")
        assertEquals("renamed", vm.state.value.sessions.single().title)

        vm.renameSession("ses_r", "   ")
        assertEquals("renamed", vm.state.value.sessions.single().title, "a blank rename is ignored")
    }
}
