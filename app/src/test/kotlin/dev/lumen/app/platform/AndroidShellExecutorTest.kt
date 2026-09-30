package dev.lumen.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * The executor must always return a [ShellResult] and never throw — even when
 * the Alpine rootfs has not been installed, which is exactly the state of a
 * plain JVM/Robolectric run. Extraction is deliberately not exercised here
 * (the bundled assets are heavy); the fallback path is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidShellExecutorTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val shell = AndroidShellExecutor(context)

    @Test
    fun `fallback runs echo and returns a ShellResult`() {
        runBlocking {
            val cwd = Files.createTempDirectory("lumen-shell").toFile()
            val result = shell.run("echo hi", cwd.toPath(), 30_000)

            assertEquals("alpine", shell.id)
            assertEquals(0, result.exitCode)
            assertEquals("hi", result.output.trim())
            assertFalse(result.timedOut)
            assertFalse(result.truncated)
        }
    }

    @Test
    fun `never throws when the working directory is bogus`() {
        runBlocking {
            val bogus = File("/definitely/not/here/lumen")
            val result = shell.run("echo hi", bogus.toPath(), 5_000)
            assertNotNull(result)
        }
    }
}
