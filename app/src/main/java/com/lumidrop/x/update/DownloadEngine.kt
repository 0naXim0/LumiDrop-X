package com.lumidrop.x.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * ══ PRODUCTION FINAL PASS 23 (§3) — the streamed, resumable, 416-recovering ─
 * download engine, deliberately free of every Android type so the WHOLE
 * matrix (HTTP 200/206/416/429/5xx, mid-body resets, premature EOF,
 * cancellation, restart-boundedness, partial preservation) runs as plain
 * JVM tests against fake connections (Pass23UpdaterReconstructionTest).
 *
 * Guarantees this engine is contracted to uphold:
 *  1. A partial is resumed ONLY from the file's REAL length on disk — a
 *     previously saved offset is never trusted (§3.A).
 *  2. A response body is appended ONLY when the server confirmed it is the
 *     exact continuation (206 + Content-Range starting at the requested
 *     byte) — a 200 over a partial truncates and restarts clean (§3.B).
 *  3. HTTP 416 is interrogated, not feared: the Content-Range total decides
 *     between "the file is already complete" (no network body at all) and
 *     "discard only the invalid bytes and restart" — bounded, never
 *     re-issuing the same refused range (§3.C).
 *  4. Transient failures retry with capped exponential backoff; a dead
 *     radio (DNS) fails immediately without hammering; cancellation is
 *     checked per chunk and per backoff step (§3.D).
 *  5. A network failure PRESERVES the partial for the next attempt; only
 *     provably invalid data (wrong size, empty) is ever deleted (§9).
 *  6. Every exit is terminal: Completed / Cancelled / Failed — never a
 *     stuck DOWNLOADING (Pass-21 §1.2 discipline, now structural).
 *
 * All decisions come from UpdatePolicy; this object is pure control flow
 * over an injectable connection factory. The APK is streamed to disk in
 * 64 KB chunks — it is never held in memory.
 */
object DownloadEngine {

    /** Minimal connection surface — small enough to fake on the JVM. */
    interface Connection {
        val code: Int
        val contentLength: Long          // THIS response's body length
        fun header(name: String): String?
        fun body(): InputStream
        fun close()
    }

    sealed class Outcome {
        /** Every expected byte is on disk — hand the file to verification. */
        object Completed : Outcome()
        /** The user cancelled — the partial was deleted, nothing preserved. */
        object Cancelled : Outcome()
        /** Honest failure — [partialPreserved] tells whether resumable bytes
         *  survived on disk (they are only ever dropped when invalid). */
        data class Failed(val message: String, val partialPreserved: Boolean) : Outcome()
    }

    /** Injectable backoff so tests never sleep. */
    fun interface Sleeper { fun sleep(ms: Long, cancelled: () -> Boolean) }

    private val realSleeper = Sleeper { ms, cancelled ->
        var remaining = ms
        while (remaining > 0) {
            if (cancelled()) return@Sleeper
            val step = minOf(remaining, 200L)
            try { Thread.sleep(step) } catch (e: InterruptedException) { return@Sleeper }
            remaining -= step
        }
    }

