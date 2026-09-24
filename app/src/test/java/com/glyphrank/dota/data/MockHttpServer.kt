package com.glyphrank.dota.data

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Tiny HTTP/1.1 mock server for unit tests, on a random local port. Only uses java.net,
 * which (unlike the JDK's com.sun.net.httpserver) is on the Android unit-test classpath.
 * One request per connection; every response closes the connection.
 */
class MockHttpServer : Closeable {
    data class Request(val path: String, val headers: Map<String, String>)

    data class Response(
        val code: Int,
        val body: String,
        val contentType: String = "application/json; charset=utf-8",
        val delayMs: Long = 0,
        val headers: Map<String, String> = emptyMap(),
    )

    private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val pool = Executors.newCachedThreadPool()

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"
    val requests = CopyOnWriteArrayList<Request>()

    /** Decides the response; header names are lower-case. Default: 404 like OpenDota's. */
    @Volatile var handler: (Request) -> Response = { Response(404, """{"error":"Not Found"}""") }

    init {
        pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                pool.execute { client.use(::serve) }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            val input = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val requestLine = input.readLine() ?: return
            val headers = generateSequence { input.readLine() }
                .takeWhile { it.isNotEmpty() }
                .filter { ':' in it }
                .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
            val request = Request(requestLine.split(' ')[1], headers)
            requests += request

            val response = handler(request)
            if (response.delayMs > 0) Thread.sleep(response.delayMs)
            val body = response.body.toByteArray(Charsets.UTF_8)
            val head = "HTTP/1.1 ${response.code} Mock\r\n" +
                "Content-Type: ${response.contentType}\r\n" +
                "Content-Length: ${body.size}\r\n" +
                response.headers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } +
                "Connection: close\r\n\r\n"
            client.getOutputStream().apply {
                write(head.toByteArray(Charsets.ISO_8859_1))
                write(body)
                flush()
            }
        } catch (e: IOException) {
            // client gave up (e.g. timeout test)
        } catch (e: InterruptedException) {
            // server closed while delaying
        }
    }

    override fun close() {
        socket.close()
        pool.shutdownNow()
    }
}
