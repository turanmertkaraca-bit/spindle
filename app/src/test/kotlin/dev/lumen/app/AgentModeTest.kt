package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.spindle.core.store.InMemorySessionStore
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The agent-mode picker: default build, switch to plan, persist across instances. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentModeTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `setAgentMode updates state and persists in KeyStore`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = Files.createTempDirectory("lumen-agent").toFile()
        val vm = ChatViewModel(dir.toPath(), KeyStore(context), InMemorySessionStore())

        assertEquals("build", vm.state.value.agentMode, "build is the default")
        vm.setAgentMode("plan")
        assertEquals("plan", vm.state.value.agentMode, "the state follows the picker")
        assertEquals("plan", KeyStore(context).agentMode, "a fresh KeyStore sees the persisted mode")
    }
}
