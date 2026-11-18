package com.lumidrop.x.transfer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * ══ FINAL PASS 1 (§3) — the native-to-native ZIP round trip, end to end ══
 *
 * The reported defect: ~21.6 MB of images sent as one ZIP from one native
 * client to another stalls at the final byte count forever — the receiver's
 * bar sits at 100%-looking while nothing ever Completes.
 *
 * Root causes fixed by this pass (see FinalPass1KeepAliveChunkedTest for the
 * wire-level pins): (a) a keep-alive connection's SECOND body-bearing
 * request inherited the first request's body stream — an eof:0 or raw-framed
 * read that could never finalize; (b) the receiver's 5 s socket read timeout
 * aborted legitimately slow live-archive senders mid-stream.
 *
 * THIS test closes the loop at the ARCHIVE level, exactly the §3.5 mandate
 * ("after each successful transfer, actually inspect or extract the
 * resulting archive"): a real ZipOutputStream-synthesized archive (the exact
 * shape FileTreeNode.ZipBatchNode streams — NO_COMPRESSION, multiple file
 * entries, nested directory entries, a text entry, ~21 MB) is POSTed with
 * the production client's chunked mode through the REAL bundled server
 * (patched), persisted to disk by the endpoint, and then:
 *
 *   1. the delivered file passes ZipIntegrity.endsWithCentralDirectory —
 *      the same structural gate apiNativePushFile applies before registering
 *      a received archive;
 *   2. the archive OPENS as a real ZipFile and every entry's bytes are
 *      identical to what was synthesized — the archive is genuinely
 *      extractable, not just "complete-looking";
 *   3. the byte count on the wire equals the archive's exact length.
 *
 * The receiver endpoint here is the same shape as writeRawStream's unknown-
 * length branch (read to decoder EOF, extend the read window first).
 */
class FinalPass1ZipRoundTripTest {

    private class ArchiveSink : fi.iki.elonen.NanoHTTPD("127.0.0.1", 0) {
        val file = File.createTempFile("ldx-finalpass1-zip", ".zip")
        var served = false
        override fun serve(session: fi.iki.elonen.NanoHTTPD.IHTTPSession):
            fi.iki.elonen.NanoHTTPD.Response {
            session.extendSocketReadTimeout(45_000)
            // The unknown-length branch: read the decoded stream to EOF.
            FileOutputStream(file).use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = session.inputStream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
            served = true
            return newFixedLengthResponse(fi.iki.elonen.NanoHTTPD.Response.Status.OK,
                "application/json", "{\"success\":true}")
        }
    }

    /** The archive members: name → bytes (deterministic pseudo-random). */
    private fun members(): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var x = 987654321L
        fun bytes(n: Int): ByteArray {
            val b = ByteArray(n)
            for (i in b.indices) { x = x * 6364136223846793005L + 1442695040888963407L; b[i] = (x ushr 33).toByte() }
            return b
        }
        out += "message.txt" to "hello from the batch".toByteArray(Charsets.UTF_8)
        out += "Photos/" to ByteArray(0)                     // a directory entry
        for (i in 1..8) out += "Photos/IMG_%02d.jpg".format(i) to bytes(2_600_000)   // ~20.8 MB
        out += "Docs/report.pdf" to bytes(500_000)
        return out
    }

    private fun synthesize(members: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            for ((name, data) in members) {
                zos.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `streamed archive arrives finalized, valid and extractable`() {
        val members = members()
        val archive = synthesize(members)
        assertTrue("test archive is a realistic ~21 MB batch",
            archive.size in 20_000_000..25_000_000)

        val server = ArchiveSink()
        server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        try {
            var waited = 0
            while (server.listeningPort < 0 && waited < 5000) { Thread.sleep(25); waited += 25 }
            assertTrue("server did not bind", server.listeningPort > 0)

            // The production sender's exact shape: HttpURLConnection,
            // chunked 256 KB, streamed from an in-memory source.
            val conn = URL("http://127.0.0.1:${server.listeningPort}" +
                "/api/native/push/file?id=x&path=Batch%20photos.zip&size=-1&offset=0")
                .openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 60_000
            conn.setChunkedStreamingMode(256 * 1024)
            conn.outputStream.use { it.write(archive) }
            assertEquals("the receiver answered success", 200, conn.responseCode)

            // (1) the structural gate the real endpoint applies
            assertTrue("the received file ends with a valid EOCD (ZipIntegrity)",
                ZipIntegrity.endsWithCentralDirectory(server.file))

            // (2) the archive genuinely opens and every member is byte-identical
            ZipFile(server.file).use { zf ->
                val byName = members.associate { it.first to it.second }
                for ((name, expected) in byName) {
                    val entry = zf.getEntry(name)
                    assertTrue("entry present: $name", entry != null)
                    if (name.endsWith("/")) continue
                    val got = zf.getInputStream(entry).use { it.readBytes() }
                    assertArrayEquals("entry bytes identical: $name", expected, got)
                }
                assertEquals("no extra entries smuggled in",
                    byName.size, zf.size())
            }
            assertEquals("exact byte count delivered", archive.size.toLong(), server.file.length())
            assertTrue(server.served)
        } finally {
            server.stop()
            server.file.delete()
        }
    }
}
