package dev.lumen.app.platform

import android.content.Context
import android.system.Os
import dev.lumen.sandbox.InAppProxy
import dev.lumen.sandbox.Linker
import dev.lumen.sandbox.Progress
import dev.lumen.sandbox.TarGz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * The Alpine layer: a real musl Linux userland the agent's shell can exec, with
 * no proot. The minirootfs asset (`alpine.bin`) is unpacked into
 * `<filesDir>/alpine`; every rootfs ELF is musl-dynamic with interpreter
 * `/lib/ld-musl-aarch64.so.1` (an absolute path that does not exist on
 * Android), so one tiny `#!/system/bin/sh` wrapper per command execs the musl
 * loader explicitly:
 *
 *     alpine/lib/ld-musl-aarch64.so.1 --library-path alpine/lib:alpine/usr/lib <bin> args
 *
 * Network goes through the in-app Java CONNECT proxy ([InAppProxy]): musl reads
 * `/etc/resolv.conf` (impossible on Android), so every wrapper sources
 * `alpine/.proxy` and exports `http(s)_proxy=127.0.0.1:<port>`. The Alpine repo
 * is forced to `http://` because the minirootfs ships no CA bundle; apk's RSA
 * signatures still verify package authenticity.
 *
 * All filesystem/exec work is suspend or documented as call-once-on-a-worker.
 * Nothing here throws to a caller: failures are reported as `false`/`null` or
 * swallowed, so a broken layer never takes the app down.
 */
class AndroidEnvironment(private val context: Context) {

    /** Extracted alpine-minirootfs root. */
    val rootfs: File get() = File(context.filesDir, "alpine")

    /** Generated per-command wrapper scripts. */
    val wrappersDir: File by lazy {
        File(context.filesDir, "wrappers").apply { mkdirs() }
    }

    /** True when the layer is usable: marker file plus the musl loader. */
    fun ready(): Boolean = File(rootfs, ".ready").exists() && File(rootfs, LOADER).isFile()

