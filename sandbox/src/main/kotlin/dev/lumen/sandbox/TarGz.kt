package dev.lumen.sandbox

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream

/**
 * Pluggable symlink/hardlink policy. Implementations receive the
 * archive-relative [name], the raw [target], and the entry's executable bit.
 */
fun interface Linker {
    fun link(destDir: File, name: String, target: String, exec: Boolean)
}

/** Optional extraction progress callback. */
fun interface Progress {
    fun on(message: String)
}

/**
 * Full-archive pure-JVM tar extractor. Handles GNU tar, POSIX ustar, long
 * names (typeflag `L`), long link targets (`K`), pax extended headers (`x`),
 * regular files (`0`/`\0`), directories (`5`), symlinks (`2`) and hardlinks
 * (`1`). Gzip input is detected from the leading magic bytes.
 *
 * When no [Linker] is supplied, links are materialized with
 * [Files.createSymbolicLink], falling back to a content copy when the
 * platform cannot create symlinks. Paths are sanitized (no leading `/`, no
 * `..` escapes). Fully streaming.
 */
object TarGz {

    private const val BLOCK = 512
    private const val EXEC_BITS = 0x49 // 0o111
    private const val MAX_LINK_COPY = 12L * 1024 * 1024

    private data class Link(val name: String, val target: String)

