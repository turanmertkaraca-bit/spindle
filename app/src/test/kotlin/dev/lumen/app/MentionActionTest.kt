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

/**
 * A headless ViewModel (no platform [dev.lumen.app.platform.WorkspaceActions])
 * must treat install/viewer mentions as no-ops instead of crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MentionActionTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(): ChatViewModel {
        val dir: File = Files.createTempDirectory("lumen-mentions").toFile()
        return ChatViewModel(
            dir.toPath(),
            KeyStore(ApplicationProvider.getApplicationContext<Context>()),
            InMemorySessionStore(),
        )
    }

    @Test
    fun `headless install and external open are no-ops`() {
        val vm = viewModel()
        vm.installApk("x.apk")
        vm.openFileExternally("x.png")
    }
}
