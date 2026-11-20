package com.slipstream.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DownloadJsonTest {
    private fun parse(s: String) = Download.fromJson(Json.parseToJsonElement(s).jsonObject)

    @Test
    fun httpEntryBasics() {
        val d =
            parse(
                """{"gid":"abc123","status":"active","totalLength":"1000","completedLength":"500",
                   "downloadSpeed":"100","uploadSpeed":"0","connections":"4","dir":"/d",
                   "files":[{"index":"1","path":"/d/file.bin","length":"1000","completedLength":"500",
                             "selected":"true","uris":[{"uri":"https://x/file.bin"}]}]}""",
            )
        assertEquals("abc123", d.gid)
        assertEquals(Download.Status.ACTIVE, d.status)
        assertEquals(1000L, d.totalLength)
        assertEquals(500L, d.completedLength)
        assertEquals("file.bin", d.name)
        assertEquals(0.5f, d.progress)
        assertEquals(1, d.files.size)
        assertEquals(listOf("https://x/file.bin"), d.files[0].uris)
        assertNull(d.errorCode)
    }

    @Test
    fun magnetNameComesFromDn() {
        val d =
            parse(
                """{"gid":"m1","status":"waiting","files":[{"path":"","uris":[
                   {"uri":"magnet:?xt=urn:btih:deadbeef&dn=My%20File&tr=udp://t"}]}]}""",
            )
        assertEquals("My%20File", d.name)
    }

    @Test
    fun magnetWithoutDnFallsBackToGid() {
        val d = parse("""{"gid":"m2","status":"waiting","files":[{"path":"","uris":[{"uri":"magnet:?xt=urn:btih:xx"}]}]}""")
        assertEquals("m2", d.name)
    }

    @Test
    fun bittorrentInfoNameWins() {
        val d =
            parse(
                """{"gid":"bt1","status":"active","bittorrent":{"info":{"name":"Torrent Name"}},
                   "files":[{"path":"/d/first.bin","uris":[]}]}""",
            )
        assertEquals("Torrent Name", d.name)
    }

    @Test
    fun unknownStatusAndMissingFields() {
        val d = parse("""{"gid":"x","status":"weird"}""")
        assertEquals(Download.Status.UNKNOWN, d.status)
        assertEquals(0L, d.totalLength)
        assertEquals("x", d.name)
        assertTrue(d.files.isEmpty())
        assertEquals(-1L, d.etaSeconds)
    }

    @Test
    fun uriFallbackStripsQuery() {
        val d =
            parse(
                """{"gid":"u1","status":"error","files":[{"path":"","uris":[
                   {"uri":"https://x/path/file.zip?token=secret"}]}]}""",
            )
        assertEquals("file.zip", d.name)
    }
}
