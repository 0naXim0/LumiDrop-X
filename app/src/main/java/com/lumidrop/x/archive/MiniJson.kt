package com.lumidrop.x.archive

/**
 * ══ GOAT-PASS 13 (PART 2.3) — a lenient, dependency-free JSON reader ═══════
 *
 * Distributor XAPK `manifest.json` files are machine-written by several
 * different packagers, and their field shapes drift: string-or-number
 * version codes, single objects where an array was expected, comments in
 * hand-edited files, trailing commas, BOMs. org.json rejects several of
 * these; a compatibility parser must not.
 *
 * This is a minimal recursive-descent reader covering the JSON grammar
 * actually encountered (objects, arrays, strings with escapes, numbers,
 * true/false/null) PLUS deliberate lenience: BOM skip, // and # comments,
 * trailing commas, and single-quoted strings. It produces [MJson] — an
 * immutable tree with typed getters that NEVER throw: a missing or
 * wrong-typed field is simply null/absent, so an archive with absent
 * optional metadata degrades to "unknown", never to a failed inspection.
 *
 * Pure JVM (no Android, no org.json) — the entire XAPK compatibility
 * pipeline is unit-testable on the desktop.
 */

/** One node of the parsed tree. */
sealed class MJson {
    object MNull : MJson()
    data class MBool(val v: Boolean) : MJson()
    data class MNum(val raw: String) : MJson() {
        val long: Long? get() = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()
        val int: Int? get() = long?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }
        val double: Double? get() = raw.toDoubleOrNull()
        override fun toString(): String = raw
    }
    data class MStr(val v: String) : MJson()
    data class MArr(val items: List<MJson>) : MJson()
    data class MObj(val fields: Map<String, MJson>) : MJson() {
        /** The field under [key], case-insensitively — packagers drift
         *  between "name"/"Name", "file"/"File". */
        fun get(key: String): MJson? =
            fields[key] ?: fields.entries.firstOrNull { it.key.equals(key, true) }?.value
    }

    /** All object fields' keys when this is an object (else empty). */
    val keys: Set<String> get() = (this as? MObj)?.fields?.keys ?: emptySet()
}

object MiniJson {

    /** Parses [text]; null when the input is not JSON at all (the caller
     *  reports "invalid manifest" — lenient ≠ accepting garbage). */
    fun parse(text: String?): MJson? {
        if (text.isNullOrBlank()) return null
        var s = text
        if (s.startsWith("﻿")) s = s.substring(1)   // UTF-8 BOM
        val p = Parser(s)
        p.skipWs()
        if (p.atEnd()) return null
        return try {
            val v = p.value()
            p.skipWs()
            if (!p.atEnd()) null else v   // trailing junk → not JSON
        } catch (e: ParseException) { null }
    }

    private class ParseException(msg: String) : Exception(msg)

    private class Parser(val s: String) {
        var i = 0
        fun atEnd() = i >= s.length
        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') { i++; continue }
                // Lenient comments: //… line and #… line (some packagers
                // annotate manifests; /* */ blocks too).
                if (c == '/' && i + 1 < s.length && s[i + 1] == '/') { skipLine(); continue }
                if (c == '#') { skipLine(); continue }
                if (c == '/' && i + 1 < s.length && s[i + 1] == '*') {
                    val close = s.indexOf("*/", i + 2)
                    i = if (close >= 0) close + 2 else s.length
                    continue
                }
                break
            }
        }
        private fun skipLine() { while (i < s.length && s[i] != '\n') i++ }

        fun value(): MJson = when {
            atEnd() -> throw ParseException("unexpected end")
            s[i] == '{' -> obj()
            s[i] == '[' -> arr()
            s[i] == '"' -> MJson.MStr(string('"'))
            s[i] == '\'' -> MJson.MStr(string('\''))
            s.startsWith("true", i) -> { i += 4; MJson.MBool(true) }
            s.startsWith("false", i) -> { i += 5; MJson.MBool(false) }
            s.startsWith("null", i) -> { i += 4; MJson.MNull }
            else -> number()
        }

        fun obj(): MJson.MObj {
            i++ // '{'
            val out = LinkedHashMap<String, MJson>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return MJson.MObj(out) }
            while (true) {
                skipWs()
                if (i >= s.length) throw ParseException("unterminated object")
                if (s[i] == '}') { i++; break }          // trailing comma leniency
                val key = when {
                    s[i] == '"' -> string('"')
                    s[i] == '\'' -> string('\'')
                    else -> bareToken(':')
                }
                skipWs()
                if (i >= s.length || s[i] != ':') throw ParseException("expected ':'")
                i++
                skipWs()
                out[key] = value()
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; break }
                throw ParseException("expected ',' or '}'")
            }
            return MJson.MObj(out)
        }

        fun arr(): MJson.MArr {
            i++ // '['
            val out = ArrayList<MJson>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return MJson.MArr(out) }
            while (true) {
                skipWs()
                if (i >= s.length) throw ParseException("unterminated array")
                if (s[i] == ']') { i++; break }           // trailing comma leniency
                out.add(value())
                skipWs()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; break }
                throw ParseException("expected ',' or ']'")
            }
            return MJson.MArr(out)
        }

        fun string(quote: Char): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("unterminated string")
                val c = s[i]
                when {
                    c == quote -> { i++; return sb.toString() }
                    c == '\\' -> {
                        i++
                        if (i >= s.length) throw ParseException("bad escape")
                        when (val e = s[i]) {
                            '"' -> sb.append('"'); '\'' -> sb.append('\'')
                            '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 >= s.length) throw ParseException("bad \\u")
                                val hex = s.substring(i + 1, i + 5)
                                sb.append(hex.toInt(16).toChar()); i += 4
                            }
                            else -> sb.append(e)
                        }
                        i++
                    }
                    else -> { sb.append(c); i++ }
                }
            }
        }

        fun bareToken(stop: Char): String {
            val start = i
            while (i < s.length && s[i] != stop && s[i] != ',' && s[i] != '}' && s[i] != ']' &&
                s[i] != ' ' && s[i] != '\n' && s[i] != '\r' && s[i] != '\t') i++
            if (i == start) throw ParseException("empty token")
            return s.substring(start, i)
        }

        fun number(): MJson.MNum {
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' ||
                    s[i] == '-' || s[i] == '+')) i++
            if (i == start) throw ParseException("not a value")
            val raw = s.substring(start, i)
            if (raw.toLongOrNull() == null && raw.toDoubleOrNull() == null)
                throw ParseException("bad number")
            return MJson.MNum(raw)
        }
    }
}

// ── Typed accessors — never throw, never invent ──────────────────────────

fun MJson?.str(): String? = (this as? MJson.MStr)?.v?.takeIf { it.isNotBlank() }
fun MJson?.long(): Long? = (this as? MJson.MNum)?.long
fun MJson?.int(): Int? = (this as? MJson.MNum)?.int
fun MJson?.bool(): Boolean? = (this as? MJson.MBool)?.v
/** A number-valued field written as a STRING ("version_code":"482") —
 *  several packagers do exactly this. */
fun MJson?.strLong(): Long? = str()?.toLongOrNull() ?: str()?.toDoubleOrNull()?.toLong()

/** This node as an array; a SINGLE object unwraps to a one-element list
 *  (some manifests list one split as a bare object). */
fun MJson?.arr(): List<MJson> = when (this) {
    is MJson.MArr -> items
    is MJson.MObj -> listOf(this)
    else -> emptyList()
}

fun MJson?.obj(): MJson.MObj? = this as? MJson.MObj
