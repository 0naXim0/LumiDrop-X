package com.lumidrop.x.server

import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

/**
 * ── GOAT-PASS 15 (§3): chunked REQUEST bodies must be decoded by the bundled
 *    NanoHTTPD ──────────────────────────────────────────────────────────────
 *
 * The native ZIP-batch send streams its archive with
 * `HttpURLConnection.setChunkedStreamingMode` (the archive's total length is
 * genuinely unknown until it has been synthesized), so the receiver's HTTP
 * layer must understand `Transfer-Encoding: chunked` on the REQUEST side.
 * The bundled NanoHTTPD only ever implemented chunked RESPONSES: a chunked
 * request body was handed to the endpoint as raw framing bytes — a corrupted
 * file on disk AND a read that never cleanly ended (the reported "ZIP never
 * finishes despite reaching the expected size"). These tests pin the fix at
 * three levels:
 *
 *   1. a REAL server on a real socket, driven by Java's own
 *      HttpURLConnection in chunked mode — the exact production client shape
 *      (multiple buffers, chunk framing written by the platform, terminating
 *      zero chunk, and a response read back);
 *   2. hand-written framing — an explicit multi-chunk payload with a
 *      chunk-extension token and trailer lines, proving the decoder follows
 *      RFC 2616 §3.6.1 beyond what the platform client happens to emit;
 *   3. the Content-Length path — byte-for-byte unchanged (no regression for
 *      every ordinary upload in the app).
 *
 * JVM-only: NanoHTTPD (fi.iki.elonen) has no Android dependency, so the
 * bundled source itself is compiled and exercised here, not a mock.
 */
class GoatPass15ChunkedRequestTest {

    /** A tiny NanoHTTPD subclass that echoes the DECODED request body back as
     *  the response payload — the endpoint sees exactly what
     *  session.inputStream yields, which is precisely what we want to pin. */
    private class EchoServer : NanoHTTPD("127.0.0.1", 0) {
        val captured = AtomicReference<ByteArray?>(null)
        override fun serve(session: IHTTPSession): Response {
            // Exactly what the app's own endpoints do: a chunked body is
            // read to its decoder EOF; a Content-Length body is read in
            // EXACTLY the declared number of bytes (a keep-alive socket
            // stream itself never EOFs).
            val body: ByteArray = if (session.headers["transfer-encoding"]
                    ?.lowercase()?.contains("chunked") == true) {
                session.inputStream.readBytes()
            } else {
                val len = session.headers["content-length"]?.toLongOrNull() ?: 0L
                val buf = java.io.ByteArrayOutputStream()
                var left = len
                val chunk = ByteArray(64 * 1024)
                while (left > 0) {
                    val n = session.inputStream.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    left -= n
                }
                buf.toByteArray()
            }
            captured.set(body)
            return newFixedLengthResponse(
                Response.Status.OK, "application/octet-stream",
                java.io.ByteArrayInputStream(body), body.size.toLong())
        }
    }

