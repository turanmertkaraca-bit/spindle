package dev.lumen.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.lumen.app.data.KeyStore
import dev.lumen.app.platform.AndroidEnvironment
import dev.lumen.app.platform.DebianEnvironment
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
import kotlin.test.assertTrue

/**
 * The storage manager on a real (temp) workspace: it measures real trees,
 * re-scans safely, and its cache clear never touches project source or the
 * session store. The diagnostics ring records run lifecycle and caps at the
 * limit. An unconfined IO dispatcher makes the launched work settle inline.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageDiagnosticsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var workspace: File
    private lateinit var vm: ChatViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        workspace = Files.createTempDirectory("lumen-storage").toFile()
        vm = ChatViewModel(
            workspace.toPath(),
            KeyStore(context),
            InMemorySessionStore(),
            context = context,
            environment = AndroidEnvironment(context),
            debian = DebianEnvironment(context),
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `scanStorage returns categories with non-negative sizes and re-scans cleanly`() {
        File(workspace, "src/app.kt").apply { parentFile?.mkdirs() }.writeText("fun main() {}\n")

        vm.scanStorage()
        val first = assertNotNull(vm.state.value.storage)
        assertFalse(first.scanning, "the unconfined scan should already have landed")
        assertTrue(first.categories.isNotEmpty(), "the scan should label the real trees")
        assertTrue(first.categories.all { it.bytes >= 0 }, "no category may report a negative size")
        assertTrue(first.total >= 0)
        assertTrue(first.total >= File(workspace, "src/app.kt").length())

        vm.scanStorage()
        val second = assertNotNull(vm.state.value.storage)
        assertEquals(first.categories.map { it.name }, second.categories.map { it.name })
    }

    @Test
    fun `clearCache keeps workspace source and the lumen db but removes app cache`() {
        val source = File(workspace, "keep.txt").apply { writeText("project source") }
        val database = File(context.filesDir, "lumen.db").apply { writeText("sessions") }
        val cached = File(context.cacheDir, "junk.bin").apply { parentFile?.mkdirs(); writeText("cache") }

        vm.clearCache()

        assertTrue(source.isFile, "project source must survive a cache clear")
        assertEquals("project source", source.readText())
        assertTrue(database.isFile, "the session store must survive a cache clear")
        assertFalse(cached.exists(), "the app cache should have been reclaimed")
        assertTrue(context.cacheDir.isDirectory, "the cache dir itself is kept, not removed")

        val report = assertNotNull(vm.state.value.storage)
        assertTrue(report.lastFreed > 0, "a clear must report the bytes it freed")
        assertEquals("all caches", report.lastCleared)
        assertEquals(
            0L,
            report.categories.first { it.name == ChatViewModel.STORAGE_CACHE }.bytes,
            "the cleared category must measure as empty",
        )
    }

    @Test
    fun `clearing debian caches empties only the regenerable dirs and reports freed bytes`() {
        val archives = File(context.filesDir, "debian/rootfs/var/cache/apt/archives").apply { mkdirs() }
        val packageFile = File(archives, "pkg.deb").apply { writeText("x".repeat(4096)) }
        val bash = File(context.filesDir, "debian/rootfs/bin/bash").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\n")
        }

        vm.scanStorage()
        val before = assertNotNull(vm.state.value.storage)
        val cacheBefore = before.categories
            .first { it.name == ChatViewModel.STORAGE_DEBIAN_CACHE }.bytes
        assertTrue(cacheBefore >= packageFile.length(), "the cache row must measure the archive")

        vm.clearStorageCategory(ChatViewModel.STORAGE_DEBIAN_CACHE)

        assertFalse(packageFile.exists())
        assertTrue(archives.isDirectory, "apt's cache dir is kept")
        assertTrue(bash.isFile, "the rest of the rootfs is untouched")

        val after = assertNotNull(vm.state.value.storage)
        assertTrue(after.lastFreed >= 4096, "the freed bytes are reported")
        assertEquals(ChatViewModel.STORAGE_DEBIAN_CACHE, after.lastCleared)
        assertEquals(
            0L,
            after.categories.first { it.name == ChatViewModel.STORAGE_DEBIAN_CACHE }.bytes,
            "the cache row drops to zero after a clear",
        )
    }

    @Test
    fun `diag records run lifecycle and caps at the limit`() {
        vm.stop()
        assertTrue(
            vm.state.value.diag.any { it.text.contains("run stopped") },
            "stopping a run should be recorded in diagnostics",
        )

        vm.clearDiagnostics()
        assertTrue(vm.state.value.diag.isEmpty())

        for (i in 0 until ChatViewModel.MAX_DIAG_LINES + 50) vm.recordDiagnostic("line $i")

        assertEquals(ChatViewModel.MAX_DIAG_LINES, vm.state.value.diag.size, "the ring must cap")
        assertEquals("line ${ChatViewModel.MAX_DIAG_LINES + 49}", vm.state.value.diag.last().text)
    }
}
