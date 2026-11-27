package com.lumidrop.x.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ PASS 28 — the ZIP-stream delivery gate, pinned on the JVM ═════════════════
 *
 * A live-synthesized archive is tracked with totalBytes = -1 (its size is
 * unknowable up front), so a serving connection's own success flag proves
 * nothing about delivery: a connection that died mid-send reports "not
 * deliberately cancelled" all the same. The old gate (writer finished) then
 * completed the row whenever the GENERATION thread happened to finish — and a
 * tiny archive finishes into the pipe's 256 KB buffer almost instantly, so
 * "user cancels a small folder ZIP from the browser's Downloads section"
 * resolved as a false COMPLETED (starving the cancellation verdict of a live
 * row to act on). The gate demands the reader actually read every byte the
 * writer wrote.
 */
class Pass28ZipStreamDeliveryTest {

    @Test fun `a fully read, cleanly generated archive completes`() {
        assertTrue(ZipStreamDelivery.deliveredFully(writerFinishedCleanly = true, writerBytes = 50_000L, readerBytes = 50_000L))
        assertTrue(ZipStreamDelivery.deliveredFully(true, 3_000_000L, 3_000_000L))
    }

    @Test fun `an abandoned reader never completes - the tail stayed unread in the pipe`() {
        // The tiny-folder cancel shape: the writer long finished (the archive
        // fit inside the pipe buffer), the reader left mid-stream.
        assertFalse(ZipStreamDelivery.deliveredFully(true, 5_000L, 1_200L))
        assertFalse(ZipStreamDelivery.deliveredFully(true, 30_000_000L, 12_000_000L))
    }

    @Test fun `a generation that died mid-write never completes - reader bytes aside`() {
        assertFalse(ZipStreamDelivery.deliveredFully(false, 5_000L, 5_000L))
        assertFalse(ZipStreamDelivery.deliveredFully(false, 0L, 0L))
    }

    @Test fun `a zero-byte archive is never a delivery`() {
        // Degenerate shape (empty folder / empty selection): writer clean but
        // zero bytes written — nothing was delivered, so nothing completes.
        assertFalse(ZipStreamDelivery.deliveredFully(true, 0L, 0L))
    }
}
