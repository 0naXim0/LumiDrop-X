package com.lumidrop.x.archive

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ══ GOAT-PASS 13 (PART 2.4) — the binary AndroidManifest reader ═════════════
 *
 * The single most important capability this pass adds: reading each inner
 * APK's OWN compiled AndroidManifest.xml (binary AXML) WITHOUT the platform
 * package parser. Base/split roles, package identity and versions then come
 * from the bytes Android itself will install — not from filenames, not from
 * size, not from the container's manifest.json.
 *
 * AXML container layout (stable since API 1, unchanged through API 34+):
 *
 *   file header        : type=0x0003, headerSize=8, fileSize
 *   string-pool chunk  : type=0x0001 — every string in the document
 *   resource-map chunk : type=0x0180 — skipped
 *   start/end-namespace: 0x0100/0x0101 — skipped
 *   start/end-element  : 0x0102/0x0103 — the manifest tree
 *
 * Every attribute carries (name, rawValueString?, typedValue) — we keep both
 * readings, so `android:versionCode="482"` (raw string) and a typed INT_DEC
 * both yield 482.
 *
 * Design: the WHOLE manifest is read into one bounded byte array and walked
 * with absolute-offset cursors — no stream-position bookkeeping, no partial
 * reads. Every access is bounds-checked; a hostile or truncated manifest
 * reads as null, never as a crash or a giant allocation. Pure JVM, fully
 * unit-testable against real aapt2 output.
 */
object ApkManifestReader {

    private const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024   // real manifests are KBs

    private const val TYPE_FILE = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_NS = 0x0100
    private const val CHUNK_END_NS = 0x0101
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103

    private const val FLAG_UTF8 = 1 shl 8
    private const val VAL_STRING = 0x03
    private const val VAL_INT_DEC = 0x10
    private const val VAL_INT_HEX = 0x11
    private const val VAL_BOOL = 0x12

    /** What we extracted from one APK's AndroidManifest.xml. */
    data class ApkIdentity(
        val packageName: String?,
        val splitName: String?,          // non-null ⇒ this APK IS a split
        val isFeatureSplit: Boolean,
        val isSplitRequired: Boolean,
        val versionCode: Long?,
        val versionName: String?,
        val appLabel: String?,           // literal label only (a @ref is unreadable here)
    ) {
        /** A base APK declares a package and NO split attribute. */
        val isBase: Boolean get() = splitName == null && packageName != null
    }

    /** Reads the manifest of [entryName] inside [zf]; null when absent or
     *  unparseable. An inner "APK" entry is ITSELF a ZIP (PK\x03\x04) whose
     *  `AndroidManifest.xml` lives inside — the manifest is streamed out of
     *  the NESTED zip without materializing the 51–413 MB APK (bounded to
     *  [MAX_MANIFEST_BYTES] even when a hostile entry lies about it). A
     *  non-ZIP entry is attempted as a bare AXML document (fixture/loose
     *  bytes) — the two forms cover every legitimate layout. */
    fun readFromZip(zf: ZipFile, entryName: String): ApkIdentity? {
        return try {
            val e: ZipEntry = zf.getEntry(entryName) ?: return null
            zf.getInputStream(e).use { readApkOrAxml(it) }
        } catch (e: Exception) { null }
    }

