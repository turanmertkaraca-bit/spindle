package dev.lumen.app.data

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Storage drill-down scanning.
 *
 * Plain JVM: the scanner takes a [File] and a clock, so no Android types and no
 * `:app` native graphics are involved. That matters because "the app uses 10 GB
 * and I cannot tell what" is only fixable if the measuring code is trustworthy,
 * and a trust-your-eyes claim about disk usage is exactly the kind of thing that
 * rots silently.
 */
class StorageScanTest {

    private fun tempDir(): File = Files.createTempDirectory("storage-scan").toFile()

    private fun write(dir: File, name: String, bytes: Int): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(bytes) { 0x41 })
        return f
    }

    @Test
    fun `children are sorted largest first and both files and dirs appear`() {
        val root = tempDir()
        try {
            write(root, "small.txt", 10)
            write(root, "big.bin", 5_000)
            val nested = File(root, "projectA").also { it.mkdirs() }
            write(nested, "a.bin", 3_000)
            write(nested, "b.bin", 3_000)

            val result = StorageScan.children(root)

            assertEquals(3, result.children.size)
            assertEquals("projectA", result.children[0].name, "the 6 KB directory must sort first")
            assertEquals(6_000L, result.children[0].bytes)
            assertTrue(result.children[0].isDirectory)
            assertEquals("big.bin", result.children[1].name)
            assertEquals("small.txt", result.children[2].name)
            assertEquals(11_010L, result.totalBytes)
            assertFalse(result.truncated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `file count is reported so a folder of many small files is legible`() {
        val root = tempDir()
        try {
            val many = File(root, "many").also { it.mkdirs() }
            repeat(25) { write(many, "f$it.txt", 1) }

            val result = StorageScan.children(root)
            val entry = result.children.single()

            assertEquals(25L, entry.fileCount, "a folder of 25 tiny files must say so")
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * The trap that would make the numbers a lie: a guest rootfs is full of
     * symlinks (to `/proc`, `/sys`, or outside the sandbox). Following them either
     * loops forever or reports bytes that are not the app's.
     */
    @Test
    fun `symlinks are never followed`() {
        val root = tempDir()
        val outside = tempDir()
        try {
            write(outside, "huge.bin", 40_000)
            // A link to a large directory outside the tree.
            val link = File(root, "link-to-outside")
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            write(root, "real.txt", 100)

            val result = StorageScan.children(root)

            val linked = result.children.first { it.name == "link-to-outside" }
            assertEquals(0L, linked.bytes, "a symlink must not contribute the target's bytes")
            assertEquals(
                100L,
                result.totalBytes,
                "total must reflect only real bytes inside the tree, got ${result.children.map { it.name + "=" + it.bytes }}",
            )
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `a symlink inside a directory is not traversed either`() {
        val root = tempDir()
        val outside = tempDir()
        try {
            write(outside, "huge.bin", 90_000)
            val sub = File(root, "sub").also { it.mkdirs() }
            Files.createSymbolicLink(File(sub, "escape").toPath(), outside.toPath())
            write(sub, "small.txt", 7)

            val result = StorageScan.children(root)
            assertEquals(7L, result.children.single().bytes, "only the real file counts")
        } finally {
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `hitting the deadline reports truncated rather than under-reporting silently`() {
        val root = tempDir()
        try {
            repeat(10) { write(root, "f$it.bin", 1_000) }
            // A clock that is already past the deadline: the scan must stop at
            // once and say so instead of pretending it measured everything.
            var t = 0L
            val result = StorageScan.children(root, budgetMs = 100, now = { t.also { t += 1_000 } })
            assertTrue(result.truncated, "an exhausted budget must be reported")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a real scan within budget is not marked truncated`() {
        val root = tempDir()
        try {
            repeat(5) { write(root, "f$it.bin", 100) }
            val result = StorageScan.children(root, budgetMs = 30_000)
            assertFalse(result.truncated)
            assertEquals(5, result.children.size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `limit caps the returned rows but the total still reflects everything`() {
        val root = tempDir()
        try {
            repeat(30) { write(root, "f$it.bin", (it + 1) * 10) }
            val result = StorageScan.children(root, limit = 5)

            assertEquals(5, result.children.size, "rows are capped")
            assertTrue(result.truncated, "a capped list must say it is partial")
            assertEquals(
                (1..30).sumOf { (it * 10).toLong() },
                result.totalBytes,
                "the total must cover every child, not just the returned rows",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a missing directory yields an empty result instead of throwing`() {
        val result = StorageScan.children(File("/definitely/not/here"))
        assertTrue(result.children.isEmpty())
        assertEquals(0L, result.totalBytes)
        assertFalse(result.truncated)
    }

    @Test
    fun `measuring a plain file returns its length and one file`() {
        val root = tempDir()
        try {
            val f = write(root, "x.bin", 1_234)
            val m = StorageScan.measure(f, System.currentTimeMillis() + 5_000)
            assertEquals(1_234L, m.bytes)
            assertEquals(1L, m.files)
            assertFalse(m.truncated)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `nested directories are measured recursively`() {
        val root = tempDir()
        try {
            write(root, "a/b/c/deep.bin", 500)
            write(root, "a/top.bin", 250)
            val result = StorageScan.children(root)
            assertEquals(750L, result.children.single().bytes)
            assertEquals(2L, result.children.single().fileCount)
        } finally {
            root.deleteRecursively()
        }
    }
}
