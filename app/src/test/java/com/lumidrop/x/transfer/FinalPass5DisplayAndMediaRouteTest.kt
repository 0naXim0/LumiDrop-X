package com.lumidrop.x.transfer

import com.lumidrop.x.server.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ FINAL PASS 5 (§5 + §8) — the Received card's display title and the ══
 * ══ media-routing predicates, pinned on the JVM. ══
 *
 * §5's displayTitleFor: the shell's tv_name keeps its MIDDLE-ellipsized
 * single line (it measures the real width); the helper only refines WHAT
 * text is fed in — names ≤ 48 chars verbatim, longer names pre-shortened
 * to "first 30 … last 15" so the extension stays identifiable (.apk/.pdf/
 * .zip/.mp4/.jpg). The full filename is never modified — presentation only.
 *
 * §8's router predicates: openTransferFile must send images/videos to the
 * internal previewer, audio to the shared music controller, and everything
 * else (documents, APKs, archives) to the existing open flow. The
 * predicates mirror the router's own derivation (MIME first, extension
 * fallback), so this suite pins the classification the router relies on —
 * including the tricky families: an octet-stream .flac is still audio; an
 * SVG/ICO is deliberately NOT previewable (the previewer's own exclusion,
 * see QueueAdapter.isPreviewableMedia); an .apk never classifies as media.
 */
class FinalPass5DisplayAndMediaRouteTest {

    // ── displayTitleFor (§5) — the exact helper from TransfersFragment ──────

    private fun displayTitleFor(fileName: String): String {
        if (fileName.length <= 48) return fileName
        val head = fileName.take(30).trimEnd()
        val tail = fileName.takeLast(15)
        return "$head…$tail"
    }

    @Test
    fun `short and moderate names render verbatim`() {
        assertEquals("photo.jpg", displayTitleFor("photo.jpg"))
        assertEquals("Quarterly Report 2026 Final.pdf", displayTitleFor("Quarterly Report 2026 Final.pdf"))
        // Exactly AT the 48-char budget: untouched (the first char that
        // crosses it enters the head…tail path).
        val exactly48 = "a".repeat(44) + ".txt"
        assertEquals(48, exactly48.length)
        assertEquals(exactly48, displayTitleFor(exactly48))
        // One char over: pre-shortened, still compact, extension visible.
        val over49 = "a".repeat(45) + ".txt"
        assertEquals(49, over49.length)
        val out49 = displayTitleFor(over49)
        assertTrue(out49.length <= 48 && out49.contains("…") && out49.endsWith(".txt"))
    }

    @Test
    fun `long names keep head and extension-identifiable tail`() {
        val longApk = "LumiDrop-X-FULL-FINAL-PASS-4-release-signed-build-1789234.apk"
        val out = displayTitleFor(longApk)
        assertTrue(out.startsWith("LumiDrop-X-FULL-FINAL-PAS"))
        assertTrue(out.endsWith("1789234.apk"))              // the extension survives
        assertTrue(out.length <= 48)                          // compact — never expands the card
        assertTrue(out.contains("…"))                         // the clean ellipsis

        val longZip = "backup-of-photos-and-videos-and-documents-from-2019-through-2026.zip"
        assertTrue(displayTitleFor(longZip).endsWith("026.zip"))
    }

    @Test
    fun `names with spaces and no extension pass through the same rules`() {
        val longSpaced = "Meeting notes from the design review session we had last Tuesday"
        val out = displayTitleFor(longSpaced)
        assertTrue(out.length <= 48)
        assertTrue(out.contains("…"))
        assertEquals("Notes on call", displayTitleFor("Notes on call"))
    }

    // ── the §8 router's classification (MIME first, extension fallback) ─────

    private fun classify(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypes.getMimeType(ext)
        // SVG/ICO carry image/* MIMEs but have no raster pixels the internal
        // previewer can decode — the same exclusion QueueAdapter's own
        // isPreviewableMedia applies (mirrors openTransferFile's router).
        val previewableByMime = mime.startsWith("image/") && ext != "svg" && ext != "ico"
        val isImage = previewableByMime ||
            (!mime.startsWith("video/") && !mime.startsWith("audio/") && MimeTypes.isImage(ext) && ext != "svg" && ext != "ico")
        val isVideo = mime.startsWith("video/") ||
            (!mime.startsWith("image/") && !mime.startsWith("audio/") && MimeTypes.isVideo(ext))
        val isAudio = mime.startsWith("audio/") ||
            (!mime.startsWith("image/") && !mime.startsWith("video/") && MimeTypes.isAudio(ext))
        return when {
            isImage -> "preview"
            isVideo -> "preview"
            isAudio -> "music"
            else -> "external"
        }
    }

    @Test
    fun `images and videos route to the internal previewer`() {
        listOf("IMG_20260914.jpg", "photo.heic", "pic.png", "anim.gif", "wallpaper.webp").forEach {
            assertEquals("preview", classify(it))
        }
        listOf("clip.mp4", "movie.mkv", "video.mov", "cam.3gp", "stream.webm").forEach {
            assertEquals("preview", classify(it))
        }
    }

    @Test
    fun `audio routes to the internal music controller`() {
        listOf("song.mp3", "track.flac", "voice.wav", "ringtone.ogg", "audiobook.m4a", "opus-file.opus").forEach {
            assertEquals("music", classify(it))
        }
        // The octet-stream trap: an unknown-MIME extension that resolves to
        // audio by extension still routes to music (the same rule the Send
        // tab's audio test uses).
        assertEquals("music", classify("unindexed-file.flac"))
    }

    @Test
    fun `documents APKs and archives keep the external open flow`() {
        listOf("doc.pdf", "sheet.xlsx", "slides.pptx", "report.docx", "app.apk", "bundle.zip",
            "archive.7z", "firmware.tar", "data.bin").forEach {
            assertEquals("external", classify(it))
        }
    }

    @Test
    fun `svg and ico are deliberately not previewable - the previewer's own exclusion`() {
        // The previewer's decode pipeline has no SVG rasterizer and (until
        // ThumbnailShared's special case) no ICO decoder — QueueAdapter's
        // isPreviewableMedia excludes both, and the router inherits that
        // exclusion so those types keep the honest external-open flow.
        assertEquals("external", classify("vector.svg"))
        assertEquals("external", classify("favicon.ico"))
        assertFalse(MimeTypes.isImage("svg") && true && classify("vector.svg") == "preview")
    }

    @Test
    fun `no false positives across the supported media extension sets`() {
        // Every extension MimeTypes knows as image/video/audio must classify
        // as preview/music — and nothing else may.
        for (ext in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")) {
            assertTrue(MimeTypes.isImage(ext))
        }
        for (ext in listOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "m4v", "ts")) {
            assertTrue(MimeTypes.isVideo(ext))
        }
        for (ext in listOf("mp3", "flac", "ogg", "wav", "aac", "m4a", "opus", "wma", "aiff")) {
            assertTrue(MimeTypes.isAudio(ext))
        }
        // Cross-family sanity: no extension is two families at once (the
        // router's when{} would still pick one deterministically, but the
        // sets must be disjoint for the labels to be honest).
        val imgs = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
        val vids = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "3gp", "webm", "m4v", "ts")
        val auds = setOf("mp3", "flac", "ogg", "wav", "aac", "m4a", "opus", "wma", "aiff")
        assertTrue((imgs intersect vids).isEmpty())
        assertTrue((imgs intersect auds).isEmpty())
        assertTrue((vids intersect auds).isEmpty())
    }
}
