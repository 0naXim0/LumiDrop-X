package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ GOAT-PASS 14 (Phase 1, §1.4) — the ONE user-facing ZIP filename ══════
 *
 * Pins [ZipNames] — the single generator behind the browser's own
 * "Download as zip" AND the new app-side queue-batch ZIP sends (both legs),
 * so both naming systems can never drift apart. The professional contract:
 *
 *  • ONE source      -> "<SourceName>.zip" (full name; a source already
 *                        ending ".zip" is never doubled);
 *  • MULTIPLE sources -> "<First>_<Second>[_more]__(N_<noun>).zip"
 *      - First capped at 60 chars (word-boundary cut, one "…"),
 *      - Second capped at 25,
 *      - "_more" only when there are more sources than the two labels,
 *      - N counts the TOP-LEVEL sources actually included, noun follows the
 *        all-Folders / all-Files / mixed-Items rule;
 *  • every label sanitized (filename-hostile chars -> '_', leading dots
 *    dropped, whitespace collapsed) but meaningful Unicode preserved;
 *  • the whole label portion stays bounded, and a degenerate/empty input
 *    still produces a valid name.
 */
class GoatPass14ZipNamesTest {

    // ── single source ───────────────────────────────────────────────────

    @Test
    fun `single source keeps its full name and gains one zip extension`() {
        assertEquals("Vacation.zip",
            ZipNames.build(listOf(ZipNames.Entry("Vacation", isFolder = true))))
        assertEquals("Report.pdf.zip",
            ZipNames.build(listOf(ZipNames.Entry("Report.pdf", isFolder = false))))
    }

    @Test
    fun `a source already ending in zip is never doubled`() {
        assertEquals("Backup.zip",
            ZipNames.build(listOf(ZipNames.Entry("Backup.zip", isFolder = false))))
        assertEquals("backup.ZIP",
            ZipNames.build(listOf(ZipNames.Entry("backup.ZIP", isFolder = false))))
    }

    @Test
    fun `empty input falls back to a stable archive name`() {
        assertEquals("Archive.zip", ZipNames.build(emptyList()))
        assertEquals("Archive.zip", ZipNames.build(listOf(ZipNames.Entry("  ", isFolder = false))))
    }

    // ── multiple sources: shape, _more, noun ────────────────────────────

    @Test
    fun `exactly two sources show both labels and no more marker`() {
        assertEquals("Trip_Notes__(2_Files).zip", ZipNames.build(listOf(
            ZipNames.Entry("Trip", isFolder = false),
            ZipNames.Entry("Notes", isFolder = false))))
    }

    @Test
    fun `three plus sources append more and count every source`() {
        assertEquals("Trip_Notes_more__(3_Files).zip", ZipNames.build(listOf(
            ZipNames.Entry("Trip", isFolder = false),
            ZipNames.Entry("Notes", isFolder = false),
            ZipNames.Entry("Extra", isFolder = false))))
        val ten = (1..10).map { ZipNames.Entry("f$it", isFolder = false) }
        assertEquals("f1_f2_more__(10_Files).zip", ZipNames.build(ten))
    }

    @Test
    fun `noun follows the all-folders all-files mixed-items rule`() {
        assertEquals("A_B__(2_Folders).zip", ZipNames.build(listOf(
            ZipNames.Entry("A", isFolder = true), ZipNames.Entry("B", isFolder = true))))
        assertEquals("A_B__(2_Items).zip", ZipNames.build(listOf(
            ZipNames.Entry("A", isFolder = true), ZipNames.Entry("B", isFolder = false))))
        assertEquals("A_B__(2_Files).zip", ZipNames.build(listOf(
            ZipNames.Entry("A", isFolder = false), ZipNames.Entry("B", isFolder = false))))
    }

    // ── caps ────────────────────────────────────────────────────────────

    @Test
    fun `first label is capped at 60 characters with an ellipsis`() {
        val long = "x".repeat(200)
        val name = ZipNames.build(listOf(
            ZipNames.Entry(long, isFolder = false), ZipNames.Entry("b", isFolder = false)))
        // "<60-char capped first>_b__(2_Files).zip" — the first label is at
        // most 60 code points INCLUDING its cut marker.
        val firstLabel = name.substringBefore("_b__")
        assertTrue("first label must be capped (was ${firstLabel.length})",
            firstLabel.length <= 60)
        assertTrue("a genuine cut is marked with one ellipsis",
            firstLabel.endsWith("\u2026"))
    }

    @Test
    fun `second label is capped at 25 characters with an ellipsis`() {
        val name = ZipNames.build(listOf(
            ZipNames.Entry("a", isFolder = false),
            ZipNames.Entry("y".repeat(80), isFolder = false)))
        val secondLabel = name.substringAfter("a_").substringBefore("__")
        assertTrue("second label must be capped (was ${secondLabel.length})",
            secondLabel.length <= 25)
        assertTrue(secondLabel.endsWith("\u2026"))
    }

    @Test
    fun `the whole filename stays bounded no matter the labels`() {
        val name = ZipNames.build(listOf(
            ZipNames.Entry("α".repeat(300), isFolder = true),
            ZipNames.Entry("β".repeat(300), isFolder = true),
            ZipNames.Entry("γ".repeat(300), isFolder = true)))
        assertTrue("whole name must stay comfortably under a filename limit (was ${name.length})",
            name.length <= 160)
    }

    @Test
    fun `short labels are never cut`() {
        val name = ZipNames.build(listOf(
            ZipNames.Entry("Hawaii Trip", isFolder = true),
            ZipNames.Entry("Notes", isFolder = false)))
        assertEquals("Hawaii Trip_Notes__(2_Items).zip", name)
    }

    // ── sanitization ────────────────────────────────────────────────────

    @Test
    fun `filename-hostile characters become underscores and leading dots drop`() {
        assertEquals("a_b_c.zip", ZipNames.build(listOf(
            ZipNames.Entry("a/b\\c", isFolder = false))))
        assertEquals("hidden.zip", ZipNames.build(listOf(
            ZipNames.Entry(".hidden", isFolder = false))))
    }

    @Test
    fun `whitespace runs collapse but meaningful unicode survives`() {
        assertEquals("My Documents.zip", ZipNames.build(listOf(
            ZipNames.Entry("My \t\n Documents", isFolder = true))))
        assertEquals("ドキュメント.zip", ZipNames.build(listOf(
            ZipNames.Entry("ドキュメント", isFolder = true))))
    }

    @Test
    fun `a label that sanitizes to nothing falls back to file`() {
        assertEquals("file.zip", ZipNames.build(listOf(
            ZipNames.Entry("...", isFolder = false))))
    }

    // ── the count always reflects the entries the caller passed ─────────

    @Test
    fun `count comes from the actual inclusion-filtered entries`() {
        // The callers filter unusable batch members BEFORE calling build
        // (usableZipBatchItems); the generator itself counts exactly what it
        // is given — 9 sources means 9 in the name, whatever their kinds.
        val mixed = listOf(
            ZipNames.Entry("First", isFolder = false),
            ZipNames.Entry("Second", isFolder = true)) +
            (1..7).map { ZipNames.Entry("m$it", isFolder = false) }
        val name = ZipNames.build(mixed)
        assertEquals("First_Second_more__(9_Items).zip", name)
    }
}
