package dev.lumen.sandbox

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InAppProxyTest {

    @Test
    fun `forwards an absolute-URI GET through the proxy`() {
        val echo = EchoServer("pong")
        echo.start()
        try {
            val proxyPort = InAppProxy.ensureStarted()
            assertTrue(proxyPort > 0, "proxy should bind a port: ${InAppProxy.errors}")
            assertEquals(proxyPort, InAppProxy.ensureStarted(), "start is idempotent")

            val url = URI("http://127.0.0.1:${echo.port}/hello").toURL()
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
            val conn = url.openConnection(proxy) as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            conn.disconnect()

            assertEquals("pong", body)
        } finally {
            echo.stop()
            InAppProxy.stop()
        }
    }

    @Test
    fun `port is minus one before start and after stop`() {
        InAppProxy.stop()
        assertEquals(-1, InAppProxy.port)
    }

    /** Minimal loopback HTTP server: replies to any request with one body. */
    private class EchoServer(private val responseBody: String) {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))

        val port: Int get() = server.localPort

        fun start() {
            Thread({
                try {
                    server.accept().use { client ->
                        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                        }
                        val bytes = responseBody.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${bytes.size}\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Connection: close\r\n\r\n"
                        client.getOutputStream().use { o ->
                            o.write(head.toByteArray(Charsets.UTF_8))
                            o.write(bytes)
                            o.flush()
                        }
                    }
                } catch (ignored: Exception) {
                }
            }, "echo-server").apply {
                isDaemon = true
                start()
            }
        }

        fun stop() {
            try {
                server.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
