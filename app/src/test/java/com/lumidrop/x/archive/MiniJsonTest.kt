package com.lumidrop.x.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ══ GOAT-PASS 13 — the lenient JSON reader's own contract ══════════════════
 *  Distributor manifests drift; every quirk below is one observed in the
 *  wild. All of them must parse; actual garbage must not. */
class MiniJsonTest {

    @Test fun `plain apkpure v2 manifest parses`() {
        val m = MiniJson.parse("""{"xapk_version":2,"package_name":"com.ziczac.shooter",
            "name":"Carnage Wars","version_code":482,"version_name":"4.682",
            "split_apks":[{"file":"config.arm64_v8a.apk","name":"config.arm64_v8a"}]}""")
        val o = m.obj()!!
        assertEquals("com.ziczac.shooter", o.get("package_name").str())
        assertEquals(482L, o.get("version_code").long())
        assertEquals(1, o.get("split_apks").arr().size)
    }

    @Test fun `string version code is read as a number`() {
        val m = MiniJson.parse("""{"version_code":"482"}""").obj()!!
        assertEquals(482L, m.get("version_code").strLong())
    }

    @Test fun `comments, trailing commas and case drift are tolerated`() {
        val m = MiniJson.parse("""{
            // packager annotation
            "PackageName": "com.example.app",
            "Split_Apks": [
                {"File": "config.en.apk"},
            ],
        }""").obj()!!
        assertEquals("com.example.app", m.get("packagename").str())   // case-insensitive field
        assertEquals("config.en.apk", m.get("split_apks").arr()[0].obj()?.get("file").str())
    }

    @Test fun `bom and single quotes are tolerated`() {
        val m = MiniJson.parse("﻿{'name':'Carnage Wars'}").obj()!!
        assertEquals("Carnage Wars", m.get("name").str())
    }

    @Test fun `single object where an array was expected unwraps`() {
        val m = MiniJson.parse("""{"split_apks": {"file": "one.apk"}}""").obj()!!
        assertEquals(1, m.get("split_apks").arr().size)
    }

    @Test fun `unicode escapes decode`() {
        val m = MiniJson.parse("""{"name":"\u0043arnage"}""").obj()!!
        assertEquals("Carnage", m.get("name").str())
    }

    @Test fun `garbage is null - never a crash, never a misread`() {
        assertNull(MiniJson.parse(null))
        assertNull(MiniJson.parse(""))
        assertNull(MiniJson.parse("   "))
        assertNull(MiniJson.parse("{ this is not json !!!"))
        assertNull(MiniJson.parse("{\"a\":1} trailing junk"))
        assertNull(MiniJson.parse("[1,2,"))
    }

    @Test fun `nested structures and numbers survive`() {
        val m = MiniJson.parse("""{"a":{"b":[1,2.5,-3,{"c":true}]},"d":false,"e":null}""").obj()!!
        assertEquals(4, m.get("a")?.obj()?.get("b")?.arr()?.size)
        assertEquals(2.5, (m.get("a")?.obj()?.get("b")?.arr()?.get(1) as MJson.MNum).double)
        assertEquals(true, m.get("a")?.obj()?.get("b")?.arr()?.get(3)?.obj()?.get("c")?.bool())
        assertEquals(false, m.get("d").bool())
        assertTrue(m.get("e") is MJson.MNull)
    }
}
