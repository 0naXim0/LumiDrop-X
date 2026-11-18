package com.lumidrop.x.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ── GOAT-PASS 15 (§3.2): no incomplete or corrupted ZIP may be reported as a
 *    successful transfer ─────────────────────────────────────────────────────
 *
 * The receiver of an unknown-length (chunked) ZIP stream cannot verify the
 * archive by byte count — the §3.1 symptom was exactly a stream whose bytes
 * all arrived without the transfer ever finalizing. The fix has two halves:
 * the chunked decoder (pinned by GoatPass15ChunkedRequestTest) makes the
 * read END correctly, and THIS gate makes the FINAL verdict honest — the
 * completed staging file must end with a structurally valid End Of Central
 * Directory record before it is renamed to its destination and registered.
 * A truncated archive (the auto-resume machinery re-POSTing from byte 0
 * after a mid-stream drop, a sender that died mid-write) can never end with
 * an EOCD — ZipOutputStream writes it last — so it resolves as an honest
 * failure, never a "successful" corrupt file.
 */
class GoatPass15ZipIntegrityTest {

    private fun realZip(entries: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
            repeat(entries) { i ->
                zos.putNextEntry(ZipEntry("dir/file$i.txt"))
                zos.write("content number $i".toByteArray())
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a genuine closed archive passes`() {
        val zip = realZip(30)
        val f = File.createTempFile("gp15", ".zip").apply {
            deleteOnExit(); writeBytes(zip)
        }
        assertTrue(ZipIntegrity.endsWithCentralDirectory(f))
    }

    @Test
    fun `a truncated archive fails at every cut`() {
        val zip = realZip(27)
        // Cut at several points: inside the first entry, inside the central
        // directory, and exactly one byte short of the EOCD's end.
        for (cut in intArrayOf(64, zip.size / 3, zip.size - EOCD_LEN - 500, zip.size - 23, zip.size - 1)) {
            if (cut <= 0 || cut >= zip.size) continue
            val f = File.createTempFile("gp15", ".zip").apply {
                deleteOnExit(); writeBytes(zip.copyOf(cut))
            }
            assertFalse("a truncation at $cut bytes must not pass as a complete archive",
                ZipIntegrity.endsWithCentralDirectory(f))
        }
    }

    @Test
    fun `an EOCD record with a comment passes when the comment length is honest`() {
        // A real archive plus a 10-byte ZIP comment appended via the EOCD's
        // own comment field (what ZipOutputStream.setComment produces).
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.setComment("0123456789")
            zos.putNextEntry(ZipEntry("a.txt"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        val f = File.createTempFile("gp15", ".zip").apply {
            deleteOnExit(); writeBytes(out.toByteArray())
        }
        assertTrue(ZipIntegrity.endsWithCentralDirectory(f))
    }

    @Test
    fun `a stray signature inside a comment cannot fake completeness`() {
        val zip = realZip(2)
        // Craft a tail: [real zip without its EOCD] + [fake EOCD with a
        // comment length that does NOT reach the end] — the scanner must
        // reject it because no record's comment ends exactly at EOF.
        val eocdAt = indexOfEocd(zip)
        val head = zip.copyOf(eocdAt)   // everything before the real EOCD
        val fake = ByteArray(EOCD_LEN)
        fake[0] = 0x50; fake[1] = 0x4B; fake[2] = 0x05; fake[3] = 0x06
        fake[21] = 5   // claims a 5-byte comment that is not actually there
        val f = File.createTempFile("gp15", ".zip").apply {
            deleteOnExit(); writeBytes(head + fake)
        }
        assertFalse(ZipIntegrity.endsWithCentralDirectory(f))
    }

    @Test
    fun `non-zip and tiny files fail`() {
        val notZip = File.createTempFile("gp15", ".bin").apply {
            deleteOnExit(); writeBytes("just some text, definitely not an archive".toByteArray())
        }
        assertFalse(ZipIntegrity.endsWithCentralDirectory(notZip))
        val empty = File.createTempFile("gp15", ".zip").apply { deleteOnExit() }
        assertFalse(ZipIntegrity.endsWithCentralDirectory(empty))
    }

    private val EOCD_LEN = 22

    private fun indexOfEocd(zip: ByteArray): Int {
        var i = zip.size - EOCD_LEN
        while (i >= 0) {
            if (zip[i] == 0x50.toByte() && zip[i + 1] == 0x4B.toByte() &&
                zip[i + 2] == 0x05.toByte() && zip[i + 3] == 0x06.toByte()) return i
            i--
        }
        throw AssertionError("test fixture has no EOCD")
    }
}