    /** Reads one inner component's stream: nested ZIP (a real APK) or a bare
     *  compiled manifest. Never loads more than [MAX_MANIFEST_BYTES] of any
     *  single entry; never loads the component itself. */
    fun readApkOrAxml(ins: InputStream): ApkIdentity? {
        return try {
            // Sniff: a ZIP/APK begins with PK\x03\x04 (or PK\x05\x06 empty);
            // an AXML document begins with its 0x0003 file-type magic.
            val pb = java.io.PushbackInputStream(ins, 4)
            val head = ByteArray(4)
            var got = 0
            while (got < 4) {
                val r = pb.read(head, got, 4 - got)
                if (r < 0) break
                got += r
            }
            if (got < 4) return null
            pb.unread(head, 0, got)
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
                readManifestFromNestedZip(pb)
            } else {
                readFromStream(pb)
            }
        } catch (e: Exception) { null }
    }

    /** Streams `AndroidManifest.xml` out of the nested ZIP readable through
     *  [pb] (its first 4 PK bytes are pushed back). The walk stops at the
     *  manifest entry — aapt/bundletool/emitting tools place it FIRST in
     *  the archive, so a 413 MB Unity asset APK typically yields its 2 KB
     *  manifest after reading a few KB of local headers. */
    private fun readManifestFromNestedZip(pb: InputStream): ApkIdentity? {
        val zin = java.util.zip.ZipInputStream(pb)
        while (true) {
            val entry = zin.nextEntry ?: break
            if (!entry.isDirectory && entry.name == "AndroidManifest.xml") {
                val manifest = readBounded(zin, MAX_MANIFEST_BYTES) ?: return null
                return parse(manifest)
            }
        }
        return null
    }

    /** Reads at most [cap] bytes; null when the stream exceeds it. */
    private fun readBounded(ins: InputStream, cap: Long): ByteArray? {
        var out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    /** Reads a standalone manifest stream (fixture / raw bytes). */
    fun readFromStream(ins: InputStream): ApkIdentity? {
        return try {
            val buf = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val n = ins.read(chunk)
                if (n < 0) break
                total += n
                if (total > MAX_MANIFEST_BYTES) return null
                buf.write(chunk, 0, n)
            }
            parse(buf.toByteArray())
        } catch (e: Exception) { null }
    }

    // ── the parser ────────────────────────────────────────────────────────────

    private class Cursor(val b: ByteArray) {
        var pos = 0
        fun u8(): Int { if (pos + 1 > b.size) throw Over(); return b[pos].toInt() and 0xFF.also { pos++ } }
        fun u16(): Int {
            if (pos + 2 > b.size) throw Over()
            val v = (b[pos].toInt() and 0xFF) or ((b[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2; return v
        }
        fun u32(): Long {
            if (pos + 4 > b.size) throw Over()
            var v = 0L
            for (k in 3 downTo 0) v = (v shl 8) or (b[pos + k].toLong() and 0xFF)
            pos += 4; return v
        }
        fun skip(n: Int) { pos += n; if (pos > b.size) throw Over() }
    }
    private class Over : Exception()

    /** The string pool, resolved once per document. */
    private class Strings(
        val b: ByteArray,
        val dataStart: Int,              // absolute offset of string data
        val offsets: IntArray,
        val utf8: Boolean,
    ) {
        fun at(idx: Int): String? {
            if (idx < 0 || idx >= offsets.size) return null
            return try { decode(dataStart + offsets[idx]) } catch (e: Over) { null }
        }
        private fun decode(at: Int): String {
            val c = Cursor(b); c.pos = at
            return if (utf8) {
                var charLen = c.u8()
                if (charLen and 0x80 != 0) charLen = ((charLen and 0x7F) shl 8) or c.u8()
                var byteLen = c.u8()
                if (byteLen and 0x80 != 0) byteLen = ((byteLen and 0x7F) shl 8) or c.u8()
                val start = c.pos
                if (start + byteLen > b.size) throw Over()
                String(b, start, byteLen, Charsets.UTF_8)
            } else {
                var units = c.u16()
                if (units and 0x8000 != 0) units = ((units and 0x7FFF) shl 16) or c.u16()
                val start = c.pos
                if (units < 0 || start + units * 2 > b.size) throw Over()
                String(b, start, units * 2, Charsets.UTF_16LE)
            }
        }
    }

    private class Attr(val name: String?, val rawString: String?, val dataType: Int, val data: Long) {
        fun asString(): String? = rawString
        fun asLong(): Long? {
            rawString?.toLongOrNull()?.let { return it }
            return when (dataType) { VAL_INT_DEC, VAL_INT_HEX, VAL_BOOL -> data; else -> null }
        }
        fun asBool(): Boolean? {
            rawString?.let { if (it.equals("true", true)) return true; if (it.equals("false", true)) return false }
            return when (dataType) { VAL_BOOL -> data != 0L; VAL_INT_DEC -> data != 0L; else -> null }
        }
    }

    fun parse(bytes: ByteArray): ApkIdentity? {
        return try {
            if (bytes.size < 8) return null
            val c = Cursor(bytes)
            val fileType = c.u16()
            val headerSize = c.u16()
            val fileSize = c.u32().toInt()
            if (fileType != TYPE_FILE || headerSize != 8) return null
            val limit = if (fileSize in 1..bytes.size) fileSize else bytes.size

            var strings: Strings? = null
            var depth = 0
            var pkg: String? = null; var split: String? = null
            var feature = false; var required = false
            var vCode: Long? = null; var vName: String? = null; var label: String? = null

            while (c.pos + 8 <= limit) {
                val chunkType = c.u16()
                val chunkHeader = c.u16()
                val chunkSize = c.u32().toInt()
                if (chunkSize < chunkHeader || c.pos + chunkSize - 8 > limit) break
                val bodyStart = c.pos
                when (chunkType) {
                    CHUNK_STRING_POOL -> strings = parsePool(c, chunkHeader, chunkSize, bytes)
                    CHUNK_START_ELEMENT -> {
                        // lineNo u32, comment u32, ns u32, name u32
                        c.skip(8); val nsIdx = c.u32().toInt(); val nameIdx = c.u32().toInt()
                        val attrStart = c.u16(); val attrSize = c.u16(); val attrCount = c.u16()
                        c.u16(); c.u16(); c.u16()   // idIndex, classIndex, styleIndex
                        val elName = strings?.at(nameIdx)
                        val attrs = HashMap<String, Attr>(attrCount)
                        var k = 0
                        val attrEnd = bodyStart + chunkSize - 8
                        while (k < attrCount && c.pos + 20 <= attrEnd) {
                            val aNs = c.u32().toInt(); val aName = c.u32().toInt()
                            val raw = c.u32().toInt()
                            c.u16()                       // typed-value size
                            c.u8()                        // res0
                            val dt = c.u8()
                            val dv = c.u32()
                            val nm = strings?.at(aName)
                            if (nm != null) attrs[nm] = Attr(
                                nm, strings?.at(raw), dt, dv)
                            k++
                        }
                        if (elName == "manifest" && depth == 0) {
                            pkg = attrs["package"]?.asString()
                            split = attrs["split"]?.asString()?.takeIf { it.isNotBlank() }
                            feature = attrs["isFeatureSplit"]?.asBool() == true
                            required = attrs["isSplitRequired"]?.asBool() == true
                            vCode = attrs["versionCode"]?.asLong() ?: attrs["android:versionCode"]?.asLong()
                            vName = attrs["versionName"]?.asString() ?: attrs["android:versionName"]?.asString()
                        } else if (elName == "application" && label == null) {
                            label = attrs["label"]?.asString() ?: attrs["android:label"]?.asString()
                        }
                        depth++
                    }
                    CHUNK_END_ELEMENT -> {
                        c.skip(16)   // lineNo, comment, ns, name
                        if (depth > 0) depth--
                    }
                    else -> { /* namespace decls, resource map — no body walk */ }
                }
                // Jump to the next chunk (chunks are byte-aligned; never
                // trust mid-chunk parsers to have consumed exactly).
                c.pos = bodyStart + chunkSize - 8
            }
            if (pkg == null && split == null && vCode == null && vName == null && label == null)
                return null
            ApkIdentity(pkg, split, feature, required, vCode, vName, label?.takeIf { it.isNotBlank() })
        } catch (e: Over) { null } catch (e: Exception) { null }
    }

    /** The string-pool chunk; returns the resolved pool. Layout (verified
     *  against real aapt2 output): chunk header 8 (type/hdrSize/size), pool
     *  body header 20 (counts, flags, data offsets), then the string-index
     *  array, then string data at chunkStart + stringsStart. After reading
     *  the body header c.pos == chunkStart + 28 exactly. */
    private fun parsePool(c: Cursor, headerSize: Int, chunkSize: Int, bytes: ByteArray): Strings? {
        val stringCount = c.u32().toInt()
        val styleCount = c.u32().toInt()
        val flags = c.u32().toInt()
        val stringsStart = c.u32().toInt()
        c.u32()   // stylesStart — unused (fixtures and real manifests carry none)
        if (stringCount < 0 || stringCount > 200_000) return null
        val offsets = IntArray(stringCount)
        var i = 0
        while (i < stringCount) { offsets[i] = c.u32().toInt(); i++ }
        val chunkStart = c.pos - 28 - 4 * stringCount
        return Strings(bytes, chunkStart + stringsStart, offsets, flags and FLAG_UTF8 != 0)
    }
}
