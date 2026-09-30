package dev.lumen.sandbox

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TarGzTest {

    @Test
    fun `extracts regular files nested dirs and executable bit`() {
        val dest = Files.createTempDirectory("targz-dest").toFile()
        val archive = buildTarGz(
            dir("dir/"),
            file("dir/hello.txt", "hello world"),
            dir("bin/"),
            file("bin/run.sh", "#!/bin/sh\necho hi\n", mode = 0b111101101),
            hardlink("bin/run-link", "bin/run.sh"),
        )

        TarGz.extractAll(archive.inputStream(), dest, executable = true)

        assertEquals("hello world", File(dest, "dir/hello.txt").readText())
        assertTrue(File(dest, "dir").isDirectory)
        assertTrue(File(dest, "bin/run.sh").canExecute(), "exec bit should be set")
        assertEquals("#!/bin/sh\necho hi\n", File(dest, "bin/run.sh").readText())
    }

    @Test
    fun `invokes the linker for symlink entries`() {
        val dest = Files.createTempDirectory("targz-link").toFile()
        val archive = buildTarGz(
            file("real.txt", "target"),
            symlink("link.txt", "real.txt"),
        )

        val calls = ArrayList<Triple<String, String, Boolean>>()
        TarGz.extractAll(
            archive.inputStream(),
            dest,
            executable = true,
            linker = Linker { _, name, target, exec -> calls.add(Triple(name, target, exec)) },
        )

        assertEquals(1, calls.size)
        assertEquals("link.txt", calls[0].first)
        assertEquals("real.txt", calls[0].second)
        assertFalse(calls[0].third)
    }

    @Test
    fun `default linker materializes symlinks`() {
        val dest = Files.createTempDirectory("targz-default-link").toFile()
        val archive = buildTarGz(
            file("real.txt", "content"),
            symlink("link.txt", "real.txt"),
        )

        TarGz.extractAll(archive.inputStream(), dest, executable = true)

        assertEquals("content", File(dest, "link.txt").readText())
        if (symlinksSupported(dest)) {
            assertTrue(Files.isSymbolicLink(File(dest, "link.txt").toPath()))
        }
    }

    @Test
    fun `handles GNU long names`() {
        val longName = "very/" + "x".repeat(140) + ".txt"
        val dest = Files.createTempDirectory("targz-long").toFile()
        val archive = buildTarGz(file(longName, "long"))

        TarGz.extractAll(archive.inputStream(), dest, executable = true)

        assertEquals("long", File(dest, longName).readText())
    }

    @Test
    fun `norm strips dot segments and parent escapes`() {
        assertEquals("", TarGz.norm(""))
        assertEquals("a/b", TarGz.norm("./a/./b/"))
        assertEquals("a/c", TarGz.norm("a/b/../c"))
        assertEquals("b", TarGz.norm("../b"))
        assertEquals("a/b", TarGz.norm("/a/b"))
        assertEquals("x", TarGz.norm("../../x"))
    }

    @Test
    fun `rel resolves a target against the link directory`() {
        assertEquals("a/b/d", TarGz.rel("a/b/c", "d"))
        assertEquals("d", TarGz.rel("c", "d"))
        assertEquals("a/d", TarGz.rel("a/c", "d"))
    }

    private fun symlinksSupported(dir: File): Boolean = try {
        val probe = File(dir, "probe-link").toPath()
        val target = File(dir, "probe-target").toPath()
        Files.createSymbolicLink(probe, target)
        Files.deleteIfExists(probe)
        true
    } catch (ignored: Exception) {
        false
    }

    // ------------------------------------------------------- tar fixtures

    private data class TarEntry(
        val name: String,
        val mode: Int,
        val type: Char,
        val content: ByteArray,
        val linkname: String,
    )

    private fun file(name: String, content: String, mode: Int = 0b110100100) =
        TarEntry(name, mode, '0', content.toByteArray(), "")

    private fun dir(name: String) = TarEntry(name, 0b111101101, '5', ByteArray(0), "")

    private fun symlink(name: String, target: String) =
        TarEntry(name, 0b111101101, '2', ByteArray(0), target)

    private fun hardlink(name: String, target: String) =
        TarEntry(name, 0b110100100, '1', ByteArray(0), target)

    private fun buildTarGz(vararg entries: TarEntry): ByteArray {
        val tar = buildTar(entries.toList())
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(tar) }
        return out.toByteArray()
    }

    private fun buildTar(entries: List<TarEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in entries) {
            val nameBytes = e.name.toByteArray()
            if (nameBytes.size > 100) {
                writeHeader(out, "././@LongLink", 0b110100100, 'L', nameBytes.size.toLong() + 1, "")
                out.write(nameBytes)
                out.write(0)
                appendPadding(out, nameBytes.size.toLong() + 1)
                writeHeader(out, e.name.takeLast(100), e.mode, e.type, e.content.size.toLong(), e.linkname)
            } else {
                writeHeader(out, e.name, e.mode, e.type, e.content.size.toLong(), e.linkname)
            }
            out.write(e.content)
            appendPadding(out, e.content.size.toLong())
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun writeHeader(
        out: ByteArrayOutputStream,
        name: String,
        mode: Int,
        type: Char,
        size: Long,
        linkname: String,
    ) {
        val h = ByteArray(512)
        putString(h, 0, 100, name)
        putOctal(h, 100, 8, mode.toLong())
        putOctal(h, 108, 8, 0)
        putOctal(h, 116, 8, 0)
        putOctal(h, 124, 12, size)
        putOctal(h, 136, 12, 0)
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        h[156] = type.code.toByte()
        putString(h, 157, 100, linkname)
        putString(h, 257, 6, "ustar")
        h[263] = '0'.code.toByte()
        h[264] = '0'.code.toByte()

        var sum = 0
        for (b in h) sum += b.toInt() and 0xFF
        val chk = String.format("%06o", sum).toByteArray()
        System.arraycopy(chk, 0, h, 148, 6)
        h[154] = 0
        h[155] = ' '.code.toByte()

        out.write(h)
    }

    private fun putString(h: ByteArray, off: Int, len: Int, s: String) {
        val b = s.toByteArray()
        System.arraycopy(b, 0, h, off, minOf(b.size, len))
    }

    private fun putOctal(h: ByteArray, off: Int, len: Int, value: Long) {
        val s = String.format("%0${len - 1}o", value).toByteArray()
        System.arraycopy(s, 0, h, off, minOf(s.size, len - 1))
        h[off + len - 1] = 0
    }

    private fun appendPadding(out: ByteArrayOutputStream, size: Long) {
        val pad = ((512 - (size % 512)) % 512).toInt()
        if (pad > 0) out.write(ByteArray(pad))
    }
}