    /**
     * Runs the complete download lifecycle for one release asset into
     * [dest]. [open] receives the requested resume offset (always the file's
     * actual length); [onProgress] fires on every chunk with (downloaded,
     * total) — 0 total means unknown size.
     */
    fun download(
        url: String,
        dest: File,
        expectedSize: Long,
        cancelled: () -> Boolean,
        open: (url: String, fromByte: Long) -> Connection,
        onProgress: (downloaded: Long, total: Long) -> Unit,
        sleeper: Sleeper = realSleeper
    ): Outcome {
        var networkAttempts = 0
        var recoveries = 0
        while (true) {
            if (cancelled()) { dest.delete(); return Outcome.Cancelled }
            // §3.A: the resume offset is the file's ACTUAL length, re-read
            // every round — never a previously saved offset.
            val fromByte = if (dest.isFile) dest.length() else 0L
            networkAttempts++
            var conn: Connection? = null
            try {
                conn = open(url, fromByte)
                val code = conn.code

                if (code == 416) {
                    // §3.C: interrogate instead of failing.
                    val serverTotal = UpdatePolicy.contentRangeTotal(conn.header("Content-Range"))
                    when (UpdatePolicy.rangeMismatchAction(fromByte, serverTotal,
                            expectedSize, recoveries)) {
                        UpdatePolicy.RangeMismatchAction.FILE_ALREADY_COMPLETE -> {
                            conn.close()
                            onProgress(dest.length(), serverTotal ?: dest.length())
                            return Outcome.Completed      // zero network body read
                        }
                        UpdatePolicy.RangeMismatchAction.RESTART_CLEAN -> {
                            conn.close()
                            dest.delete()                 // discard ONLY the invalid bytes
                            recoveries++
                            continue
                        }
                        UpdatePolicy.RangeMismatchAction.FAIL_HONEST -> {
                            conn.close()
                            return Outcome.Failed(
                                "The download could not recover from HTTP 416. It will restart from the beginning on your next try.",
                                dest.isFile && dest.length() > 0)
                        }
                    }
                }

                if (UpdatePolicy.isRetryableHttp(code)) {          // 429 / 5xx
                    conn.close()
                    if (cancelled()) { dest.delete(); return Outcome.Cancelled }
                    if (networkAttempts >= UpdatePolicy.MAX_NETWORK_ATTEMPTS) {
                        return Outcome.Failed("The server returned HTTP $code. ${partialNote(dest)}",
                            dest.isFile && dest.length() > 0)
                    }
                    sleeper.sleep(UpdatePolicy.retryDelayMs(networkAttempts), cancelled)
                    continue    // fromByte recomputed from the real file length
                }

                if (code !in 200..299) {                            // other 4xx: final
                    conn.close()
                    return Outcome.Failed("The server returned HTTP $code.",
                        dest.isFile && dest.length() > 0)
                }

                val rangeHeader = conn.header("Content-Range")
                val appending = code == 206 && UpdatePolicy.rangeHonoredAt(rangeHeader, fromByte)
                if (code == 206 && fromByte > 0 && !appending) {
                    // 206 at an unexpected offset — appending would corrupt
                    // the file. Clean restart, bounded like a 416 recovery.
                    conn.close()
                    if (recoveries >= UpdatePolicy.MAX_RECOVERY_ATTEMPTS) {
                        return Outcome.Failed("The server sent an unexpected partial response.",
                            dest.isFile && dest.length() > 0)
                    }
                    dest.delete()
                    recoveries++
                    continue
                }
                if (fromByte > 0 && !appending) {
                    // 200 full response over a stale partial — never append.
                    dest.delete()
                }

                val startAt = if (appending) fromByte else 0L
                val bodyLen = conn.contentLength
                val serverTotal = UpdatePolicy.contentRangeTotal(rangeHeader)
                    ?: if (bodyLen > 0) startAt + bodyLen else null
                val total = if (bodyLen > 0) startAt + bodyLen
                    else serverTotal ?: expectedSize
                var done = startAt
                onProgress(done, total)
                val input: InputStream = conn.body()
                FileOutputStream(dest, appending).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled()) { out.flush(); dest.delete(); return Outcome.Cancelled }
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    out.flush()
                }
                conn.close()

                // Clean EOF — the size verdict decides completion (§3.E).
                when (UpdatePolicy.eofVerdict(dest.length(), expectedSize, serverTotal)) {
                    UpdatePolicy.EofVerdict.COMPLETE -> return Outcome.Completed
                    UpdatePolicy.EofVerdict.INCOMPLETE -> {
                        if (cancelled()) { dest.delete(); return Outcome.Cancelled }
                        if (networkAttempts >= UpdatePolicy.MAX_NETWORK_ATTEMPTS) {
                            return Outcome.Failed(
                                "The download was interrupted (got ${dest.length()} of " +
                                    "${serverTotal ?: expectedSize} bytes). Your partial download has been preserved.",
                                true)
                        }
                        sleeper.sleep(UpdatePolicy.retryDelayMs(networkAttempts), cancelled)
                        continue    // resumes from dest.length()
                    }
                    UpdatePolicy.EofVerdict.INVALID -> {
                        dest.delete()
                        return Outcome.Failed(
                            "The downloaded file does not match the release's published size.", false)
                    }
                }
            } catch (e: Exception) {
                try { conn?.close() } catch (e2: Exception) { }
                if (cancelled()) { dest.delete(); return Outcome.Cancelled }
                if (UpdatePolicy.shouldAutoRetry(errorKindOf(e), networkAttempts)) {
                    sleeper.sleep(UpdatePolicy.retryDelayMs(networkAttempts), cancelled)
                    continue    // partial preserved; resumes from real length
                }
                return Outcome.Failed(describeNetworkError(e) + " " + partialNote(dest),
                    dest.isFile && dest.length() > 0)
            }
        }
    }

    private fun partialNote(dest: File): String =
        if (dest.isFile && dest.length() > 0) "Your partial download has been preserved." else ""

    private fun errorKindOf(e: Exception): UpdatePolicy.ErrorKind = when (e) {
        is UnknownHostException -> UpdatePolicy.ErrorKind.NO_NETWORK
        is SocketTimeoutException -> UpdatePolicy.ErrorKind.TIMEOUT
        is SocketException -> UpdatePolicy.ErrorKind.RESET
        is InterruptedIOException -> UpdatePolicy.ErrorKind.TIMEOUT
        is IOException -> UpdatePolicy.ErrorKind.RESET
        else -> UpdatePolicy.ErrorKind.UNKNOWN
    }

    private fun describeNetworkError(e: Exception): String = when (e) {
        is UnknownHostException -> "No network connection."
        is SocketTimeoutException -> "The connection timed out."
        is IOException -> "The network request failed."
        else -> "The download could not complete."
    }
}
