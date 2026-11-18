package com.lumidrop.x.ui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * ══ FINAL PASS 2 (§4) — empty folders Copy / Cut / Paste correctly ══
 *
 * An empty folder is a valid filesystem item. The old plan model carried
 * FILES only, so a destination directory was only ever created as the parent
 * of a landing FILE: copying an empty folder reported "Copied 1 item" while
 * pasting NOTHING, and a cross-volume cut's cleanup could even delete the
 * source without creating any destination. The plan now carries directories
 * too, and both operations create the destination tree up-front.
 *
 * These tests run the REAL FileOps engine (plain java.io + coroutines, by
 * design Android-free) against temp folders: one empty folder, several empty
 * folders, empty nested folders, mixed content, excluded children, hidden
 * files, and paste-into-same-directory collisions.
 */
class FinalPass2EmptyFolderOpsTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var srcRoot: File
    private lateinit var destRoot: File

    @Before
    fun setUp() {
        srcRoot = tmp.newFolder("src")
        destRoot = tmp.newFolder("dest")
    }

    private fun pick(f: File, excluded: Set<String> = emptySet()) = FileOps.Pick(f, excluded)

    @Test
    fun `copying one empty folder pastes the real empty folder`() = runBlocking {
        val empty = File(srcRoot, "Empty").apply { mkdirs() }
        val result = FileOps.copyBatch(listOf(pick(empty)), destRoot, false) { }
        assertEquals(1, result.ok)
        assertEquals(0, result.failed)
        val landed = File(destRoot, "Empty")
        assertTrue("the destination folder exists", landed.isDirectory)
        assertEquals("and it is empty, exactly like the source", 0, landed.listFiles()!!.size)
    }

    @Test
    fun `cutting one empty folder moves it (atomic rename path)`() = runBlocking {
        val empty = File(srcRoot, "Empty").apply { mkdirs() }
        val result = FileOps.moveBatch(listOf(pick(empty)), destRoot, false) { }
        assertEquals(1, result.ok)
        assertEquals(0, result.failed)
        assertTrue(File(destRoot, "Empty").isDirectory)
        assertFalse("the source is gone after the move", empty.exists())
    }

    @Test
    fun `several empty folders alongside normal files all land`() = runBlocking {
        val a = File(srcRoot, "A-empty").apply { mkdirs() }
        val b = File(srcRoot, "B-empty").apply { mkdirs() }
        val f = File(srcRoot, "note.txt").apply { writeText("hello") }
        val result = FileOps.copyBatch(listOf(pick(a), pick(b), pick(f)), destRoot, false) { }
        assertEquals(3, result.ok)
        assertEquals(0, result.failed)
        assertTrue(File(destRoot, "A-empty").isDirectory)
        assertTrue(File(destRoot, "B-empty").isDirectory)
        assertEquals("hello", File(destRoot, "note.txt").readText())
    }

    @Test
    fun `empty nested folders survive inside a copied folder`() = runBlocking {
        File(srcRoot, "Tree").apply {
            mkdirs()
            File(this, "docs").mkdirs()
            File(this, "media").mkdirs()
            File(File(this, "media"), "photos").mkdirs()
            File(this, "readme.txt").writeText("x")
        }
        val result = FileOps.copyBatch(listOf(pick(File(srcRoot, "Tree"))), destRoot, false) { }
        assertEquals(1, result.ok)
        assertEquals(0, result.failed)
        assertTrue(File(File(destRoot, "Tree"), "docs").isDirectory)
        assertTrue(File(File(destRoot, "Tree"), "media").isDirectory)
        assertTrue(File(File(File(destRoot, "Tree"), "media"), "photos").isDirectory)
        assertEquals("x", File(File(destRoot, "Tree"), "readme.txt").readText())
    }

    @Test
    fun `a folder whose children are all excluded still lands as the folder`() = runBlocking {
        val dir = File(srcRoot, "Partial").apply { mkdirs() }
        File(dir, "keep.txt").writeText("k")
        File(dir, "drop.txt").writeText("d")
        val result = FileOps.copyBatch(
            listOf(pick(dir, excluded = setOf(File(dir, "drop.txt").absolutePath))),
            destRoot, false) { }
        assertEquals(1, result.ok)
        val landed = File(destRoot, "Partial")
        assertTrue(landed.isDirectory)
        assertTrue(File(landed, "keep.txt").isFile)
        assertFalse("the excluded child did not copy", File(landed, "drop.txt").exists())
        // A folder with EVERY child excluded still lands as the folder — the
        // exact "nothing to paste" shape the old file-only plan produced.
        val allExcluded = File(srcRoot, "AllExcluded").apply {
            mkdirs(); File(this, "drop.txt").writeText("d")
        }
        val r2 = FileOps.copyBatch(
            listOf(pick(allExcluded, excluded = setOf(File(allExcluded, "drop.txt").absolutePath))),
            destRoot, false) { }
        assertEquals(1, r2.ok)
        assertTrue("a folder with every child excluded still lands as the folder",
            File(destRoot, "AllExcluded").isDirectory)
    }

    @Test
    fun `hidden children are skipped but the visible folder hierarchy lands`() = runBlocking {
        val dir = File(srcRoot, "Hid").apply {
            mkdirs()
            File(this, ".secret").writeText("s")
            File(this, "visible.txt").writeText("v")
            File(this, "inner").mkdirs()
        }
        val result = FileOps.copyBatch(listOf(pick(dir)), destRoot, false) { }
        assertEquals(1, result.ok)
        val landed = File(destRoot, "Hid")
        assertTrue(landed.isDirectory)
        assertTrue(File(landed, "visible.txt").isFile)
        assertFalse(File(landed, ".secret").exists())
        assertTrue("the empty visible subfolder lands too", File(landed, "inner").isDirectory)
    }

    @Test
    fun `pasting into the same directory lands collision-suffixed never overwritten`() = runBlocking {
        val empty = File(srcRoot, "Empty").apply { mkdirs() }
        val r1 = FileOps.copyBatch(listOf(pick(empty)), srcRoot, false) { }
        assertEquals(1, r1.ok)
        assertTrue(File(srcRoot, "Empty").isDirectory)          // original
        assertTrue(File(srcRoot, "Empty (1)").isDirectory)      // collision-suffixed copy
        val r2 = FileOps.copyBatch(listOf(pick(empty)), srcRoot, false) { }
        assertEquals(1, r2.ok)
        assertTrue(File(srcRoot, "Empty (2)").isDirectory)
    }

    @Test
    fun `moving a folder with files and empty dirs removes the source only after landing`() = runBlocking {
        val dir = File(srcRoot, "MoveMe").apply {
            mkdirs()
            File(this, "a.txt").writeText("a")
            File(this, "empty-child").mkdirs()
        }
        val result = FileOps.moveBatch(listOf(pick(dir)), destRoot, false) { }
        assertEquals(1, result.ok)
        assertEquals(0, result.failed)
        assertTrue(File(File(destRoot, "MoveMe"), "a.txt").isFile)
        assertTrue(File(File(destRoot, "MoveMe"), "empty-child").isDirectory)
        assertFalse("the source tree is gone after a successful move", dir.exists())
    }

    @Test
    fun `a cancelled copy never counts the empty folder as succeeded`() = runBlocking {
        // A real payload first, so the engine is genuinely streaming when the
        // cancel lands (the cancel flag is a mid-operation signal — the batch
        // start resets it by design). The empty folder is the SECOND pick, so
        // the cancellation must leave it unprocessed: nothing is counted as
        // copied and no empty-folder shell lands at the destination.
        val payload = File(srcRoot, "payload.bin").apply {
            java.io.RandomAccessFile(this, "rw").use { raf ->
                raf.setLength(4L * 1024 * 1024)
            }
        }
        val empty = File(srcRoot, "Empty").apply { mkdirs() }
        var cancelledOnce = false
        val result = FileOps.copyBatch(listOf(pick(payload), pick(empty)), destRoot, false) { p ->
            if (!cancelledOnce && p.doneBytes > 0L) {
                cancelledOnce = true
                FileOps.cancel()
            }
        }
        assertTrue(result.cancelled)
        assertFalse("the cancelled pick's partial is deleted", File(destRoot, "payload.bin").exists())
        assertFalse("a pick the run never reached is never counted or created",
            File(destRoot, "Empty").exists())
    }
}
