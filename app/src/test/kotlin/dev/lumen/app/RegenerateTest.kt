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
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regenerate drops the last user turn (and its answer) before re-asking. With no
 * API key the follow-up send stops at the key gate, so this pins the rewind half:
 * both the old user message and the assistant reply are gone afterwards.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RegenerateTest {

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

    private fun text(id: String, body: String) = Part.Text(PartId(id), body)

    @Test
    fun `regenerate removes the last user turn and its answer`() {
        val dir = Files.createTempDirectory("lumen-regen").toFile()
        val store = InMemorySessionStore()
        val vm = ChatViewModel(dir.toPath(), keys(), store)

        val sid = SessionId("ses_regen")
        runBlocking {
            store.createSession(Session(sid, "t", dir.path, 0, 0))
            store.appendMessage(Message(MessageId("m1"), sid, Role.USER, listOf(text("p1", "first")), 1L))
            store.appendMessage(Message(MessageId("m2"), sid, Role.ASSISTANT, listOf(text("p2", "answer")), 2L))
            store.appendMessage(Message(MessageId("m3"), sid, Role.USER, listOf(text("p3", "again?")), 3L))
            store.appendMessage(Message(MessageId("m4"), sid, Role.ASSISTANT, listOf(text("p4", "second")), 4L))
        }
        vm.openSession(sid.value)

        vm.regenerateLast()

        val remaining = runBlocking { store.messages(sid) }
        assertEquals(listOf("m1", "m2"), remaining.map { it.id.value })
        assertTrue(vm.state.value.needsKey, "a keyless regenerate stops at the key gate")
    }

    @Test
    fun `edit drops the message and loads its text into the composer`() {
        val dir = Files.createTempDirectory("lumen-edit").toFile()
        val store = InMemorySessionStore()
        val vm = ChatViewModel(dir.toPath(), keys(), store)

        val sid = SessionId("ses_edit")
        runBlocking {
            store.createSession(Session(sid, "t", dir.path, 0, 0))
            store.appendMessage(Message(MessageId("m1"), sid, Role.USER, listOf(text("p1", "first")), 1L))
            store.appendMessage(Message(MessageId("m2"), sid, Role.ASSISTANT, listOf(text("p2", "answer")), 2L))
            store.appendMessage(Message(MessageId("m3"), sid, Role.USER, listOf(text("p3", "again?")), 3L))
            store.appendMessage(Message(MessageId("m4"), sid, Role.ASSISTANT, listOf(text("p4", "second")), 4L))
        }
        vm.openSession(sid.value)

        vm.beginEdit("m3")

        assertEquals(listOf("m1", "m2"), runBlocking { store.messages(sid) }.map { it.id.value })
        assertEquals("again?", vm.state.value.input)
    }
}
