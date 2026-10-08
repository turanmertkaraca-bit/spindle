package dev.lumen.app.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import dev.lumen.sandbox.InAppProxy
import dev.lumen.sandbox.Linker
import dev.lumen.sandbox.Progress
import dev.lumen.sandbox.TarGz
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The Debian layer: a real Debian 12 (bookworm) userland with apt, git,
 * python and node, run through proot (user-space rootfs, no root needed).
 *
 * LAYOUT (all under `filesDir`)
 *   debian/rootfs/    ONE shared rootfs for every project (packages install
 *                     once; each session binds only its own folder).
 *   debian/tmp/       PROOT_TMP_DIR — must exist before proot runs.
 *   debian/.extracted marker: rootfs fully extracted + configured.
 *   debian/.probed    marker: the proot probe passed on this device.
 *   debianbin/        bundled Termux proot + loader + libtalloc +
 *                     libandroid-shmem (bionic, exec-allowed private storage).
 *   home/             opencode home, bound into the guest at its real path.
 *
 * ROOTFS SOURCE — the official `debian:bookworm` arm64 Docker image:
 *   1. GET https://auth.docker.io/token (anonymous pull scope)
 *   2. GET the manifest list → linux/arm64 digest
 *   3. GET the arm64 manifest → first layer digest
 *   4. GET the blob (~48 MB tar.gz) → TarGz.extractAll with the Debian link
 *      policy (absolute symlinks rewritten root-relative, content-copy
 *      fallback via android.system.Os.symlink).
 *
 * OPT-IN: Debian is never downloaded on its own. [install] is the explicit
 * action (a large ~48 MB download); the shell executor only routes through
 * Debian once [active] is true. If the probe fails, [active] stays false and
 * the Alpine layer remains the shell.
 *
 * NETWORK: every guest shell exports http(s)_proxy pointing at [InAppProxy]
 * (CONNECT + absolute-URI GET, host-resolver DNS). The registry download
 * itself also rides [InAppProxy] when it is up. `/etc/resolv.conf` inside the
 * rootfs is a decoy pointing at 127.0.0.1.
 */
class DebianEnvironment(private val context: Context) {

    /** `filesDir/debian` — markers, tmp and the rootfs. */
    val dir: File get() = File(context.filesDir, "debian")

    /** The extracted Debian root. */
    val rootfs: File get() = File(dir, "rootfs")

    /** Unpacked proot toolkit (proot, loader, libtalloc, libandroid-shmem). */
    val binDir: File get() = File(context.filesDir, "debianbin")

    /** PROOT_TMP_DIR — created before anything runs. */
    val tmpDir: File get() = File(dir, "tmp")

    /** opencode home; bound into the guest at its real device path. */
    val homeDir: File get() = File(context.filesDir, "home").apply { mkdirs() }

    private val installLock = Mutex()

    /**
     * Rootfs present and the toolkit has been unpacked: `/bin/bash` plus the
     * `.extracted` marker. Does not imply the proot probe passed.
     */
    fun ready(): Boolean =
        try {
            File(rootfs, "bin/bash").isFile && File(dir, ".extracted").exists()
        } catch (t: Throwable) {
            false
        }

    /** [ready] and a passed probe: the guest shell may be used. */
    fun active(): Boolean =
        try {
            ready() && File(dir, ".probed").exists()
        } catch (t: Throwable) {
            false
        }

