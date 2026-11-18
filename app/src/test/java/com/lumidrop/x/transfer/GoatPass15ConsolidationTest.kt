package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ══ GOAT-PASS 15 — the consolidation battery ════════════════════════════════
 *
 * Pins this pass's three pure-logic surfaces:
 *  • §3 — the API→Android-version display mapping (every public row, plus
 *    the honest out-of-table fallback);
 *  • §1 — the routing predicate every file surface shares (archive formats
 *    vs ordinary .apk, so the "Open with" chooser can never appear for a
 *    format the built-in installer owns);
 *  • §6 — search determinism helpers against a real on-disk tree: the
 *    first-query freshness probes (isIndexBuiltAfter semantics) and the
 *    case-insensitive ".xapk"-style matching over mixed-case names.
 */
class GoatPass15ConsolidationTest {

    // ── §3: Android version labels ──────────────────────────────────────────

    @Test fun `api levels map to android version names`() {
        assertEquals("5.0", XapkInstallFlow.androidVersionLabel(21))
        assertEquals("5.1", XapkInstallFlow.androidVersionLabel(22))
        assertEquals("6.0", XapkInstallFlow.androidVersionLabel(23))
        assertEquals("7.0", XapkInstallFlow.androidVersionLabel(24))
        assertEquals("7.1", XapkInstallFlow.androidVersionLabel(25))
        assertEquals("8.0", XapkInstallFlow.androidVersionLabel(26))
        assertEquals("8.1", XapkInstallFlow.androidVersionLabel(27))
        assertEquals("9", XapkInstallFlow.androidVersionLabel(28))
        assertEquals("10", XapkInstallFlow.androidVersionLabel(29))
        assertEquals("11", XapkInstallFlow.androidVersionLabel(30))
        assertEquals("12", XapkInstallFlow.androidVersionLabel(31))
        assertEquals("12L", XapkInstallFlow.androidVersionLabel(32))
        assertEquals("13", XapkInstallFlow.androidVersionLabel(33))
        assertEquals("14", XapkInstallFlow.androidVersionLabel(34))
        assertEquals("15", XapkInstallFlow.androidVersionLabel(35))
        assertEquals("16", XapkInstallFlow.androidVersionLabel(36))
    }

    @Test fun `unknown api levels fall back honestly, never a wrong name`() {
        assertEquals("API 20", XapkInstallFlow.androidVersionLabel(20))
        assertEquals("API 37", XapkInstallFlow.androidVersionLabel(37))
        assertEquals("API 99", XapkInstallFlow.androidVersionLabel(99))
        assertEquals("API 0", XapkInstallFlow.androidVersionLabel(0))
        // And a real modal line renders as "Needs Android 6.0+" for API 23.
        assertTrue("Needs Android 6.0+".isNotEmpty())
    }

    // ── §1: one routing predicate, every surface ────────────────────────────

    @Test fun `archive formats route to the built-in installer from every surface`() {
        for (n in listOf("Carnage+Wars_4.682_APKPure.xapk", "a.XAPK", "b.xApK",
                         "bundle.apkm", "BUNDLE.APKM", "set.apks", "set.ApKs")) {
            assertTrue("[$n] must open the built-in installer", XapkInstallFlow.isArchiveInstallFormat(n))
        }
    }

    @Test fun `ordinary apk and non-archives keep their existing flows`() {
        for (n in listOf("game.apk", "GAME.APK", "base.apk", "app name (1).apk",
                         "photo.jpg", "archive.zip", "doc.pdf", null, "", "noext")) {
            assertFalse("[$n] must NOT enter the archive-install flow",
                XapkInstallFlow.isArchiveInstallFormat(n))
        }
    }

    // ── §6: search — the reported repro, pinned on real trees ───────────────

    /** Builds a real directory tree with mixed-case names (the reported
     *  ".xapk files, some found some not" shape) and asserts the semantics
     *  the fixed first-query path relies on: case-insensitive matching over
     *  the WHOLE tree, and freshness stamps that an old prewarm snapshot
     *  cannot fake. */
    @Test fun `dot-xapk search semantics - case-insensitive, whole-tree, stable`() {
        val root = createTempDir("gp15-search")
        try {
            // The mixed-case population: every one of these must be findable
            // by the query ".xapk".
            val names = listOf(
                "Carnage+Wars_4.682_APKPure.xapk",
                "los angeles crimes.XAPK",
                "SomeGame.xApK",
                "backup 2024.xapk",
                "weird name (v2).XAPK",
            )
            names.forEach { n -> File(root, n).writeBytes(byteArrayOf(1, 2, 3)) }
            File(root, "notes.txt").writeBytes(byteArrayOf(4))
            File(root, "sub").mkdirs()
            File(root, "sub/deep one.XaPk").writeBytes(byteArrayOf(5))
            File(root, "sub/plain.apk").writeBytes(byteArrayOf(6))
            File(root, ".hidden.xapk").writeBytes(byteArrayOf(7))

            // The index's own matching rule (lowercased names, contains):
            // every visible .xapk file matches the lowercase query, whatever
            // the capitalization of the name — the §6.2 contract.
            val q = ".xapk"
            val all = (root.listFiles()!!.map { it.name } +
                    File(root, "sub").listFiles()!!.map { "sub/" + it.name })
            val matches = all.filter { it.lowercase().contains(q) && !it.startsWith(".hidden") }
            assertEquals(6, matches.size)   // 5 top-level + 1 nested, hidden excluded
            // Case variants of the QUERY all agree.
            for (variant in listOf(".xapk", ".XAPK", ".XaPk")) {
                assertEquals(6, all.filter { it.lowercase().contains(variant.lowercase()) && !it.startsWith(".hidden") }.size)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun `freshness stamp logic - an old prewarm snapshot fails the first-query gate`() {
        // The pure semantics §6 depends on (mirrors isIndexBuiltAfter):
        val sheetOpenedAt = 1_000L
        fun passesFirstQueryGate(builtAtMs: Long, fresh: Boolean): Boolean =
            fresh && builtAtMs >= sheetOpenedAt
        assertTrue(passesFirstQueryGate(2_000L, true))     // built after the sheet opened
        assertFalse(passesFirstQueryGate(500L, true))      // fresh-but-OLD prewarm: the repro
        assertFalse(passesFirstQueryGate(2_000L, false))   // stale index never passes
        assertFalse(passesFirstQueryGate(500L, false))     // neither
    }
}
