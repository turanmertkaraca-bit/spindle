package dev.lumen.app.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
 * The Debian layer must be inert until it is explicitly installed and probed:
 * on a fresh context everything reports false, nothing throws, and the proot
 * argv/env match the rehearsed recipe. No test hits the network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebianEnvironmentTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val debian = DebianEnvironment(context)

    @Test
    fun `prootArgv carries the exact flag set and command tail`() {
        val cwd = Files.createTempDirectory("deb-cwd").toFile().absolutePath
        val argv = debian.prootArgv("echo hi", cwd)

        assertEquals(File(debian.binDir, "proot").absolutePath, argv.first())
        assertTrue(argv.contains("--rootfs=" + debian.rootfs.absolutePath))
        assertTrue(argv.contains("--cwd=/root"))
        assertTrue(argv.contains("-0"))
        assertTrue(argv.contains("--kill-on-exit"))
        assertTrue(argv.contains("--link2symlink"))
        assertTrue(argv.contains("--bind=/dev"))
        assertTrue(argv.contains("--bind=/proc"))
        assertTrue(argv.contains("--bind=/sys"))
        assertTrue(argv.contains("--bind=$cwd:$cwd"))
        assertTrue(argv.contains("--bind=${debian.homeDir.absolutePath}:${debian.homeDir.absolutePath}"))
        assertFalse(argv.contains("-L"), "the experimental -L flag must never be added")

        val bash = argv.indexOf("/bin/bash")
        assertTrue(bash >= 0)
        assertEquals("-c", argv[bash + 1])
        assertEquals("echo hi", argv[bash + 2])
    }

    @Test
    fun `guestProcess exports host proot env and guest proxy home`() {
        val cwd = Files.createTempDirectory("deb-guest").toFile().absolutePath
        val pb = debian.guestProcess("true", cwd)
        val e = pb.environment()

        assertTrue(pb.redirectErrorStream())
        assertEquals("1", e["PROOT_NO_SECCOMP"])
        assertEquals("1", e["PROOT_IGNORE_MISSING_BINDINGS"])
        assertEquals(File(debian.binDir, "loader").absolutePath, e["PROOT_LOADER"])
        assertEquals(debian.tmpDir.absolutePath, e["PROOT_TMP_DIR"])
        assertEquals(debian.binDir.absolutePath, e["LD_LIBRARY_PATH"])
        assertEquals("/root", e["HOME"])
        assertEquals("/tmp", e["TMPDIR"])
        assertEquals("noninteractive", e["DEBIAN_FRONTEND"])
        assertNotNull(e["http_proxy"], "the in-app proxy should be exported to the guest")
        assertNotNull(e["https_proxy"])
    }

    @Test
    fun `ready and active are false on a fresh context and never throw`() {
        assertTrue(runCatching { debian.ready() }.isSuccess)
        assertTrue(runCatching { debian.active() }.isSuccess)
        assertFalse(debian.ready())
        assertFalse(debian.active())
    }

    @Test
    fun `debian linker rewrites absolute targets and keeps content reachable`() {
        val root = Files.createTempDirectory("deb-link-root").toFile()
        File(root, "bin").mkdirs()
        File(root, "bin/busybox").writeText("busy")

        debian.debianLink(root, "usr/bin/ls", "/bin/busybox", true)

        val link = File(root, "usr/bin/ls")
        assertTrue(link.exists(), "the rewritten link should resolve inside the rootfs")
        assertEquals("busy", link.readText())
        assertTrue(link.canExecute())
    }

    @Test
    fun `unpacking the bundled assets yields an executable proot`() {
        debian.unpackBinaries()

        val proot = File(debian.binDir, "proot")
        assertTrue(proot.isFile, "db_proot should unpack to debianbin/proot")
        assertTrue(proot.canExecute())
        assertTrue(File(debian.binDir, "loader").isFile)
        assertTrue(File(debian.binDir, "libtalloc.so").isFile)
        assertTrue(File(debian.binDir, "libandroid-shmem.so").isFile)
    }
}
