package com.lumidrop.x.transfer

import java.io.InputStream

/**
 * Wraps a download's source [InputStream] so that NanoHTTPD's own
 * read-then-send loop — completely unmodified — drives [TransferManager]
 * progress updates and final status, with zero changes to how responses are
 * built elsewhere in LumiDropServer.
 *
 * Two things make this reliable rather than approximate:
 *
 * 1. Progress is updated from the actual number of bytes NanoHTTPD has
 *    pulled out of this stream so far — that's a precise measure of bytes
 *    handed to the socket, not a guess.
 *
 * 2. Success vs. failure is decided in [close] by comparing bytes read to
 *    [expectedTotal] (when known) rather than by waiting to see a -1 from
 *    read() — NanoHTTPD's fixed-length response sender stops reading the
 *    instant it has pulled exactly the declared Content-Length, so a real
 *    end-of-stream signal may never actually be requested even on a fully
 *    successful transfer. NanoHTTPD always closes the data stream in a
 *    `finally` block once it's done sending (success, error, or client
 *    disconnect alike), so [close] is guaranteed to run exactly once per
 *    transfer regardless of how it ends.
 *
 * For streams whose final size genuinely can't be known ahead of time
 * (folder zip downloads), pass `expectedTotal = -1`; completion then simply
 * means "wasn't cancelled and every byte the writer thread produced was
 * read" — the same best-effort standard already used for the WebUI's own
 * client-side zip-download progress.
 *
 * Progress is reported to [TransferManager] as a delta (bytes read by just
 * this call) via [TransferManager.addProgress], not as this stream's own
 * running [total] — [total] stays purely local, used only against
 * [expectedTotal] in [close] below. That distinction matters when more than
 * one connection is attached to the same transfer id (a browser's
 * parallel/segmented Range requests for one large file — see
 * [TransferManager.claimOrStartActiveQuickShare] /
 * [TransferManager.claimOrStartActivePlainDownload]): each instance here
 * only ever knows its own byte-span, so reporting deltas lets their
 * contributions add up correctly in the shared transfer instead of each
 * one's own local total overwriting the others'.
 *
 * Pause support: before each delegate read, [TransferManager.awaitUnpaused]
 * blocks this call's thread while the transfer is paused. NanoHTTPD gives
 * every request its own worker thread, so this only stalls the one transfer
 * being paused — every other concurrent transfer's thread is untouched.
 * Standard TCP/HTTP client behavior treats a stalled-but-open connection as
 * "still downloading, just slow," not an error, so no client-side change is
 * needed for Pause/Resume to work — it's entirely a server-side mechanism.
 *
 * Cancellation deliberately THROWS rather than returning -1 (EOF). A plain
 * EOF against a response that already declared a fixed Content-Length is
 * genuinely ambiguous at the HTTP layer — some server/client combinations
 * may treat "fewer bytes than promised, then silence" as the connection
 * just being slow rather than done, which can leave the client's request
 * hanging indefinitely: exactly the "cancel doesn't really stop it, the
 * transfer dialog never closes" symptom. Throwing forces the HTTP layer to
 * treat this response as broken and tear the connection down outright — a
 * protocol-unambiguous signal that reliably surfaces to the client as a
 * network error within moments, which is what actually lets its own
 * transfer UI leave the terminal "cancelled" state instead of stalling.
 */
class ProgressTrackingInputStream(
    private val delegate: InputStream,
    private val transferId: String,
    private val expectedTotal: Long,
    private val onFinished: (success: Boolean) -> Unit
) : InputStream() {

    @Volatile private var total = 0L
    @Volatile private var finished = false

    /** FINAL PASS 3 (§1): this connection's own cumulative byte count — the
     *  exact archive size a -1-total zip generation just handed to the
     *  socket. Read by the completion callback (which runs after close()
     *  has stopped counting) to stamp the transfer's real final size. */
    val bytesRead: Long get() = total

    // ── Pass-16 ("the browser download keeps flowing after the host taps ──
    //  Cancel", root fix): every deliberate-cancel check here used to be a
    //  bare isCancelled() — a LIVE-MAP lookup that answered "not cancelled"
    //  the moment the CANCELLED row auto-dismissed 400 ms after the cancel.
    //  For a large file the story that produced was exactly the report: the
    //  throw fires for the first ~0.4 s, the row vanishes from the sender's
    //  Active sub-tab, the next read() then sees a missing row and answers
    //  false — and the remaining bytes keep streaming to the browser's
    //  download manager until 100%, invisible on the host.
    //  isCancelAuthoritative() keeps the deliberate-cancel verdict alive
    //  for the full durable-latch window (same duration as the sender's own
    //  auto-resume loop), so the FIRST throw is also the FINAL one: the
    //  connection tears down and every later retry/refetch is refused by
    //  the (name, peer) tombstones the cancel already recorded.
    private fun cancelAuthoritative(): Boolean =
        TransferManager.isCancelled(transferId) || TransferManager.wasCancelledTid(transferId)

    override fun read(): Int {
        TransferManager.awaitUnpaused(transferId)
        if (cancelAuthoritative())
            throw java.io.IOException("Transfer cancelled")
        val b = delegate.read()
        if (b != -1) {
            total++
            TransferManager.addProgress(transferId, 1)
        }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        TransferManager.awaitUnpaused(transferId)
        if (cancelAuthoritative())
            throw java.io.IOException("Transfer cancelled")
        val n = delegate.read(b, off, len)
        if (n > 0) {
            total += n
            TransferManager.addProgress(transferId, n.toLong())
        }
        return n
    }

    override fun close() {
        if (!finished) {
            finished = true
            val success = !cancelAuthoritative() &&
                (expectedTotal < 0 || total >= expectedTotal)
            onFinished(success)
        }
        try { delegate.close() } catch (e: Exception) { /* already gone — nothing to clean up */ }
    }
}
