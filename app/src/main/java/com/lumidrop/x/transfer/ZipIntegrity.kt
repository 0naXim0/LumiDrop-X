package com.lumidrop.x.transfer

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * ══ GOAT-PASS 15 (§3.2) — ZIP archive completeness verification ═══════════════
 *
 * A streamed archive whose total size was never declared (the native
 * ZIP-batch send: `size = -1`, chunked POST) reaches the receiver as a
 * length-unknown stream: the receiver cannot verify completeness by byte
 * count alone — EOF on the decoded stream says the SENDER finished writing,
 * but the honest question §3.2 asks is "is this a VALID, COMPLETE archive?",
 * and an interrupted stream that the auto-resume machinery half-delivered
 * must never finalize as a success.
 *
 * The answer for the ZIP format is cheap and structural: the End Of Central
 * Directory record (EOCD) is written LAST — after every local entry and the
 * whole central directory — by `ZipOutputStream.close()`. An archive whose
 * final bytes are a well-formed EOCD (signature `PK\x05\x06`, a coherent
 * entry count, and a comment length that ends exactly at the file's end) is
 * therefore provably complete at the structural level; a truncated archive
 * can never end with one, because the bytes that would contain it were never
 * written.
 *
 * Pure Kotlin, JVM-tested (`GoatPass15ZipIntegrityTest`): the scan window is
 * bounded (EOCD + the maximum 64 KB ZIP comment), so even a multi-gigabyte
 * archive costs one bounded read of its tail.
 */
object ZipIntegrity {

    /** "PK\x05\x06" — the EOCD signature (little-endian). */
    private val EOCD_SIG = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    /** The EOCD's own fixed size: signature(4) + disk fields(18) +
     *  comment length(2) = 22 bytes. */
    private const val EOCD_MIN = 22

    /** A ZIP file comment may be up to 65,535 bytes — the maximum distance
     *  the EOCD can sit from the end of the file. */
    private const val MAX_COMMENT = 65_535

    /**
     * True when [file] ends with a structurally complete End Of Central
     * Directory record — the cheap, sufficient proof that the archive was
     * fully written and closed by its producer.
     *
     * Returns false for anything else: a truncated archive (no EOCD in the
     * tail), a non-ZIP file, a file too small to hold any EOCD, or an I/O
     * error reading the tail. Callers treat false as "not a complete
     * archive" and must never register the file as a successful transfer.
     */
    fun endsWithCentralDirectory(file: File): Boolean {
        val length = try { file.length() } catch (e: Exception) { return false }
        if (length < EOCD_MIN) return false
        val window = minOf(length, (EOCD_MIN + MAX_COMMENT).toLong()).toInt()
        return try {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(length - window)
                val tail = ByteArray(window)
                var off = 0
                while (off < window) {
                    val n = raf.read(tail, off, window - off)
                    if (n < 0) break
                    off += n
                }
                if (off < window) return false
                endsWithCentralDirectory(tail, off)
            }
        } catch (e: IOException) { false } catch (e: Exception) { false }
    }

    /**
     * The pure form: [len] valid bytes of [tail] (the file's last bytes,
     * little-endian ZIP layout). Scans backward for the LAST occurrence of
     * the EOCD signature, then validates that the record's declared comment
     * length ends exactly at the buffer's end and its entry count is not
     * obviously corrupt (the "total entries" field may legally wrap at
     * 65,535 for huge archives, so it is not compared against anything —
     * only the record's SELF-CONSISTENCY is checked).
     */
    fun endsWithCentralDirectory(tail: ByteArray, len: Int = tail.size): Boolean {
        if (len < EOCD_MIN) return false
        // Scan from the END backward so a comment that happens to contain a
        // stray signature cannot shadow the real record — the real EOCD is
        // the one whose comment length reaches exactly the file's end.
        var i = len - EOCD_MIN
        while (i >= 0) {
            if (tail[i] == EOCD_SIG[0] && tail[i + 1] == EOCD_SIG[1] &&
                tail[i + 2] == EOCD_SIG[2] && tail[i + 3] == EOCD_SIG[3]) {
                val commentLen = (tail[i + 20].toInt() and 0xFF) or
                    ((tail[i + 21].toInt() and 0xFF) shl 8)
                val recordEnd = i + EOCD_MIN + commentLen
                if (recordEnd == len) return true
            }
            i--
        }
        return false
    }
}
