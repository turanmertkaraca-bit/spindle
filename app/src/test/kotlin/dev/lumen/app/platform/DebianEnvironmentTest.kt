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
    fun `absolute link targets are rewritten relative to the link's own depth`() {
        // Asserting filesystem resolution is host-dependent (symlink vs. content
        // copy differ across filesystems). The invariant that actually matters —
        // and that the Debian rootfs depends on — is the relative rewrite itself.
        // "usr/bin/ls" has two slashes -> "../../" climbs from usr/bin/ to root.
        assertEquals("../../bin/busybox", debian.relFromRoot("usr/bin/ls", "bin/busybox"))
        // "bin/python3" has one slash -> "../" climbs from bin/ to root.
        assertEquals("../usr/bin/python3", debian.relFromRoot("bin/python3", "usr/bin/python3"))
        // a top-level "sh" has no slash -> the target is already root-relative.
        assertEquals("bin/sh", debian.relFromRoot("sh", "bin/sh"))
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