    /**
     * Extract every entry of [input] into [destDir].
     *
     * [executable] applies the archive's executable mode bits to extracted
     * files. [progress] and [linker] are optional; a null linker materializes
     * real filesystem symlinks. The input stream is consumed and closed.
     */
    fun extractAll(
        input: InputStream,
        destDir: File,
        executable: Boolean,
        progress: Progress? = null,
        linker: Linker? = null,
    ) {
        val p = progress ?: Progress { }
        val lnk = linker ?: Linker { d, n, t, e -> defaultLink(d, n, t, executable && e) }
        val tin = maybeGunzip(input)

        val links = ArrayList<Link>()
        val execFiles = ArrayList<String>()
        var files = 0
        val hdr = ByteArray(BLOCK)
        var pendingLinkTarget: String? = null

        try {
            while (readFully(tin, hdr)) {
                if (isZero(hdr)) break
                var type = hdr[156].toInt() and 0xFF
                var size = parseSize(hdr, 124, 12)
                var name = norm(cstr(hdr, 0, 100))
                val mode = parseSize(hdr, 100, 8).toInt()
                var regular = type == '0'.code || type == 0

                if (type == 'K'.code) {
                    if (size >= 1 && size <= 65536) {
                        val lb = ByteArray(size.toInt())
                        if (!readFully(tin, lb)) break
                        pendingLinkTarget = cstr(lb, 0, lb.size)
                    }
                    skipPad(tin, size)
                    continue
                }
                if (type == 'L'.code) {
                    if (size < 1 || size > 65536) throw IOException("bad longname size $size")
                    val nb = ByteArray(size.toInt())
                    if (!readFully(tin, nb)) break
                    name = norm(cstr(nb, 0, nb.size))
                    skipPad(tin, size)
                    if (!readFully(tin, hdr) || isZero(hdr)) break
                    type = hdr[156].toInt() and 0xFF
                    size = parseSize(hdr, 124, 12)
                    regular = type == '0'.code || type == 0
                } else if (type == 'x'.code) {
                    if (size >= 1 && size <= (1 shl 20)) {
                        val pb = ByteArray(size.toInt())
                        if (!readFully(tin, pb)) break
                        val path = paxField(pb, "path")
                        val lp = paxField(pb, "linkpath")
                        if (lp != null) pendingLinkTarget = lp
                        skipPad(tin, size)
                        if (!readFully(tin, hdr) || isZero(hdr)) break
                        type = hdr[156].toInt() and 0xFF
                        size = parseSize(hdr, 124, 12)
                        if (path != null) name = norm(path)
                        regular = type == '0'.code || type == 0
                    } else {
                        skipFully(tin, size)
                        skipPad(tin, size)
                        continue
                    }
                } else if (hdr[257].toInt() == 'u'.code && hdr[262].toInt() == 0 && hdr[263].toInt() == '0'.code) {
                    val prefix = norm(cstr(hdr, 345, 155))
                    if (prefix.isNotEmpty() && name.isNotEmpty()) name = "$prefix/$name"
                }

                if (name.isEmpty() || name == ".") {
                    skipFully(tin, size)
                    skipPad(tin, size)
                    continue
                }

                if (regular) {
                    val out = File(destDir, name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { o -> copyN(tin, o, size) }
                    files++
                    if ((mode and EXEC_BITS) != 0) execFiles.add(name)
                } else if (type == '5'.code) {
                    File(destDir, name).mkdirs()
                } else if (type == '2'.code) {
                    val target = pendingLinkTarget ?: cstr(hdr, 157, 100)
                    pendingLinkTarget = null
                    links.add(Link(name, target))
                } else if (type == '1'.code) {
                    var target = pendingLinkTarget ?: cstr(hdr, 157, 100)
                    pendingLinkTarget = null
                    // Hardlink targets are archive-root-relative (POSIX), while
                    // symlink targets are link-dir-relative.
                    if (!target.startsWith("/")) target = "/$target"
                    lnk.link(destDir, name, target, (mode and EXEC_BITS) != 0)
                } else if (type != 'x'.code && type != 'K'.code && type != 'L'.code) {
                    pendingLinkTarget = null
                }
                skipPad(tin, size)
            }
        } finally {
            try {
                tin.close()
            } catch (ignored: IOException) {
            }
        }

        p.on("resolving ${links.size} symlinks…")

        var made = 0
        for (l in links) {
            if (made++ % 40 == 0) p.on("resolving symlinks… $made/${links.size}")
            lnk.link(destDir, l.name, l.target, false)
        }
        finish(destDir, links, execFiles, p, files, executable)
    }

    private fun finish(
        destDir: File,
        links: List<Link>,
        execFiles: List<String>,
        progress: Progress,
        files: Int,
        executable: Boolean,
    ) {
        if (executable) {
            for (e in execFiles) File(destDir, e).setExecutable(true, false)
            for (l in links) {
                val f = File(destDir, l.name)
                if (f.exists() && f.isFile() && !Files.isSymbolicLink(f.toPath())) {
                    f.setExecutable(true, false)
                }
            }
        }
        progress.on("extracted $files files, ${links.size} links")
    }

    /**
     * Pull one `key=` record out of a pax extended-header block. Record-exact:
     * each `<len> <key>=<value>\n` record is split and the key compared whole,
     * so `path` never matches inside `linkpath`.
     */
    private fun paxField(rec: ByteArray, key: String): String? {
        val s = String(rec)
        var pos = 0
        while (pos < s.length) {
            var nl = s.indexOf('\n', pos)
            if (nl < 0) nl = s.length
            val r = s.substring(pos, nl)
            pos = nl + 1
            val sp = r.indexOf(' ')
            val eq = r.indexOf('=', sp + 1)
            if (sp <= 0 || eq < 0) continue
            if (r.substring(sp + 1, eq) == key) return r.substring(eq + 1)
        }
        return null
    }

    /** Materialize an archive link as a real symlink, or copy on failure. */
    private fun defaultLink(destDir: File, name: String, target: String, exec: Boolean) {
        val link = File(destDir, name)
        link.parentFile?.mkdirs()
        val resolved = resolveTarget(destDir, name, target)
        try {
            val parent = link.absoluteFile.parentFile.toPath()
            val relTarget = try {
                parent.relativize(resolved.absoluteFile.toPath())
            } catch (e: IllegalArgumentException) {
                resolved.absoluteFile.toPath()
            }
            Files.createSymbolicLink(link.toPath(), relTarget)
            if (exec) link.setExecutable(true, false)
        } catch (ignored: Exception) {
            copyFallback(destDir, name, target)
        }
    }

    /** Emulated link: copy the resolved target's content, as the old app did. */
    private fun copyFallback(destDir: File, name: String, target: String) {
        try {
            val resolved = resolveTarget(destDir, name, target)
            val dst = File(destDir, name)
            when {
                resolved.isDirectory -> dst.mkdirs()
                resolved.isFile && resolved.length() <= MAX_LINK_COPY -> {
                    dst.parentFile?.mkdirs()
                    FileInputStream(resolved).use { input ->
                        FileOutputStream(dst).use { o -> input.copyTo(o) }
                    }
                    dst.setExecutable(true, false)
                }
            }
        } catch (ignored: Exception) {
        }
    }

    private fun resolveTarget(destDir: File, name: String, target: String): File =
        if (target.startsWith("/")) File(destDir, norm(target.substring(1)))
        else File(destDir, norm(rel(name, target)))

    /** Resolve a relative link target against the link's directory. */
    fun rel(from: String, to: String): String {
        val slash = from.lastIndexOf('/')
        val dir = if (slash >= 0) from.substring(0, slash + 1) else ""
        return dir + to
    }

    /** Normalize: strip leading `./`, collapse `x/../y`, reject escapes. */
    fun norm(path: String): String {
        val parts = path.split("/")
        val st = ArrayDeque<String>()
        for (s in parts) {
            if (s.isEmpty() || s == ".") continue
            if (s == "..") {
                if (!st.isEmpty()) st.removeLast()
                continue
            }
            st.addLast(s)
        }
        return st.joinToString("/")
    }

    private fun isZero(b: ByteArray): Boolean {
        for (k in 0 until BLOCK) if (b[k] != 0.toByte()) return false
        return true
    }

    private fun readFully(input: InputStream, b: ByteArray): Boolean {
        var got = 0
        while (got < b.size) {
            val n = input.read(b, got, b.size - got)
            if (n == -1) break
            got += n
        }
        return got == b.size
    }

    private fun parseSize(b: ByteArray, off: Int, len: Int): Long {
        if ((b[off].toInt() and 0x80) != 0) {
            var v = (b[off].toInt() and 0x7F).toLong()
            for (k in 1 until len) v = (v shl 8) or (b[off + k].toInt() and 0xFF).toLong()
            return v
        }
        var v = 0L
        for (k in 0 until len) {
            val c = b[off + k]
            if (c == 0.toByte() || c == ' '.code.toByte()) {
                if (v > 0) break
                continue
            }
            if (c < '0'.code.toByte() || c > '7'.code.toByte()) break
            v = (v shl 3) or (c - '0'.code).toLong()
        }
        return v
    }

    private fun cstr(b: ByteArray, off: Int, maxLen: Int): String {
        var end = off
        val stop = off + maxLen
        while (end < stop && b[end] != 0.toByte()) end++
        return String(b, off, end - off)
    }

    private fun skipPad(input: InputStream, size: Long) {
        val pad = (BLOCK - (size % BLOCK)) % BLOCK
        if (pad > 0) skipFully(input, pad)
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            var s = input.skip(left)
            if (s <= 0) {
                if (input.read() == -1) return
                s = 1
            }
            left -= s
        }
    }

    private fun copyN(input: InputStream, out: OutputStream, n: Long) {
        val b = ByteArray(1 shl 16)
        var left = n
        while (left > 0) {
            val want = minOf(b.size.toLong(), left).toInt()
            val got = input.read(b, 0, want)
            if (got == -1) throw IOException("unexpected EOF in entry data")
            out.write(b, 0, got)
            left -= got
        }
    }

    /** Wrap the input in buffering and transparently gunzip on the magic bytes. */
    private fun maybeGunzip(input: InputStream): InputStream {
        val buffered = if (input is BufferedInputStream) input else BufferedInputStream(input, 1 shl 16)
        buffered.mark(2)
        val b0 = buffered.read()
        val b1 = buffered.read()
        buffered.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(buffered) else buffered
    }
}
