package dev.lumen.sandbox

import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/**
 * In-app HTTP proxy (CONNECT + absolute-URI GET/POST) bound to `127.0.0.1`
 * only. Hostnames are resolved with the JDK's [InetAddress] resolver (i.e. the
 * host's resolver), requests are forwarded verbatim, and nothing is rewritten.
 *
 * Process-wide singleton: [ensureStarted] starts once and returns the bound
 * port, or `-1` when the proxy is unavailable.
 */
object InAppProxy {

    /** The bound local port, or `-1` when not started. */
    @Volatile
    var port: Int = -1
        private set

    @Volatile
    private var acceptor: ServerSocket? = null

    private val live = AtomicInteger()
    private val errTail = StringBuilder()
    private val lock = Any()

    /** Bound concurrent proxy connections. */
    private const val MAX_CONNS = 64

    /** Finite idle timeout so a stalled tunnel frees its connection slot. */
    private const val TUNNEL_IDLE_MS = 60_000

    /** Start once; returns the local port, or `-1` if the proxy is unavailable. */
    fun ensureStarted(): Int {
        if (port > 0 && acceptor?.isClosed == false) return port
        synchronized(lock) {
            if (port > 0 && acceptor?.isClosed == false) return port
            return try {
                val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
                ss.reuseAddress = true
                acceptor = ss
                port = ss.localPort
                Thread({ acceptLoop(ss) }, "lumen-proxy-accept").apply {
                    isDaemon = true
                    start()
                }
                note("proxy listening on 127.0.0.1:$port")
                port
            } catch (e: Exception) {
                note("proxy start FAILED: $e")
                port = -1
                -1
            }
        }
    }

    /** Stop accepting connections and release the port. */
    fun stop() {
        synchronized(lock) {
            try {
                acceptor?.close()
            } catch (ignored: Exception) {
            }
            acceptor = null
            port = -1
        }
    }

    /** Recent diagnostics for the proxy, newest last. */
    val errors: String
        get() = synchronized(errTail) { errTail.toString() }

