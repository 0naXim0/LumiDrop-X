package com.lumidrop.x.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ══ GOAT-PASS 14 (PART 7) — the shared routing predicate. Ordinary .apk ──
 *  files must NEVER enter the archive-install flow; only multi-component
 *  container formats do. Both Received and Files route through this one
 *  predicate, so the two surfaces cannot drift apart again. */
class GoatPass14SharedInstallRoutingTest {

    @Test fun `ordinary apk is not an archive install`() {
        assertFalse(XapkInstallFlow.isArchiveInstallFormat("game.apk"))
        assertFalse(XapkInstallFlow.isArchiveInstallFormat("GAME.APK"))
        assertFalse(XapkInstallFlow.isArchiveInstallFormat("base.apk"))
        assertFalse(XapkInstallFlow.isArchiveInstallFormat("photo.jpg"))
        assertFalse(XapkInstallFlow.isArchiveInstallFormat("archive.zip"))
        assertFalse(XapkInstallFlow.isArchiveInstallFormat(null))
        assertFalse(XapkInstallFlow.isArchiveInstallFormat(""))
        assertFalse(XapkInstallFlow.isArchiveInstallFormat("noext"))
    }

    @Test fun `container formats route to the shared flow`() {
        assertTrue(XapkInstallFlow.isArchiveInstallFormat("Carnage+Wars_4.682_APKPure.xapk"))
        assertTrue(XapkInstallFlow.isArchiveInstallFormat("game.XAPK"))
        assertTrue(XapkInstallFlow.isArchiveInstallFormat("bundle.apkm"))
        assertTrue(XapkInstallFlow.isArchiveInstallFormat("bundle.apks"))
        assertTrue(XapkInstallFlow.isArchiveInstallFormat("weird.name.xApK"))
    }
}
