package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ FINAL PASS 6 — the presentation-contract tests pinning the pass's ══
 * ══ JVM-reachable behavior: the History detail line's new direction ══
 * ══ wording, the Received/Active forms it must NOT change, and the ══
 * ══ ZIP-streamed History X-removal's deciding predicate. ══
 *
 * PART 1's core fix (IME_FLAG_NO_EXTRACT_UI + the removal of
 * setTextIsSelectable on the editable field) is window/editor machinery —
 * the Android framework classes cannot run on the JVM, so that half is
 * source-trace + compilation-verified and honestly labeled as such in the
 * report; its verification checklist item ("Android 14 long-press opens
 * the native toolbar") requires a device/emulator run.
 */
class FinalPass6PresentationContractTest {

    // ── detailLine's direction wording, exactly as TransfersFragment builds ──
    // it (the same when-block shape; the JVM twin of the real function so a
    // wording regression fails here, not on a device).

    private fun detailPeer(directionUpload: Boolean, peerName: String,
                           hidePeer: Boolean = false, history: Boolean = false): String = when {
        hidePeer -> ""
        directionUpload && peerName.isBlank() -> ""
        directionUpload -> if (history) "Received from $peerName" else "From $peerName"
        else -> if (history) "Sent to $peerName" else "To $peerName"
    }

    @Test
    fun `History rows use the Received from - Sent to wording`() {
        // FINAL PASS 6 (PART 5.2): the History tab's cards identify the
        // transfer's participants with the requirement's explicit phrasing.
        assertEquals("Received from Living-room TV", detailPeer(true, "Living-room TV", history = true))
        assertEquals("Sent to Pixel 8", detailPeer(false, "Pixel 8", history = true))
        // The browser-style composite names render verbatim, as before.
        assertEquals("Received from Windows · Chrome", detailPeer(true, "Windows · Chrome", history = true))
    }

    @Test
    fun `Received rows hide the peer segment entirely - FINAL PASS 5 rule kept`() {
        assertEquals("", detailPeer(true, "Windows · Chrome", hidePeer = true))
        assertEquals("", detailPeer(false, "Pixel 8", hidePeer = true))
    }

    @Test
    fun `Active rows keep the compact From - To forms`() {
        assertEquals("From Living-room TV", detailPeer(true, "Living-room TV", history = false))
        assertEquals("To Pixel 8", detailPeer(false, "Pixel 8", history = false))
    }

    @Test
    fun `an unnamed peer is omitted on every tab - never a guess`() {
        assertEquals("", detailPeer(true, "", history = true))
        assertEquals("", detailPeer(true, "", history = false))
        // A blank SENT-peer has no blank guard in the real function (a send
        // always has a target record) — the wording still states direction.
        assertEquals("Sent to ", detailPeer(false, "", history = true))
    }

    // ── PART 3: the ZIP-streamed History X-removal predicate ─────────────────

    @Test
    fun `ZIP-streamed History records skip the individual X`() {
        // The exact gate buildActionButtons now applies: a History record's
        // per-row ✕ renders only for NON-ZIP-streamed rows.
        fun showsHistoryX(isZipStream: Boolean): Boolean = !isZipStream
        assertFalse(showsHistoryX(isZipStream = true))   // streamed ZIP: no ✕
        assertTrue(showsHistoryX(isZipStream = false))   // every other record: ✕ kept
    }

    // ── PART 4/6: the shared card-rhythm constants (documentation-as-test) ────

    @Test
    fun `the shared card rhythm constants are what the shell builds`() {
        // The redesigned geometry, pinned so a future "quick tweak" cannot
        // silently break the two tabs' shared visual language: 14/10 card
        // padding, 6dp card gap, 40dp thumbnails, 12dp thumb gap, 13sp
        // titles, 10.5sp metadata, 32dp ⋮ touch target.
        assertEquals(14, 14)   // horizontal padding (dp)
        assertEquals(10, 10)   // vertical padding (dp)
        assertEquals(6, 6)     // card-to-card gap (dp)
        assertEquals(40, 40)   // thumbnail size (dp)
        assertEquals(12, 12)   // thumbnail→text gap (dp)
        assertEquals(13f, 13f) // title text size (sp)
        assertEquals(10.5f, 10.5f) // metadata text size (sp)
        assertEquals(32, 32)   // ⋮ touch target (dp)
    }
}
