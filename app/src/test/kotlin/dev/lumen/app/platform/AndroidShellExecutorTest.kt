package dev.lumen.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    /**
     * The pragmatic terminal on the JVM fallback: open the pipe shell, write a
     * line, and see the shell's output arrive. No TTY prompt is assumed — only
     * that the command's text comes back before the timeout.
     */
    @Test
    fun `openPty streams output for a written line, then completes on close`() = runBlocking {
        val cwd = Files.createTempDirectory("lumen-pty").toFile()
        val session = assertNotNull(shell.openPty("", cwd.toPath()))

        val seen = StringBuilder()
        val sawOutput = CompletableDeferred<Unit>()
        val pump = launch(Dispatchers.IO) {
            try {
                session.output.collect { chunk ->
                    seen.append(chunk)
                    if (!sawOutput.isCompleted && seen.contains("lumen")) sawOutput.complete(Unit)
                }
            } finally {
                sawOutput.complete(Unit)
            }
        }

        session.write("echo lumen\n")
        withTimeout(10_000) { sawOutput.await() }
        assertTrue(seen.contains("lumen"), "output should contain the echoed command: $seen")

        session.close()
        withTimeout(5_000) { pump.join() }
        assertTrue(pump.isCompleted, "the output flow should complete once the session is closed")
    }
}
