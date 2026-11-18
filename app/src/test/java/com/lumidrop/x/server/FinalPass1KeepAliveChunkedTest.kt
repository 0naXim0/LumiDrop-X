package com.lumidrop.x.server

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ══ FINAL PASS 1 (§3) — a keep-alive connection's SECOND body-bearing request ══
 *
 * The reported defect: a native-to-native ZIP transfer streams every byte
 * (~21.6 MB), then sits in "loading" forever — never Completed, on either
 * side.
 *
 * Root cause: ONE HTTPSession object serves EVERY request that rides a
 * keep-alive connection (DefaultServerRunnable.run's execute() loop), but
 * the lazily-built body stream was cached across those requests. A second
 * body-bearing request on the same pooled connection received the FIRST
 * request's stream:
 *   • after a chunked POST — a stream already at its decoder EOF, so the
 *     receiver "completed" an EMPTY body (for an unknown-length archive,
 *     completed=true with 0 bytes → the EOCD gate refuses → the sender
 *     retries → forever);
 *   • after a Content-Length POST — the RAW socket stream with the chunk
 *     FRAMING still in it, so the receiver consumed framing bytes as
 *     payload and the read only ever "ended" when the connection died.
 * HttpURLConnection POOLS connections, so the production sender hits this
 * whenever its file POST lands on a socket that already served a body —
 * exactly the recurring, connection-history-dependent stall.
 *
 * The fix resets the per-request body stream at the top of execute(). These
 * tests pin, against the REAL bundled server on real sockets:
 *
 *  1. two consecutive CHUNKED POSTs over one pooled connection both decode
 *     byte-for-byte (the eof:0 poison is gone);
 *  2. a fixed-length POST followed by a CHUNKED POST over one pooled
 *     connection both decode (the raw-framing poison is gone);
 *  3. a chunked POST that stalls mid-body longer than the server's own
 *     SOCKET_READ_TIMEOUT survives when the endpoint raises the
 *     connection's read window (extendSocketReadTimeout — the slow
 *     live-archive sender), while the extension API itself is a safe no-op
 *     for a session without a socket;
 *  4. a Content-Length body still arrives byte-for-byte (no regression).
 */
class FinalPass1KeepAliveChunkedTest {