    /**
     * Extract and configure the layer. Idempotent and safe from any thread;
     * returns true when the layer is usable afterwards.
     */
    suspend fun ensureInstalled(progress: ((String) -> Unit)? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val al = rootfs
                if (!ready()) {
                    progress?.invoke("unpacking sandbox toolkit…")
                    context.assets.open("alpine.bin").use { input ->
                        TarGz.extractAll(
                            input = input,
                            destDir = al,
                            executable = true,
                            progress = Progress { msg -> progress?.invoke(msg) },
                            linker = Linker { destDir, name, target, exec ->
                                alpineLink(destDir, name, target, exec)
                            },
                        )
                    }
                    if (!File(al, LOADER).isFile()) {
                        throw IOException("rootfs incomplete (no musl loader)")
                    }
                    File(al, "root").mkdirs()
                    File(al, "tmp").mkdirs()
                    writeText(
                        File(al, "etc/apk/repositories"),
                        "http://dl-cdn.alpinelinux.org/alpine/$REPO_VER/main\n" +
                            "http://dl-cdn.alpinelinux.org/alpine/$REPO_VER/community\n",
                    )
                    writeText(
                        File(al, "etc/resolv.conf"),
                        "nameserver 127.0.0.1\n# DNS goes through the in-app proxy (.proxy)\n",
                    )
                    writeText(File(al, ".ready"), "ok $REPO_VER\n")
                    progress?.invoke("toolkit installed")
                }
                refreshProxy()
                installStaticBusybox()
                generateWrappers(false)
                true
            } catch (t: Throwable) {
                progress?.invoke("toolkit install failed: " + (t.message ?: t.toString()))
                false
            }
        }

    /**
     * (Re)write `<rootfs>/.proxy` with the local proxy port. Cheap and
     * idempotent; failures are ignored. Call from a worker thread.
     */
    fun refreshProxy() {
        try {
            val port = InAppProxy.ensureStarted()
            if (port <= 0) return
            val proxy = "http://127.0.0.1:$port"
            writeText(
                File(rootfs, ".proxy"),
                "http_proxy=$proxy\n" +
                    "https_proxy=$proxy\n" +
                    "HTTP_PROXY=$proxy\n" +
                    "HTTPS_PROXY=$proxy\n" +
                    "no_proxy=127.0.0.1,localhost,::1\n" +
                    "NO_PROXY=127.0.0.1,localhost,::1\n" +
                    "export http_proxy https_proxy HTTP_PROXY HTTPS_PROXY no_proxy NO_PROXY\n",
            )
        } catch (ignored: Exception) {
        }
    }

    /**
     * Environment variables the shell executor should hand to the alpine
     * world: the local proxy when it is up, empty otherwise.
     */
    fun proxyEnv(): Map<String, String> {
        val port = InAppProxy.port
        if (port <= 0) return emptyMap()
        val proxy = "http://127.0.0.1:$port"
        return mapOf(
            "http_proxy" to proxy,
            "https_proxy" to proxy,
            "HTTP_PROXY" to proxy,
            "HTTPS_PROXY" to proxy,
            "no_proxy" to "127.0.0.1,localhost,::1",
            "NO_PROXY" to "127.0.0.1,localhost,::1",
        )
    }

    /**
     * Generate `<filesDir>/wrappers/<cmd>` for every executable in the rootfs.
     * `force = false` only fills gaps (fast rehash); `force = true` rewrites
     * all. Returns the number of new command wrappers written. Call from a
     * worker thread.
     */
    fun generateWrappers(force: Boolean = false): Int {
        try {
            val al = rootfs
            if (!File(al, LOADER).isFile()) return 0
            val wrap = wrappersDir
            if (force) wrap.listFiles()?.forEach { it.delete() }

            var made = 0
            for (dir in SCAN) {
                val kids = File(al, dir).listFiles() ?: continue
                for (f in kids) {
                    try {
                        val name = f.name
                        if (name == "apk" || name == "pkg") continue
                        val w = File(wrap, name)
                        if (w.exists()) continue
                        val script = wrapperFor(f) ?: continue
                        writeText(w, script)
                        w.setExecutable(true, false)
                        w.setReadable(true, false)
                        made++
                    } catch (ignored: Exception) {
                    }
                }
            }

            try {
                val w = File(wrap, "apk")
                if (!w.exists() || force) {
                    writeText(
                        w,
                        prolog() +
                            "exec \"\$LB\" --library-path \"\$LP\" \"\$AL/sbin/apk\" " +
                            "--root \"\$AL\" --no-scripts \"\$@\"\n",
                    )
                    w.setExecutable(true, false)
                    w.setReadable(true, false)
                }
            } catch (ignored: Exception) {
            }

            try {
                val w = File(wrap, "pkg")
                if (!w.exists() || force) {
                    writeText(w, pkgWrapper())
                    w.setExecutable(true, false)
                    w.setReadable(true, false)
                }
            } catch (ignored: Exception) {
            }
            return made
        } catch (t: Throwable) {
            return 0
        }
    }

    /**
     * Executable search path: static busybox applets first, then the Alpine
     * wrappers, then the rootfs, then Android's shell. The static applets lead
     * because dynamic musl binaries die with SIGSYS ("Bad system call") on
     * devices that enforce seccomp — see [installStaticBusybox].
     */
    fun shellPath(): String {
        val al = rootfs.absolutePath
        return "${binDir.absolutePath}:${wrappersDir.absolutePath}:$al/bin:$al/usr/bin:/system/bin"
    }

    /** Bundled static-busybox applet directory, first on PATH. */
    val binDir: File by lazy {
        File(context.filesDir, "bin").apply { mkdirs() }
    }

    /**
     * P12-style "Bad system call" cure, ported from the old app: the bundled
     * busybox is musl-STATIC and survives seccomp, while the Alpine loader is
     * dynamic and dies with SIGSYS when it runs an applet directly on some
     * kernels. Copy the asset once and symlink every applet it supports into
     * [binDir] (which [shellPath] puts FIRST), so ls/cat/grep/… never reach the
     * dynamic loader. User-installed rootfs commands still win where an applet
     * does not exist. Idempotent; never throws. Call from a worker thread.
     */
    fun installStaticBusybox() {
        try {
            val bb = File(binDir, "busybox")
            if (!bb.exists() || bb.length() == 0L) {
                context.assets.open("busybox").use { input ->
                    FileOutputStream(bb).use { out -> input.copyTo(out) }
                }
                bb.setExecutable(true, false)
                bb.setReadable(true, false)
            }
            val flag = File(binDir, ".applets")
            val supported = if (flag.exists()) return else busyboxApplets(bb)
            val applets = supported.ifEmpty { CORE_APPLETS }
            for (name in applets) {
                if (name in SKIP_APPLETS) continue
                val dst = File(binDir, name)
                if (dst.exists()) continue
                try {
                    Os.symlink("busybox", dst.absolutePath)
                    dst.setExecutable(true, false)
                } catch (ignored: Exception) {
                }
            }
            writeText(flag, "ok\n")
        } catch (ignored: Throwable) {
        }
    }

    /** `busybox --list`, or empty when it cannot run (then CORE_APPLETS is used). */
    private fun busyboxApplets(bb: File): List<String> = try {
        val p = ProcessBuilder(bb.absolutePath, "--list")
            .redirectErrorStream(true)
            .start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    } catch (ignored: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------ extraction

    /**
     * Alpine link policy: create a real symlink with [Os.symlink]. Absolute
     * targets are rewritten relative to the rootfs root so every path stays
     * inside app storage; content-copy fallback when symlinks are refused.
     */
    private fun alpineLink(destDir: File, name: String, target: String, exec: Boolean) {
        try {
            val dst = File(destDir, name)
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val rewritten = if (target.startsWith("/")) relFromRoot(name, target.substring(1)) else target
            try {
                Os.symlink(rewritten, dst.absolutePath)
            } catch (linkFail: Exception) {
                copyWithinRootfs(destDir, name, rewritten)
                return
            }
            if (exec) dst.setExecutable(true, false)
        } catch (ignored: Exception) {
        }
    }

    /** "/bin/busybox" for a link at "usr/bin/ls" → "../../bin/busybox". */
    private fun relFromRoot(linkName: String, rootRel: String): String {
        val depth = linkName.count { it == '/' }
        return "../".repeat(depth) + rootRel
    }

    /** Emulated link: copy the resolved target's content within the rootfs. */
    private fun copyWithinRootfs(destDir: File, name: String, target: String) {
        try {
            val resolved = if (target.startsWith("/")) {
                TarGz.norm(target.substring(1))
            } else {
                TarGz.norm(TarGz.rel(name, target))
            }
            val src = File(destDir, resolved)
            if (!src.isFile) return
            val dst = File(destDir, name)
            dst.parentFile?.mkdirs()
            FileInputStream(src).use { input ->
                FileOutputStream(dst).use { out -> input.copyTo(out) }
            }
            dst.setExecutable(true, false)
        } catch (ignored: Exception) {
        }
    }

    // -------------------------------------------------------------- wrappers

    private fun prolog(): String {
        val al = rootfs.absolutePath
        // Point every wrapper at the rootfs Mozilla CA bundle when present, so
        // apk/curl/git/pip inside the layer get TLS too.
        val ca = "$al/etc/ssl/certs/ca-certificates.crt"
        return "#!/system/bin/sh\n" +
            "AL=$al\n" +
            "LB=\"\$AL/lib/ld-musl-aarch64.so.1\"\n" +
            "LP=\"\$AL/lib:\$AL/usr/lib\"\n" +
            "[ -r \"\$AL/.proxy\" ] && . \"\$AL/.proxy\"\n" +
            "if [ -r \"$ca\" ]; then\n" +
            "  export SSL_CERT_FILE=\"$ca\" CURL_CA_BUNDLE=\"$ca\" GIT_SSL_CAINFO=\"$ca\"\n" +
            "  export REQUESTS_CA_BUNDLE=\"$ca\" PIP_CERT=\"$ca\"\n" +
            "fi\n" +
            "export LD_LIBRARY_PATH=\"\$LP\"\n"
    }

    /** Build one wrapper script for a rootfs entry; null to skip. */
    private fun wrapperFor(f: File): String? {
        try {
            val real = resolveSymlinks(f, 8) ?: return null
            if (!File(real).isFile) return null

            val she = shebang(File(real))
            if (she != null) {
                val interp = she.first
                val base = interp.substringAfterLast('/')
                if (base.startsWith("python")) {
                    val py = rootfsResolve("/usr/bin/$base") ?: rootfsResolve("/usr/local/bin/$base")
                    if (py != null) {
                        return prolog() +
                            "exec \"\$LB\" --library-path \"\$LP\" \"${py.absolutePath}\" \"$real\" \"\$@\"\n"
                    }
                }
                if (base == "sh" || base == "ash") {
                    return prolog() +
                        "exec \"\$LB\" --library-path \"\$LP\" \"\$AL/bin/busybox\" $base \"$real\" \"\$@\"\n"
                }
                if (base == "env") {
                    return prolog() +
                        "exec \"\$LB\" --library-path \"\$LP\" \"\$AL/bin/busybox\" env \"$real\" \"\$@\"\n"
                }
                // Unknown interpreter: fall through and try the loader anyway.
            }

            if (real.endsWith("/bin/busybox")) {
                val applet = f.name
                return prolog() +
                    "exec \"\$LB\" --library-path \"\$LP\" \"\$AL/bin/busybox\" $applet \"\$@\"\n"
            }

            return prolog() +
                "exec \"\$LB\" --library-path \"\$LP\" \"$real\" \"\$@\"\n"
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * `pkg` front-end: maps install/remove/search/... onto `apk --root`, then
     * rehashes new commands onto PATH so they are runnable immediately.
     */
    private fun pkgWrapper(): String {
        val al = rootfs.absolutePath
        val wrap = wrappersDir.absolutePath
        val ca = "$al/etc/ssl/certs/ca-certificates.crt"
        return "#!/system/bin/sh\n" +
            "# pkg — sandbox package manager (Alpine layer, no proot)\n" +
            "AL=$al\n" +
            "WRAP=$wrap\n" +
            "LB=\"\$AL/lib/ld-musl-aarch64.so.1\"\n" +
            "export PATH=\"\$WRAP:\$AL/bin:\$AL/usr/bin:\$AL/sbin:\$AL/usr/sbin:/system/bin\"\n" +
            "export HOME=\"\$AL/root\" TMPDIR=\"\$AL/tmp\"\n" +
            "mkdir -p \"\$HOME\" \"\$TMPDIR\" \"\$AL/var/cache/apk\" \"\$WRAP\" 2>/dev/null\n" +
            "[ -r \"\$AL/.proxy\" ] && . \"\$AL/.proxy\"\n" +
            "export LD_LIBRARY_PATH=\"\$AL/lib:\$AL/usr/lib\"\n" +
            "if [ -r \"$ca\" ]; then\n" +
            "  export SSL_CERT_FILE=\"$ca\" CURL_CA_BUNDLE=\"$ca\" GIT_SSL_CAINFO=\"$ca\"\n" +
            "  export REQUESTS_CA_BUNDLE=\"$ca\" PIP_CERT=\"$ca\"\n" +
            "fi\n" +
            "if [ ! -x \"\$LB\" ]; then\n" +
            "  echo \"pkg: sandbox toolkit not installed yet\" >&2\n" +
            "  exit 127\n" +
            "fi\n" +
            "apk() { \"\$LB\" --library-path \"\$AL/lib:\$AL/usr/lib\" \"\$AL/sbin/apk\" --root \"\$AL\" --no-scripts \"\$@\"; }\n" +
            "usage() {\n" +
            "  echo \"pkg - sandbox package manager (apk front-end)\"\n" +
            "  echo \"  pkg update              refresh package index\"\n" +
            "  echo \"  pkg install <pkgs...>   install (e.g. pkg install python3 py3-pip git)\"\n" +
            "  echo \"  pkg remove <pkgs...>    remove\"\n" +
            "  echo \"  pkg search <word>       search the repos\"\n" +
            "  echo \"  pkg list                list installed\"\n" +
            "  echo \"  pkg info <pkg>          details for one package\"\n" +
            "  echo \"  pkg rehash              re-link new commands onto PATH\"\n" +
            "}\n" +
            "rehash() {\n" +
            "  made=0\n" +
            "  for d in bin usr/bin sbin usr/sbin usr/local/bin; do\n" +
            "    [ -d \"\$AL/\$d\" ] || continue\n" +
            "    for f in \"\$AL/\$d\"/*; do\n" +
            "      [ -f \"\$f\" ] || continue\n" +
            "      n=\${f##*/}\n" +
            "      [ \"\$n\" = apk ] && continue\n" +
            "      [ -e \"\$WRAP/\$n\" ] && continue\n" +
            "      {\n" +
            "        printf '#!/system/bin/sh\\nAL=%s\\n' \"\$AL\"\n" +
            "        printf 'LB=\"\$AL/lib/ld-musl-aarch64.so.1\"\\nLP=\"\$AL/lib:\$AL/usr/lib\"\\n'\n" +
            "        printf '[ -r \"\$AL/.proxy\" ] && . \"\$AL/.proxy\"\\n'\n" +
            "        printf 'CA=\"%s\"\\n' \"\$AL/etc/ssl/certs/ca-certificates.crt\"\n" +
            "        printf '[ -r \"\$CA\" ] && export SSL_CERT_FILE=\"\$CA\" CURL_CA_BUNDLE=\"\$CA\" GIT_SSL_CAINFO=\"\$CA\" REQUESTS_CA_BUNDLE=\"\$CA\" PIP_CERT=\"\$CA\"\\n'\n" +
            "        printf 'export LD_LIBRARY_PATH=\"\$LP\"\\nexec \"\$LB\" --library-path \"\$LP\"'\n" +
            "      } > \"\$WRAP/\$n.part\"\n" +
            "      t=\$(readlink -f \"\$f\" 2>/dev/null); [ -z \"\$t\" ] && t=\$f\n" +
            "      case \"\$t\" in\n" +
            "        */bin/busybox) printf ' \"\$AL/bin/busybox\" \"%s\"' \"\$n\" >> \"\$WRAP/\$n.part\" ;;\n" +
            "        *)\n" +
            "          case \"\$(dd if=\"\$t\" bs=2 count=1 2>/dev/null)\" in\n" +
            "            '#!') il=\$(head -n 1 \"\$t\" 2>/dev/null); il=\${il##*#!}; il=\${il# }; ip=\${il%% *}\n" +
            "              case \"\$ip\" in\n" +
            "                */python*) printf ' \"\$AL/usr/bin/python3\" \"%s\"' \"\$t\" >> \"\$WRAP/\$n.part\" ;;\n" +
            "                */sh|*/ash) printf ' \"\$AL/bin/busybox\" sh \"%s\"' \"\$t\" >> \"\$WRAP/\$n.part\" ;;\n" +
            "                *) printf ' \"%s\"' \"\$t\" >> \"\$WRAP/\$n.part\" ;;\n" +
            "              esac ;;\n" +
            "            *) printf ' \"%s\"' \"\$t\" >> \"\$WRAP/\$n.part\" ;;\n" +
            "          esac ;;\n" +
            "      esac\n" +
            "      printf ' \"\$@\"\\n' >> \"\$WRAP/\$n.part\"\n" +
            "      chmod 755 \"\$WRAP/\$n.part\" 2>/dev/null && mv \"\$WRAP/\$n.part\" \"\$WRAP/\$n\"\n" +
            "      made=\$((made+1))\n" +
            "    done\n" +
            "  done\n" +
            "  echo \"pkg: linked \$made commands\"\n" +
            "}\n" +
            "rc=0\n" +
            "case \"\$1\" in\n" +
            "  install|add) shift; apk add \"\$@\"; rc=\$?; [ \$rc -eq 0 ] && rehash ;;\n" +
            "  remove|del|uninstall) shift; apk del \"\$@\"; rc=\$?; [ \$rc -eq 0 ] && rehash ;;\n" +
            "  update) shift; apk update; rc=\$? ;;\n" +
            "  upgrade) shift; apk upgrade; rc=\$? ;;\n" +
            "  search) shift; [ \$# -gt 0 ] && apk search -v \"\$@\"; rc=\$? ;;\n" +
            "  list|installed) apk info; rc=\$? ;;\n" +
            "  info) shift; apk info -a \"\$1\" 2>/dev/null; rc=\$? ;;\n" +
            "  rehash) rehash; rc=0 ;;\n" +
            "  version|--version) apk --version; rc=\$? ;;\n" +
            "  help|-h|*) usage; rc=0 ;;\n" +
            "esac\n" +
            "exit \$rc\n"
    }

    /** Follow real symlinks (created at extraction) to the final file. */
    private fun resolveSymlinks(f: File, depth: Int): String? {
        try {
            var cur = f.canonicalFile
            var left = depth
            while (left-- > 0) {
                val target = try {
                    Os.readlink(cur.absolutePath)
                } catch (notLink: Exception) {
                    return cur.absolutePath
                }
                if (target.isNullOrEmpty()) return cur.absolutePath
                val next = File(target)
                cur = (if (next.isAbsolute) next else File(cur.parentFile, target)).canonicalFile
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    /** Resolve an absolute-in-rootfs path (like /usr/bin/python3). */
    private fun rootfsResolve(absInRootfs: String): File? {
        return try {
            val f = File(rootfs, TarGz.norm(absInRootfs.substring(1)))
            val resolved = resolveSymlinks(f, 8)
            if (resolved != null && File(resolved).isFile) File(resolved) else null
        } catch (e: Exception) {
            null
        }
    }

    /** First line "#!" → the interpreter path; null when not a script. */
    private fun shebang(f: File): Pair<String, String>? {
        try {
            FileInputStream(f).use { input ->
                val b = ByteArray(120)
                val n = input.read(b)
                if (n < 2 || b[0] != '#'.code.toByte() || b[1] != '!'.code.toByte()) return null
                var line = String(b, 2, maxOf(0, n - 2), Charsets.UTF_8)
                val nl = line.indexOf('\n')
                if (nl >= 0) line = line.substring(0, nl)
                line = line.trim()
                if (line.isEmpty()) return null
                return line.split(Regex("\\s+")).first() to line
            }
        } catch (e: Exception) {
            return null
        }
    }

    // ------------------------------------------------------------------ misc

    private fun writeText(f: File, s: String) {
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.parentFile?.mkdirs()
        FileOutputStream(tmp).use { it.write(s.toByteArray(Charsets.UTF_8)) }
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) throw IOException("rename failed: $f")
    }

    private companion object {
        const val REPO_VER = "v3.22"
        const val LOADER = "lib/ld-musl-aarch64.so.1"
        val SCAN = listOf("bin", "usr/bin", "sbin", "usr/sbin", "usr/local/bin")

        /** Applets never shadowed: these are owned by the wrappers/shims. */
        val SKIP_APPLETS = setOf("busybox", "sh", "ash", "bash", "git", "pkg", "apk")

        /** Fallback applet set when `busybox --list` cannot run. */
        val CORE_APPLETS = listOf(
            "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "echo", "printf",
            "grep", "egrep", "fgrep", "sed", "awk", "find", "tar", "gzip",
            "gunzip", "zcat", "head", "tail", "wc", "touch", "chmod", "chown",
            "ln", "which", "uname", "env", "cut", "tr", "sort", "uniq", "date",
            "du", "df", "stat", "sleep", "basename", "dirname", "sha256sum",
            "md5sum", "wget", "od", "dd", "ps", "id", "whoami", "expr", "less",
            "more", "diff", "vi", "clear", "free", "netstat", "pgrep", "kill",
            "nice", "nohup", "seq", "yes", "true", "false", "xargs", "realpath",
            "readlink", "fold", "comm", "expand", "unexpand", "sum", "sync",
            "time", "timeout", "tty",
        )
    }
}