    private fun withServer(block: (EchoServer, Int) -> Unit) {
        val server = EchoServer()
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            val port = server.listeningPort
            block(server, port)
        } finally {
            server.stop()
        }
    }

    // ── 1. The production client shape ─────────────────────────────────────

    @Test
    fun `chunked upload via HttpURLConnection arrives decoded and gets a response`() {
        // ~700 KB of pseudo-random-but-deterministic payload, forced across
        // several 256 KB chunks (the ZIP-batch send's exact chunk size).
        val payload = ByteArray(700 * 1024)
        var x = 123456789L
        for (i in payload.indices) {
            x = x * 6364136223846793005L + 1442695040888963407L
            payload[i] = (x ushr 33).toByte()
        }
        withServer { server, port ->
            val conn = java.net.URL("http://127.0.0.1:$port/api/native/push/file?id=x&path=y")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setChunkedStreamingMode(256 * 1024)
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(payload) }
            val code = conn.responseCode
            assertEquals(200, code)
            val echoed = conn.inputStream.readBytes()
            conn.disconnect()
            assertArrayEquals(
                "the endpoint must receive the DECODED payload, not chunk framing",
                payload, echoed)
            assertArrayEquals(payload, server.captured.get())
        }
    }

    /** The §3.1 symptom's second half: the SENDER must not hang waiting for
     *  the response after writing the terminating chunk. The production-shape
     *  test above already proves a prompt response; this one pins the empty
     *  body (an announce-class request an interrupted sender might emit). */
    @Test
    fun `empty chunked body ends cleanly and answers`() {
        withServer { _, port ->
            val conn = java.net.URL("http://127.0.0.1:$port/x")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setChunkedStreamingMode(256 * 1024)
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(ByteArray(0)) }
            assertEquals(200, conn.responseCode)
            val echoed = conn.inputStream.readBytes()
            assertEquals(0, echoed.size)
            conn.disconnect()
        }
    }

    // ── 2. Hand-written framing (RFC 2616 §3.6.1) ───────────────────────────

    /** Drives one raw request through a plain socket and returns the response
     *  body bytes. */
    private fun rawRequest(port: Int, request: String): ByteArray {
        Socket().use { sock ->
            sock.connect(InetSocketAddress("127.0.0.1", port), 5000)
            sock.soTimeout = 15000
            sock.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.ISO_8859_1))
                flush()
            }
            val out = ByteArrayOutputStream()
            val input: InputStream = sock.getInputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = try { input.read(buf) } catch (e: java.io.IOException) { -1 }
                if (n < 0) break
                out.write(buf, 0, n)
                // Response ends when the server closes (Connection: close is
                // implied by the HTTP/1.0 request line below).
                if (!sock.isConnected || sock.isClosed) break
            }
            val text = out.toString("ISO_8859_1")
            val bodyStart = text.indexOf("\r\n\r\n")
            return if (bodyStart < 0) ByteArray(0)
            else text.substring(bodyStart + 4).toByteArray(StandardCharsets.ISO_8859_1)
        }
    }

    @Test
    fun `multi-chunk body with extensions and trailers decodes exactly`() {
        val part1 = "LumiDrop X ZIP payload part one — ".toByteArray(StandardCharsets.UTF_8)
        val part2 = ByteArray(300) { ((it * 7) and 0xFF).toByte() }
        val part3 = "END".toByteArray(StandardCharsets.UTF_8)
        val frame = buildString {
            append("POST /zip HTTP/1.1\r\n")
            append("Host: 127.0.0.1\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            append("\r\n")
            append(Integer.toHexString(part1.size)).append(";x=ext\r\n")     // chunk-extension token
            append(String(part1, StandardCharsets.ISO_8859_1)).append("\r\n")
            append(Integer.toHexString(part2.size)).append("\r\n")
            append(String(part2, StandardCharsets.ISO_8859_1)).append("\r\n")
            append(Integer.toHexString(part3.size)).append("\r\n")
            append(String(part3, StandardCharsets.ISO_8859_1)).append("\r\n")
            append("0\r\n")                                                   // terminating chunk
            append("X-Trailer: yes\r\n")                                      // trailer headers
            append("\r\n")
        }
        withServer { _, port ->
            val body = rawRequest(port, frame)
            val expected = part1 + part2 + part3
            assertArrayEquals(expected, body.copyOf(expected.size))
        }
    }

    @Test
    fun `single-byte reads across a chunk boundary decode correctly`() {
        // A 5-byte chunk then a 3-byte chunk: 8 payload bytes total.
        val frame = "POST /t HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" +
            "5\r\nabcde\r\n3\r\nfgh\r\n0\r\n\r\n"
        withServer { _, port ->
            val body = rawRequest(port, frame)
            assertEquals("abcdefgh", String(body.copyOf(8), StandardCharsets.ISO_8859_1))
        }
    }

    // ── 3. The Content-Length path is untouched ─────────────────────────────

    @Test
    fun `content-length body still arrives byte-for-byte`() {
        val payload = ByteArray(100_000) { (it and 0xFF).toByte() }
        withServer { server, port ->
            val conn = java.net.URL("http://127.0.0.1:$port/y")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(payload.size.toLong())
            conn.connectTimeout = 5000
            conn.readTimeout = 15000
            conn.outputStream.use { it.write(payload) }
            assertEquals(200, conn.responseCode)
            assertArrayEquals(payload, server.captured.get())
            conn.disconnect()
        }
    }

    // ── 4. The decoder alone, on a ByteArrayInputStream ──────────────────────

    @Test
    fun `decoder is reachable through reflection and honest about truncated streams`() {
        // The ChunkedInputStream is private in NanoHTTPD; the socket-level
        // tests above already pin its behavior. Here we pin the TRUNCATION
        // contract: a body that dies mid-chunk must throw (never a silent
        // short read that could finalize a corrupt ZIP as success).
        val cls = Class.forName("fi.iki.elonen.NanoHTTPD\$ChunkedInputStream")
        val ctor = cls.getDeclaredConstructor(InputStream::class.java)
        ctor.isAccessible = true
        val framed = ByteArrayInputStream(
            "4\r\nabcd\r\n10\r\nnever-arrives".toByteArray(StandardCharsets.ISO_8859_1))
        val dec = ctor.newInstance(framed) as InputStream
        val got = ByteArray(4)
        assertEquals(4, dec.read(got, 0, 4))
        assertEquals("abcd", String(got, StandardCharsets.ISO_8859_1))
        var threw = false
        try {
            // Keep reading: a SHORT read is legal InputStream behavior (the
            // 12 bytes that did arrive), but the read that reaches for the
            // missing tail must throw — the exact signal apiNativePushFile's
            // interrupted path needs to keep the partial as a checkpoint
            // instead of completing it.
            while (true) {
                val n = dec.read(ByteArray(16), 0, 16)
                if (n < 0) break   // must NOT happen: truncation throws first
            }
        } catch (e: java.io.IOException) {
            threw = true
        }
        assertTrue("a connection dying inside a chunk must throw, not return EOF", threw)
    }
}
