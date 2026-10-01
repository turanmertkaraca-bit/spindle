package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.model.MessageId
import dev.spindle.core.model.Part
import dev.spindle.core.model.Role
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The canvas loader and the image-attachment queue on the real view model:
 * workspace resolution, UTF-8 reading, escape refusal, and the send path that
 * carries queued images to the store as `Part.File` vision payloads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CanvasAttachmentTest {

    private lateinit var workspace: File
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workspace = Files.createTempDirectory("lumen-canvas").toFile()
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
    fun `openCanvas loads an html file's contents into state`() {
        write("pages/demo.html", "<html><body>hi</body></html>")

        vm.openCanvas("pages/demo.html")

        assertEquals("<html><body>hi</body></html>", vm.state.value.canvas)
        assertEquals("pages/demo.html", vm.state.value.canvasPath)
        assertNull(vm.state.value.error)

        vm.closeCanvas()
        assertNull(vm.state.value.canvas)
        assertNull(vm.state.value.canvasPath)
    }

    @Test
    fun openCanvasRefusesEscapePathsAndMissingFiles() {
        val outside = File(workspace.parentFile, "secret.html")
        outside.writeText("<html>secret</html>")

        vm.openCanvas(outside.absolutePath)
        assertNull(vm.state.value.canvas)
        assertNotNull(vm.state.value.error)

        vm.openCanvas("../secret.html")
        assertNull(vm.state.value.canvas)
        assertNotNull(vm.state.value.error)

        vm.openCanvas("nope.html")
        assertNull(vm.state.value.canvas)
        assertNotNull(vm.state.value.error)
    }

    @Test
    fun `attachImage queues and removeAttachment drops by index`() {
        vm.attachImage("a.png", "image/png", "AAAA")
        vm.attachImage("b.jpg", "image/jpeg", "BBBB")

        assertEquals(listOf("a.png", "b.jpg"), vm.state.value.attachments.map { it.name })

        vm.removeAttachment(0)
        assertEquals(listOf("b.jpg"), vm.state.value.attachments.map { it.name })

        vm.removeAttachment(9)
        assertEquals(listOf("b.jpg"), vm.state.value.attachments.map { it.name })

        vm.attachImage("blank", "image/png", "")
        assertEquals(1, vm.state.value.attachments.size, "empty payloads are ignored")
    }

    @Test
    fun sendCarriesQueuedImagesAsFilePartsAndClearsTheQueue() {
        val store = InMemorySessionStore()
        val dir = Files.createTempDirectory("lumen-send-vision").toFile()
        val keys = KeyStore(ApplicationProvider.getApplicationContext<Context>())
        val model = ChatViewModel(dir.toPath(), keys, store)
        model.saveKey("opencode-go", "test-key")
        model.newChat()
        val sid = model.state.value.currentSessionId ?: error("newChat should open a session")

        model.attachImage("photo.png", "image/png", "QUJD")
        assertEquals(1, model.state.value.attachments.size)

        model.onInput("look at this")
        model.send()
        model.stop()

        assertTrue(model.state.value.attachments.isEmpty(), "the queue clears on send")
        val messages = kotlinx.coroutines.runBlocking { store.messages(dev.spindle.core.model.SessionId(sid)) }
        val withImages = messages.firstOrNull { m ->
            m.role == Role.USER && m.parts.any { it is Part.File }
        }
        val file = withImages?.parts?.filterIsInstance<Part.File>()?.single()
        assertEquals("image/png", file?.mime)
        assertEquals("QUJD", file?.dataBase64)
    }
}