    /** The app's endpoint shape: chunked bodies read to decoder EOF;
     *  Content-Length bodies read to EXACTLY the declared count (a
     *  keep-alive socket stream itself never EOFs). Raises the read window
     *  like apiNativePushFile/apiUpload do. */
    private class TransferShapeServer : NanoHTTPD("127.0.0.1", 0) {
        val bodies = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        val readTimeoutsRaised = AtomicInteger(0)
        val lastError = AtomicReference<String?>(null)
        override fun serve(session: IHTTPSession): Response {
            try {
                session.extendSocketReadTimeout(45_000)
                readTimeoutsRaised.incrementAndGet()
                val chunked = session.headers["transfer-encoding"]
                    ?.lowercase()?.contains("chunked") == true
                val body: ByteArray = if (chunked) {
                    session.inputStream.readBytes()
                } else {
                    val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
                    val out = ByteArrayOutputStream()
                    var left = len
                    val buf = ByteArray(64 * 1024)
                    while (left > 0) {
                        val n = session.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(buf, 0, n)
                        left -= n
                    }
                    out.toByteArray()
                }
                bodies.add(body)
                return newFixedLengthResponse(Response.Status.OK, "application/json",
                    "{\"ok\":true,\"n\":${body.size}}")
            } catch (e: Exception) {
                lastError.set("${e.javaClass.simpleName}: ${e.message}")
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "err")
            }
        }
    }

    private fun withServer(block: (TransferShapeServer, Int) -> Unit) {
        val server = TransferShapeServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            // Wait for bind.
            var waited = 0
            while (server.listeningPort < 0 && waited < 5000) {
                Thread.sleep(25); waited += 25
            }
            assertTrue("server did not bind", server.listeningPort > 0)
            block(server, server.listeningPort)
        } finally {
            server.stop()
        }
    }

    /** One POST over the (pooled) connection; returns (httpCode, body). */
    private fun post(port: Int, payload: ByteArray, chunked: Boolean): Pair<Int, ByteArray> {
        val conn = URL("http://127.0.0.1:$port/api/native/push/file?id=x&path=z&size=-1")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5_000
        conn.readTimeout = 60_000
        if (chunked) conn.setChunkedStreamingMode(256 * 1024)
        else conn.setFixedLengthStreamingMode(payload.size.toLong())
        conn.outputStream.use { it.write(payload) }
        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
            ?: ByteArray(0)
        return code to body
    }

    private fun payload(n: Int): ByteArray {
        val b = ByteArray(n); Random(42).nextBytes(b); return b
    }

    /** Drives TWO sequential requests over ONE raw socket (one keep-alive
     *  connection = one HTTPSession object = exactly the production reuse),
     *  reading each response fully before sending the next request. */
    private fun twoRequestsOneSocket(
        port: Int,
        req1: String, body1: ByteArray,
        req2: String, body2: ByteArray,
    ): Pair<String, String> {
        java.net.Socket("127.0.0.1", port).use { sock ->
            sock.soTimeout = 30_000
            sock.getOutputStream().use { out ->
                sock.getInputStream().use { inp ->
                    fun requestOnce(head: String, body: ByteArray): String {
                        out.write(head.toByteArray(Charsets.ISO_8859_1))
                        out.write(body)
                        out.flush()
                        // Read the full response BYTE-WISE (no BufferedReader:
                        // it would buffer past the headers into the next
                        // request's bytes): status line, headers, then exactly
                        // Content-Length body bytes.
                        fun readLineRaw(): String {
                            val sb = StringBuilder()
                            while (true) {
                                val c = inp.read()
                                if (c < 0) throw IllegalStateException("eof in headers")
                                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                                sb.append(c.toChar())
                            }
                        }
                        val status = readLineRaw()
                        var len = 0
                        while (true) {
                            val line = readLineRaw()
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true))
                                len = line.substringAfter(':').trim().toInt()
                        }
                        val buf = ByteArray(len)
                        var off = 0
                        while (off < len) {
                            val n = inp.read(buf, off, len - off)
                            if (n < 0) break
                            off += n
                        }
                        return status + " body=" + String(buf, Charsets.ISO_8859_1)
                    }
                    val r1 = requestOnce(req1, body1)
                    val r2 = requestOnce(req2, body2)
                    return r1 to r2
                }
            }
        }
    }

    /** Hand-builds a chunked request head for [nChunks] data chunks of the
     *  payload, plus the terminating zero chunk, as one body byte blob. */
    private fun chunkedBody(payload: ByteArray, chunkSize: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var off = 0
        while (off < payload.size) {
            val len = minOf(chunkSize, payload.size - off)
            out.write(("%x\r\n".format(len)).toByteArray(Charsets.ISO_8859_1))
            out.write(payload, off, len)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
            off += len
        }
        out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun `second chunked request on one keep-alive connection decodes fully`() {
        withServer { server, port ->
            val p1 = payload(5 * 256 * 1024 + 7)
            val p2 = payload(5 * 256 * 1024 + 13)
            val head = "POST /api/native/push/file?id=x&path=z&size=-1 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\nTransfer-Encoding: chunked\r\n\r\n"
            val (r1, r2) = twoRequestsOneSocket(
                port, head, chunkedBody(p1, 256 * 1024),
                head, chunkedBody(p2, 256 * 1024))
            assertTrue("request 1 answered 200: $r1", r1.startsWith("HTTP/1.1 200"))
            assertTrue("request 2 answered 200 (was the eof:0 poison): $r2", r2.startsWith("HTTP/1.1 200"))
            assertArrayEquals(p1, server.bodies.poll())
            assertArrayEquals(p2, server.bodies.poll())
            assertEquals(null, server.lastError.get())
        }
    }

    @Test
    fun `chunked request after a fixed-length request on one connection decodes fully`() {
        withServer { server, port ->
            val p1 = payload(3 * 256 * 1024 + 5)
            val p2 = payload(3 * 256 * 1024 + 11)
            val head1 = "POST /api/native/push/file?id=x&path=a&size=-1 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\nContent-Length: ${p1.size}\r\n\r\n"
            val head2 = "POST /api/native/push/file?id=x&path=z&size=-1 HTTP/1.1\r\n" +
                "Host: 127.0.0.1:$port\r\nTransfer-Encoding: chunked\r\n\r\n"
            val (r1, r2) = twoRequestsOneSocket(
                port, head1, p1,
                head2, chunkedBody(p2, 256 * 1024))
            assertTrue("fixed-length request answered 200: $r1", r1.startsWith("HTTP/1.1 200"))
            assertTrue("chunked request answered 200 (was the raw-framing poison): $r2", r2.startsWith("HTTP/1.1 200"))
            assertArrayEquals(p1, server.bodies.poll())
            assertArrayEquals(p2, server.bodies.poll())
            assertEquals(null, server.lastError.get())
        }
    }

    @Test
    fun `content-length body is byte-for-byte unchanged`() {
        withServer { server, port ->
            val p = payload(2 * 256 * 1024 + 12345)
            val (c, _) = post(port, p, chunked = false)
            assertEquals(200, c)
            assertArrayEquals(p, server.bodies.poll())
        }
    }

    @Test
    fun `slow live sender survives past the server-wide socket timeout`() {
        withServer { server, port ->
            // 21.6 MB in 256 KB chunks with a >SOCKET_READ_TIMEOUT (5 s)
            // pause 2/3 in — the live-archive-generator shape (a slow
            // ContentResolver open). With the endpoint's raised read window
            // the receive must complete with every byte.
            val total = 21_600_000
            val conn = URL("http://127.0.0.1:$port/api/native/push/file?id=x&path=z&size=-1")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.doOutput = true
            conn.connectTimeout = 5_000; conn.readTimeout = 60_000
            conn.setChunkedStreamingMode(256 * 1024)
            val chunk = payload(256 * 1024)
            conn.outputStream.use { out ->
                var written = 0
                var stalled = false
                while (written < total) {
                    if (!stalled && written >= total * 2 / 3) {
                        Thread.sleep(7_000)   // > SOCKET_READ_TIMEOUT, < window
                        stalled = true
                    }
                    val len = minOf(chunk.size.toLong(), (total - written).toLong()).toInt()
                    out.write(chunk, 0, len)
                    written += len
                }
            }
            assertEquals(200, conn.responseCode)
            assertEquals(total, server.bodies.poll()?.size)
            assertEquals(null, server.lastError.get())
        }
    }
}