    /** Create every directory proot needs before it can run. Never throws. */
    fun ensureDirs() {
        try {
            dir.mkdirs()
            tmpDir.mkdirs()
            homeDir.mkdirs()
            if (File(rootfs, "bin/bash").isFile) File(rootfs, "tmp").mkdirs()
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------ install

    /**
     * Full install: unpack the toolkit → download the layer → extract with
     * the Debian link policy → configure → probe. Suspending and idempotent;
     * skips whatever is already done. Returns true only when Debian is
     * [active] afterwards, so a device that refuses proot keeps Alpine.
     */
    suspend fun install(progress: ((String) -> Unit)? = null): Boolean = installLock.withLock {
        withContext(Dispatchers.IO) {
            try {
                ensureDirs()
                if (ready()) {
                    if (!File(dir, ".probed").exists()) probe()
                    return@withContext active()
                }
                progress?.invoke("unpacking proot toolkit…")
                unpackBinaries()

                val blob = File(context.cacheDir, "debian-rootfs.tar.gz")
                if (!blob.isFile || blob.length() < 30_000_000L) {
                    progress?.invoke("resolving debian:bookworm arm64 image…")
                    val digest = resolveLayer()
                    progress?.invoke("downloading Debian 12 rootfs (~48 MB)…")
                    downloadBlob(digest, blob)
                }

                progress?.invoke("extracting rootfs (one-time)…")
                extract(blob, progress)
                runCatching { blob.delete() }

                progress?.invoke("configuring apt + DNS bridge…")
                configure()

                writeText(File(dir, ".extracted"), "ok\n")

                progress?.invoke("probing proot on this device…")
                val ok = probe()
                if (!ok) progress?.invoke("proot not supported here — Alpine shell stays active")
                active()
            } catch (t: Throwable) {
                progress?.invoke("install failed: " + (t.message ?: t.toString()))
                false
            }
        }
    }

    /** Unpack the bundled `db_*` assets into [binDir], mode 755. */
    fun unpackBinaries() {
        binDir.mkdirs()
        copyAssetExec("db_proot", File(binDir, "proot"))
        copyAssetExec("db_loader", File(binDir, "loader"))
        copyAssetExec("db_shmem", File(binDir, "libandroid-shmem.so"))
        val talloc = File(binDir, "libtalloc.so")
        copyAssetExec("db_talloc", talloc)
        // Belt: some proot builds carry SONAME libtalloc.so.2.
        val soname = File(binDir, "libtalloc.so.2")
        if (!soname.exists()) {
            talloc.copyTo(soname, overwrite = true)
            soname.setExecutable(true, false)
        }
    }

    // --------------------------------------------------- registry dance

    private fun resolveLayer(): String {
        val token = authToken()
        val list = httpGet(
            REG + "manifests/" + TAG_VER,
            token,
            "application/vnd.docker.distribution.manifest.list.v2+json," +
                " application/vnd.oci.image.index.v1+json",
        )
        val manifests = JSONObject(list).optJSONArray("manifests")
            ?: throw IOException("no manifests for $TAG_VER")
        var armDigest: String? = null
        for (i in 0 until manifests.length()) {
            val m = manifests.getJSONObject(i)
            val p = m.optJSONObject("platform") ?: continue
            if (p.optString("os") == "linux" && p.optString("architecture") == "arm64") {
                armDigest = m.optString("digest")
                break
            }
        }
        if (armDigest.isNullOrEmpty()) throw IOException("no linux/arm64 manifest for $TAG_VER")

        val man = httpGet(
            REG + "manifests/" + armDigest,
            token,
            "application/vnd.docker.distribution.manifest.v2+json," +
                " application/vnd.oci.image.manifest.v1+json",
        )
        val layers = JSONObject(man).optJSONArray("layers")
            ?: throw IOException("image has no layers")
        if (layers.length() == 0) throw IOException("image has no layers")
        val digest = layers.getJSONObject(0).optString("digest")
        if (digest.isEmpty()) throw IOException("layer digest missing")
        return digest
    }

    private fun authToken(): String {
        val body = httpGet(REG_AUTH, null, null)
        val token = JSONObject(body).optString("token")
        if (token.isEmpty()) throw IOException("registry auth failed")
        return token
    }

    private fun downloadBlob(digest: String, dst: File) {
        val token = authToken()
        val conn = open(REG + "blobs/" + digest, token, null)
        try {
            if (conn.responseCode != 200) throw IOException("blob HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            val tmp = File(dst.parentFile, dst.name + ".part")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var n = input.read(buf)
                    while (n > 0) {
                        out.write(buf, 0, n)
                        done += n
                        n = input.read(buf)
                    }
                }
            }
            // Verify the layer against the digest the registry advertised before
            // we trust it as a rootfs; a flipped bit or a swapped blob must not
            // be extracted and executed.
            val expected = digest.removePrefix("sha256:").lowercase()
            if (expected.isNotEmpty() && !sha256(tmp).equals(expected, ignoreCase = true)) {
                runCatching { tmp.delete() }
                throw IOException("rootfs digest mismatch")
            }
            if (dst.exists()) dst.delete()
            if (!tmp.renameTo(dst)) throw IOException("blob rename failed")
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            var n = input.read(buf)
            while (n > 0) {
                md.update(buf, 0, n)
                n = input.read(buf)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun httpGet(url: String, token: String?, accept: String?): String {
        val conn = open(url, token, accept)
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode} from ${URL(url).host}")
            return conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun open(url: String, token: String?, accept: String?): HttpURLConnection {
        val port = InAppProxy.ensureStarted()
        val conn = if (port > 0) {
            URL(url).openConnection(
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)),
            ) as HttpURLConnection
        } else {
            URL(url).openConnection() as HttpURLConnection
        }
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.useCaches = false
        if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
        if (accept != null) conn.setRequestProperty("Accept", accept)
        return conn
    }

    // ------------------------------------------------------- extraction

    private fun extract(blob: File, progress: ((String) -> Unit)?) {
        rootfs.mkdirs()
        FileInputStream(blob).use { input ->
            TarGz.extractAll(
                input = input,
                destDir = rootfs,
                executable = true,
                progress = Progress { msg -> progress?.invoke(msg) },
                linker = Linker { destDir, name, target, exec ->
                    debianLink(destDir, name, target, exec)
                },
            )
        }
    }

    /**
     * Debian link policy: a real symlink via [Os.symlink]; absolute targets
     * are rewritten relative to the rootfs root; content-copy fallback when
     * symlinks are refused.
     */
    internal fun debianLink(destDir: File, name: String, target: String, exec: Boolean) {
        try {
            val dst = File(destDir, name)
            dst.parentFile?.mkdirs()
            if (dst.exists()) dst.delete()
            val rewritten = if (target.startsWith("/")) relFromRoot(name, target.substring(1)) else target
            try {
                Os.symlink(rewritten, dst.absolutePath)
            } catch (linkFail: Throwable) {
                copyWithinRootfs(destDir, name, rewritten)
                return
            }
            if (exec) dst.setExecutable(true, false)
        } catch (ignored: Throwable) {
        }
    }

    /** "/bin/busybox" for a link at "usr/bin/ls" → "../../bin/busybox". */
    internal fun relFromRoot(linkName: String, rootRel: String): String {
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
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------- configure

    private fun configure() {
        try {
            writeText(
                File(rootfs, "etc/apt/sources.list"),
                "deb http://deb.debian.org/debian $TAG_VER main contrib non-free non-free-firmware\n" +
                    "deb http://security.debian.org/debian-security $TAG_VER-security main contrib non-free non-free-firmware\n",
            )
            File(rootfs, "etc/apt/sources.list.d").listFiles()?.forEach { it.delete() }
            writeText(
                File(rootfs, "etc/resolv.conf"),
                "nameserver 127.0.0.1\n# DNS rides on http_proxy (in-app bridge)\n",
            )
            writeText(
                File(rootfs, "etc/apt/apt.conf.d/99lumen"),
                "APT::Install-Recommends \"false\";\n" +
                    "Acquire::Retries \"3\";\n" +
                    "Dpkg::Options { \"--force-confnew\"; };\n",
            )
            writeText(
                File(rootfs, "root/.gitconfig"),
                "[safe]\n\tdirectory = *\n" +
                    "[http]\n\tlowSpeedLimit = 1000\n\tlowSpeedTime = 30\n\tpostBuffer = 524288000\n",
            )
            File(rootfs, "root/project").mkdirs()
            File(rootfs, "tmp").mkdirs()
            seedCa()
        } catch (ignored: Throwable) {
        }
    }

    /** Seed the system CA bundle into the rootfs when one is readable. */
    private fun seedCa() {
        try {
            val dst = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
            if (dst.isFile && dst.length() > 0) return
            val src = SYSTEM_CA_CANDIDATES
                .map { File(it) }
                .firstOrNull { it.isFile && it.length() > 0 } ?: return
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------ proot

    /**
     * The proot argv for one guest command. This is the exact rehearsed flag
     * set (no `-L`): `--rootfs --cwd=<cwd|/root> -0 --kill-on-exit
     * --link2symlink` plus the /dev, /proc, /sys, cwd, Downloads and home
     * binds, then `/bin/bash -c <command>`.
     *
     * The guest working directory is the bound session [cwd] when one is
     * supplied, so a relative path in a command resolves to the same file the
     * app's own tools (and the Files view) see. Left at `/root` only for a
     * caller that has no working directory.
     */
    fun prootArgv(command: String, cwd: String): List<String> {
        val a = ArrayList<String>()
        a += File(binDir, "proot").absolutePath
        a += "--rootfs=" + rootfs.absolutePath
        a += "--cwd=" + if (cwd.isNotBlank()) cwd else "/root"
        a += "-0"
        a += "--kill-on-exit"
        a += "--link2symlink"
        a += "--bind=/dev"
        a += "--bind=/proc"
        a += "--bind=/sys"
        if (cwd.isNotBlank()) a += "--bind=$cwd:$cwd"
        // Shared Downloads is RW only when the user granted legacy external
        // storage: otherwise the sandbox cannot touch anything outside the
        // session workspace + app-private home.
        val dl = File(DOWNLOADS)
        if (dl.isDirectory && downloadsAllowed()) a += "--bind=${dl.absolutePath}:${dl.absolutePath}"
        a += "--bind=${homeDir.absolutePath}:${homeDir.absolutePath}"
        a += "/bin/bash"
        a += "-c"
        a += command
        return a
    }

    /**
     * A [ProcessBuilder] for one proot run: host-side proot env (PROOT_LOADER,
     * PROOT_TMP_DIR, LD_LIBRARY_PATH, PROOT_IGNORE_MISSING_BINDINGS,
     * PROOT_NO_SECCOMP) and guest env (proxy, DEBIAN_FRONTEND, HOME, PATH,
     * TMPDIR, TERM, CA exports). stderr is merged into stdout.
     */
    fun guestProcess(command: String, cwd: String): ProcessBuilder {
        ensureDirs()
        val pb = ProcessBuilder(prootArgv(command, cwd))
        pb.redirectErrorStream(true)
        val e = pb.environment()
        // Never hand the app process's ambient secrets to a guest command.
        scrubHostEnv(e)
        e["PROOT_LOADER"] = File(binDir, "loader").absolutePath
        e["PROOT_TMP_DIR"] = tmpDir.absolutePath
        e["LD_LIBRARY_PATH"] = binDir.absolutePath
        e["PROOT_IGNORE_MISSING_BINDINGS"] = "1"
        e["PROOT_NO_SECCOMP"] = "1"

        val port = InAppProxy.ensureStarted()
        if (port > 0) {
            val proxy = "http://127.0.0.1:$port"
            e["http_proxy"] = proxy
            e["https_proxy"] = proxy
            e["HTTP_PROXY"] = proxy
            e["HTTPS_PROXY"] = proxy
            e["no_proxy"] = "127.0.0.1,localhost,::1"
            e["NO_PROXY"] = "127.0.0.1,localhost,::1"
        }

        e["DEBIAN_FRONTEND"] = "noninteractive"
        e["HOME"] = "/root"
        e["USER"] = "root"
        e["PATH"] = PATH
        e["TMPDIR"] = "/tmp"
        e["TERM"] = "xterm-256color"

        val ca = File(rootfs, "etc/ssl/certs/ca-certificates.crt")
        if (ca.isFile) {
            val bundle = "/etc/ssl/certs/ca-certificates.crt"
            e["SSL_CERT_FILE"] = bundle
            e["CURL_CA_BUNDLE"] = bundle
            e["GIT_SSL_CAINFO"] = bundle
            e["REQUESTS_CA_BUNDLE"] = bundle
            e["PIP_CERT"] = bundle
        }
        return pb
    }

    // ------------------------------------------------------------ probe

    /**
     * The gate: run `proot … /bin/echo probe-ok`. Writes `.probed` on success,
     * deletes it on failure, and never throws. When it fails the Alpine layer
     * stays the shell.
     */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        if (!File(rootfs, "bin/bash").isFile) return@withContext false
        var process: Process? = null
        try {
            process = guestProcess("echo probe-ok", rootfs.absolutePath).start()
            val out = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { r ->
                        val buf = CharArray(4096)
                        var n = r.read(buf)
                        while (n >= 0) {
                            out.append(buf, 0, n)
                            n = r.read(buf)
                        }
                    }
                } catch (ignored: Throwable) {
                }
            }
            reader.isDaemon = true
            reader.start()

            val done = process.waitFor(PROBE_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!done) runCatching { process.destroyForcibly() }
            runCatching { reader.join(2000) }

            val rc = runCatching { process.exitValue() }.getOrDefault(-1)
            val ok = done && rc == 0 && out.contains("probe-ok")
            if (ok) {
                writeText(File(dir, ".probed"), "ok\n")
            } else {
                runCatching { File(dir, ".probed").delete() }
            }
            ok
        } catch (t: Throwable) {
            runCatching { File(dir, ".probed").delete() }
            false
        } finally {
            runCatching { if (process?.isAlive == true) process.destroyForcibly() }
        }
    }

    // ------------------------------------------------------------ assets

    private fun copyAssetExec(asset: String, dst: File) {
        val tmp = File(dst.parentFile, dst.name + ".part")
        context.assets.open(asset).use { input ->
            FileOutputStream(tmp).use { out -> input.copyTo(out) }
        }
        if (dst.exists()) dst.delete()
        if (!tmp.renameTo(dst)) throw IOException("rename failed: $dst")
        dst.setExecutable(true, false)
    }

    // -------------------------------------------------------------- misc

    /**
     * Best-effort launch housekeeping: drop stale temp files and any partial
     * download left by an interrupted install. Never touches the rootfs or the
     * user's files. Returns a short human-readable summary for the boot screen.
     */
    suspend fun maintenance(): String = withContext(Dispatchers.IO) {
        var freed = 0L
        try {
            tmpDir.listFiles()?.forEach { f -> val n = f.length(); if (f.delete()) freed += n }
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(".part") }?.forEach { f ->
                val n = f.length()
                if (f.delete()) freed += n
            }
            File(context.cacheDir, "debian-rootfs.tar.gz.part").takeIf { it.isFile }?.let { f ->
                val n = f.length()
                if (f.delete()) freed += n
            }
        } catch (ignored: Throwable) {
        }
        if (freed <= 0) "environment ready" else "reclaimed ${freed / 1024} KiB"
    }

    /** True when legacy external-storage access was granted (targetSdk 28). */
    private fun downloadsAllowed(): Boolean = try {
        context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        false
    }

    /** Drop host secrets from the inherited env before adding guest vars. */
    private fun scrubHostEnv(env: MutableMap<String, String>) {
        val suffixes = listOf("_TOKEN", "_KEY", "_SECRET", "_PASSWORD", "_PASSWD", "_CREDENTIAL")
        env.keys.removeAll { key ->
            val upper = key.uppercase()
            key == "JAVA_TOOL_OPTIONS" || key == "_JAVA_OPTIONS" || key == "GITHUB_TOKEN" ||
                suffixes.any { upper.endsWith(it) }
        }
    }

    private fun writeText(f: File, s: String) {
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.parentFile?.mkdirs()
        FileOutputStream(tmp).use { it.write(s.toByteArray(StandardCharsets.UTF_8)) }
        if (f.exists()) f.delete()
        if (!tmp.renameTo(f)) throw IOException("rename failed: $f")
    }

    companion object {
        const val TAG_VER = "bookworm"

        private const val REG_AUTH =
            "https://auth.docker.io/token?service=registry.docker.io" +
                "&scope=repository:library/debian:pull"
        private const val REG = "https://registry-1.docker.io/v2/library/debian/"

        private const val DOWNLOADS = "/storage/emulated/0/Download"

        private const val PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

        private const val PROBE_TIMEOUT_SEC = 30L

        private val SYSTEM_CA_CANDIDATES = listOf(
            "/system/etc/security/ca-certificates.crt",
            "/system/etc/security/cacerts.pem",
            "/etc/ssl/certs/ca-certificates.crt",
        )

        /**
         * Opt-in gate for the shell executor: when false (the default) Debian
         * is never downloaded implicitly and only an already-[active] layer is
         * used. An explicit [install] is the way to turn it on.
         */
        @Volatile
        var autoInstallOnFirstUse: Boolean = false
    }
}
