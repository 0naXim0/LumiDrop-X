package com.lumidrop.x.server

/**
 * ══ PASS 28 — the ZIP-stream delivery gate, as pure logic ═══════════════════
 *
 * A live-synthesized archive (folder ZIP streaming) has NO knowable total size
 * at response time, so its serving row is tracked with totalBytes = -1 — which
 * makes the per-connection success flag of [ProgressTrackingInputStream]
 * ("not deliberately cancelled") PROVE NOTHING about whether the reader
 * actually delivered the archive: a connection that died mid-send reports
 * "success" all the same.
 *
 * The old completion gate (`readerOk && writer finished cleanly`) therefore
 * still completed the row whenever the WRITER had managed to finish — and a
 * tiny archive finishes into the pipe's 256 KB buffer almost instantly. So
 * "user cancels a small folder ZIP from the browser's Downloads section
 * mid-transfer" resolved as COMPLETED on a connection whose remaining bytes
 * nobody ever received: exactly the false-Completed shape this pass's brief
 * forbids, plus it starved the cancellation verdict of a live row to act on.
 *
 * The gate this pass pins: the delivery completed ONLY when the writer
 * finished writing the archive AND the reader actually read EVERY byte the
 * writer wrote (readerBytes >= writerBytes — an abandoned reader always
 * leaves the tail unread in the pipe). Pure JVM logic so the matrix is
 * unit-tested without Android.
 */
object ZipStreamDelivery {

    /**
     * Did a live-synthesized archive delivery genuinely complete?
     *
     * @param writerFinishedCleanly the generation thread wrote the whole
     *        archive into the pipe without error (zipCompletedCleanly).
     * @param writerBytes the exact number of bytes the generation thread
     *        wrote into the pipe.
     * @param readerBytes the exact number of bytes the serving connection
     *        read OUT of the pipe (the tracking stream's own count).
     */
    fun deliveredFully(writerFinishedCleanly: Boolean, writerBytes: Long, readerBytes: Long): Boolean =
        writerFinishedCleanly && writerBytes > 0L && readerBytes >= writerBytes
}