    private fun note(s: String) {
        synchronized(errTail) {
            if (errTail.length > 4000) errTail.delete(0, 2000)
            errTail.append(s).append('\n')
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (true) {
            try {
                val client = ss.accept()
                if (live.get() >= MAX_CONNS) {
                    note("conn cap $MAX_CONNS hit — refusing client")
                    try {
                        client.close()
                    } catch (ignored: Exception) {
                    }
                    continue
                }
                live.incrementAndGet()
                Thread({
                    try {
                        handle(client)
                    } finally {
                        live.decrementAndGet()
                    }
                }, "lumen-proxy").apply {
                    isDaemon = true
                    start()
                }
            } catch (e: Exception) {
                if (ss.isClosed) return
                note("accept: $e")
                try {
                    Thread.sleep(200)
                } catch (ie: InterruptedException) {
                    return
                }
            }
        }
    }

    /** One proxied client connection. */
    private fun handle(client: Socket) {
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000 // header read only; tunnels clear it
            val input = client.getInputStream()
            val reqline = readLine(input)
            if (reqline.isNullOrEmpty()) {
                client.close()
                return
            }
            val headers = StringBuilder()
            var l = readLine(input)
            while (l != null && l.isNotEmpty()) {
                headers.append(l).append("\r\n")
                l = readLine(input)
            }
            client.soTimeout = 0

            if (reqline.regionMatches(0, "CONNECT ", 0, 8, ignoreCase = true)) {
                doConnect(client, input, reqline.substring(8).trim())
            } else {
                doPlainHttp(client, input, reqline, headers.toString())
            }
        } catch (e: Exception) {
            note("client: $e")
            try {
                client.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /** CONNECT host:port — tunnel after resolving with the JDK resolver. */
    private fun doConnect(client: Socket, cin: InputStream, hostport: String) {
        var host = hostport
        var p = 443
        val c = host.lastIndexOf(':')
        if (c > 0) {
            host = hostport.substring(0, c)
            try {
                p = hostport.substring(c + 1).toInt()
            } catch (ignored: Exception) {
            }
        }
        var up: Socket? = null
        try {
            val addrs = InetAddress.getAllByName(host)
            var last: IOException? = null
            for (a in addrs) {
                try {
                    up = Socket()
                    up.connect(InetSocketAddress(a, p), 15_000)
                    break
                } catch (e: IOException) {
                    last = e
                    try {
                        up?.close()
                    } catch (ignored: Exception) {
                    }
                    up = null
                }
            }
            if (up == null) throw last ?: IOException("no addresses")
            up.tcpNoDelay = true
            val cout = client.getOutputStream()
            cout.write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.UTF_8))
            cout.flush()
            pump(client, cin, up)
        } catch (e: Exception) {
            note("CONNECT $hostport failed: $e")
            try {
                val cout = client.getOutputStream()
                cout.write("HTTP/1.1 502 ${sanitize(e)}\r\n\r\n".toByteArray(Charsets.UTF_8))
                cout.flush()
            } catch (ignored: Exception) {
            }
            try {
                client.close()
            } catch (ignored: Exception) {
            }
            up?.let {
                try {
                    it.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    /** Absolute-URI plain-HTTP request — forward with Connection: close. */
    private fun doPlainHttp(client: Socket, cin: InputStream, reqline: String, headers: String) {
        var up: Socket? = null
        try {
            val parts = reqline.split(" ")
            if (parts.size < 2) throw IOException("bad request line")
            val u = URI.create(parts[1])
            val host = u.host ?: throw IOException("no host in ${parts[1]}")
            val p = if (u.port > 0) u.port else 80
            up = Socket()
            up.connect(InetSocketAddress(InetAddress.getByName(host), p), 15_000)
            val uo = up.getOutputStream()
            val pathq = (u.rawPath ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            uo.write("${parts[0]} $pathq HTTP/1.1\r\n".toByteArray(Charsets.UTF_8))
            uo.write("Host: $host${if (p != 80) ":$p" else ""}\r\n".toByteArray(Charsets.UTF_8))
            uo.write("Connection: close\r\n".toByteArray(Charsets.UTF_8))
            uo.write(headers.toByteArray(Charsets.UTF_8))
            uo.write("\r\n".toByteArray(Charsets.UTF_8))
            uo.flush()
            pump(client, cin, up)
        } catch (e: Exception) {
            note("HTTP $reqline failed: $e")
            try {
                client.close()
            } catch (ignored: Exception) {
            }
        } finally {
            up?.let {
                try {
                    it.close()
                } catch (ignored: Exception) {
                }
            }
        }
    }

    /** Bidirectional copy until either side closes. */
    private fun pump(client: Socket, cin: InputStream, up: Socket) {
        try {
            up.getInputStream()
        } catch (e: IOException) {
            try {
                client.close()
            } catch (ignored: Exception) {
            }
            return
        }
        try {
            client.soTimeout = TUNNEL_IDLE_MS
        } catch (ignored: Exception) {
        }
        try {
            up.soTimeout = TUNNEL_IDLE_MS
        } catch (ignored: Exception) {
        }
        Thread({ copy(cin, up) }, "lumen-proxy-c2u").apply {
            isDaemon = true
            start()
        }
        try {
            copy(up.getInputStream(), client)
        } catch (ignored: Exception) {
        }
        try {
            client.close()
        } catch (ignored: Exception) {
        }
        try {
            up.close()
        } catch (ignored: Exception) {
        }
    }

    private fun copy(input: InputStream, out: Socket) {
        val buf = ByteArray(16 * 1024)
        try {
            out.getOutputStream().use { o ->
                var n = input.read(buf)
                while (n > 0) {
                    o.write(buf, 0, n)
                    n = input.read(buf)
                }
                o.flush()
            }
        } catch (ignored: Exception) {
        } finally {
            try {
                out.shutdownOutput()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun readLine(input: InputStream): String? {
        val b = StringBuilder(80)
        var prev = -1
        while (true) {
            val i = input.read()
            if (i < 0) return if (b.isEmpty()) null else b.toString()
            if (prev == '\r'.code && i == '\n'.code) return b.substring(0, b.length - 1)
            b.append(i.toChar())
            prev = i
            if (b.length > 8192) throw IOException("header line too long")
        }
    }

    private fun sanitize(t: Throwable): String {
        var m = t.message.orEmpty()
        m = m.replace("\r", " ").replace("\n", " ")
        return if (m.length > 120) m.substring(0, 120) else m
    }
}
