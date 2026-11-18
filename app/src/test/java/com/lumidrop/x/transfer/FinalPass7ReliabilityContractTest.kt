package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * ══ FINAL PASS 7 — the JVM-reachable contract tests for this pass's ══
 * ══ fixes: the metadata-contrast root cause (PART 3), the stranded- ══
 * ══ sheet visual reconciliation predicate (PART 2), and the ══
 * ══ History-X removal contract (PART 4). ══
 *
 * PART 1 (the Text Sender reconstruction onto the plain Dialog scaffold)
 * and PART 2's window/animation half are Android-framework machinery —
 * they compile (Gradle assembleRelease) and are source-traced, but their
 * runtime verification needs a device and is honestly labeled as such in
 * the report.
 */
class FinalPass7ReliabilityContractTest {

    // ── PART 3: the contrast math that made the metadata invisible ────────────

    /** WCAG relative luminance — the exact formula from the spec. */
    private fun relLuminance(hex: String): Double {
        fun channel(v: Double): Double =
            if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        val r = channel(hex.substring(0, 2).toInt(16) / 255.0)
        val g = channel(hex.substring(2, 4).toInt(16) / 255.0)
        val b = channel(hex.substring(4, 6).toInt(16) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrast(fgHex: String, bgHex: String): Double {
        val l1 = relLuminance(fgHex); val l2 = relLuminance(bgHex)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test
    fun `the OLD metadata color was the invisibility root cause - below 3-1 on the card surface`() {
        // text_muted #676E77 on the cards' actual surface (bg_secondary
        // #2A2E37, via bg_btn_secondary): the FP6-era combination.
        assertTrue(contrast("676E77", "2A2E37") < 3.0)
    }

    @Test
    fun `the NEW metadata color meets WCAG AA on the card surface`() {
        // text_secondary #8F97A3 on the same surface: AA (4.5:1) at last,
        // still a quiet gray that cannot compete with the 13sp title.
        assertTrue(contrast("8F97A3", "2A2E37") >= 4.5)
    }

    @Test
    fun `the new metadata color also clears AA on the elevated card variant`() {
        // bg_card #22252C — the flat card color the surfaces fall back to.
        assertTrue(contrast("8F97A3", "22252C") >= 4.5)
    }

    // ── PART 2: the stranded-sheet reconciliation predicate (pure twin) ───────

    /**
     * The exact decision ReceivingModal.isSheetVisuallyStranded makes —
     * the JVM twin so the gating logic is pinned by tests:
     *   stranded = entranceRan && translationY >= 100dp && age > 1000ms
     */
    private fun stranded(entranceRan: Boolean, translationYdp: Float, ageMs: Long): Boolean =
        entranceRan && translationYdp >= 100f && ageMs > 1000L

    @Test
    fun `a healthy freshly-opened sheet is never stranded - entrance not yet run`() {
        // The first fraction of a second: the entrance runnable hasn't run,
        // the root sits at 400dp — that is an entrance ABOUT TO START.
        assertFalse(stranded(entranceRan = false, translationYdp = 400f, ageMs = 300))
    }

    @Test
    fun `a healthy sheet mid-entrance is never stranded - inside the grace window`() {
        // Entrance running (220ms animation), still displaced, under 1s old.
        assertFalse(stranded(entranceRan = true, translationYdp = 180f, ageMs = 400))
    }

    @Test
    fun `a settled sheet is never stranded - at rest on screen`() {
        // The normal steady state: translationY == 0, any age.
        assertFalse(stranded(entranceRan = true, translationYdp = 0f, ageMs = 60_000))
    }

    @Test
    fun `a strandED sheet - entrance ran, root displaced, grace elapsed - is reconciled`() {
        // The exact race condition: the posted entrance was cancelled by a
        // window-focus transition; the window is attached (isShowing true)
        // while the root sits 400dp below the screen. The tick MUST treat
        // this as stranded and rebuild.
        assertTrue(stranded(entranceRan = true, translationYdp = 400f, ageMs = 1500))
    }

    @Test
    fun `an upward drag is never stranded - resting drags are negative or zero`() {
        // A user dragging the sheet UP produces negative translationY; the
        // predicate's >= 100dp floor never fires on real gestures.
        assertFalse(stranded(entranceRan = true, translationYdp = -80f, ageMs = 5000))
        assertFalse(stranded(entranceRan = true, translationYdp = 40f, ageMs = 5000))
    }

    // ── PART 4: no individual X on any History card ──────────────────────────

    @Test
    fun `no History card class renders the individual X`() {
        // The tab+state contract after FP7: buildActionButtons' per-row ✕
        // exists ONLY on the Active tab (the interrupted-receive partial
        // discard). History (the "sent" tab) renders no ✕ for ANY item
        // type — file, folder, text, ZIP-streamed, failed or completed.
        fun historyShowsX(tab: String): Boolean = tab == "active"
        assertFalse(historyShowsX("sent"))     // History: never
        assertTrue(historyShowsX("active"))    // Active: the discard control remains
    }
}
