package com.slipstream

import androidx.compose.runtime.saveable.SaverScope
import com.slipstream.engine.EngineSpec
import com.slipstream.engine.RpcTransport
import com.slipstream.model.CategorySpec
import com.slipstream.model.DirectoryMode
import com.slipstream.model.FileCategories
import com.slipstream.model.FileCategory
import com.slipstream.model.PatternMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsDraftTest {
    private fun spec(custom: Map<String, String> = emptyMap()) =
        EngineSpec(
            dir = "/storage/emulated/0/Download",
            customOptions = custom,
        )

    private fun draft(custom: Map<String, String> = emptyMap()) =
        SettingsDraft.from(
            spec(custom),
            ExtApiSpec(),
            FileCategories.defaults(),
        )

    @Test
    fun extractMergeRoundTripIsIdentity() {
        val custom =
            mapOf(
                "split" to "64",
                "max-connection-per-server" to "16",
                "seed-ratio" to "1.5",
                "enable-dht" to "true",
                "enable-peer-exchange" to "false",
                "bt-tracker" to "udp://a,udp://b",
                "custom-weird" to "keepme",
            )
        assertEquals(custom, draft(custom).mergedOptions())
    }

    @Test
    fun typedKeysStayOutOfResidual() {
        val d = draft(mapOf("split" to "64", "other" to "1"))
        assertEquals("64", d.split)
        assertEquals(mapOf("other" to "1"), d.residual.result())
    }

    @Test
    fun triStateParsesAndBlanksNeverEmit() {
        val d = draft(mapOf("enable-dht" to "true", "bt-enable-lpd" to "false", "enable-peer-exchange" to "weird"))
        assertEquals(true, d.enableDht)
        assertEquals(false, d.enableLpd)
        assertNull(d.enablePeerExchange)
        // Non-conforming tri value is preserved via residual, not dropped.
        assertEquals("weird", d.mergedOptions()["enable-peer-exchange"])
        assertEquals("true", d.mergedOptions()["enable-dht"])
        assertEquals("false", d.mergedOptions()["bt-enable-lpd"])

        val blank = draft()
        assertFalse(blank.mergedOptions().containsKey("enable-dht"))
        blank.enableDht = true
        assertEquals("true", blank.mergedOptions()["enable-dht"])
    }

    @Test
    fun perfPresetWritesTypedFields() {
        val d = draft()
        // Same writes the Downloads-tab preset button performs.
        d.split = "64"
        d.maxConnections = "16"
        d.fileAllocation = "none"
        d.residual.addAll(mapOf("min-split-size" to "1M"))
        assertEquals("64", d.split)
        assertEquals("16", d.maxConnections)
        assertEquals("none", d.fileAllocation)
        // Only the untyped key sits in residual — no shadow state.
        assertEquals(mapOf("min-split-size" to "1M"), d.residual.result())
        val merged = d.mergedOptions()
        assertEquals("64", merged["split"])
        assertEquals("16", merged["max-connection-per-server"])
        assertEquals("none", merged["file-allocation"])
        assertEquals("1M", merged["min-split-size"])
    }

    @Test
    fun toSpecKeepsFieldsAndSwapsOptions() {
        val base = spec(mapOf("split" to "8"))
        val d = SettingsDraft.from(base, ExtApiSpec(), CategorySpec())
        assertEquals("8", d.split)
        d.dlLimit = "100"
        val out = d.toSpec(base)
        assertEquals(base.rpcPort, out.rpcPort)
        assertEquals(100, out.dlLimitKbps)
        assertEquals(d.mergedOptions(), out.customOptions)
    }

    @Test
    fun multiLineTrackersJoinWithCommas() {
        val d = draft()
        d.btTracker = "udp://a\n udp://b \n\nudp://c\n"
        assertEquals("udp://a,udp://b,udp://c", d.mergedOptions()["bt-tracker"])
        // Extracted comma lists stay verbatim (identity).
        val e = draft(mapOf("bt-tracker" to "a,b"))
        assertEquals("a,b", e.mergedOptions()["bt-tracker"])
    }

    @Test
    fun validation() {
        val d = draft()
        assertTrue(d.valid)
        d.rpcPort = "80"
        assertFalse(d.valid)
        d.rpcPort = "6800"
        d.btListenPort = "1023"
        assertFalse(d.btPortValid)
        assertFalse(d.valid)
        d.btListenPort = "6881"
        assertTrue(d.valid)
        d.split = "-1"
        assertFalse(d.valid)
        d.split = "1.5"
        assertFalse(d.valid) // int fields reject decimals
        d.split = ""
        d.seedRatio = "1.5"
        assertTrue(d.valid) // seed-ratio accepts floats
        d.maxTries = "abc"
        assertFalse(d.valid)
    }

    @Test
    fun categoryValidationOnlyWhenEnabled() {
        val bad = FileCategories.BUILTIN.map { it.copy(directory = "../x") }
        val d =
            SettingsDraft.from(
                spec(),
                ExtApiSpec(),
                CategorySpec(enabled = false, categories = bad),
            )
        assertNull(d.categoryError)
        d.categoryEnabled = true
        assertTrue(d.categoryError != null)
        assertFalse(d.valid)
    }

    @Test
    fun allTypedKeysRoundTrip() {
        val custom =
            mapOf(
                "max-concurrent-downloads" to "5",
                "split" to "64",
                "max-connection-per-server" to "16",
                "max-tries" to "3",
                "retry-wait" to "10",
                "file-allocation" to "none",
                "seed-ratio" to "1.5",
                "seed-time" to "120",
                "bt-max-peers" to "80",
                "listen-port" to "6881",
                "bt-tracker" to "udp://a,udp://b",
                "all-proxy" to "http://p:8",
                "connect-timeout" to "10",
                "timeout" to "20",
                "user-agent" to "UA",
                "enable-dht" to "true",
                "enable-peer-exchange" to "false",
                "bt-enable-lpd" to "true",
            )
        assertEquals(custom, draft(custom).mergedOptions())
    }

    @Test
    fun extPortRules() {
        val d = draft()
        // Disabled ext → an invalid port never blocks Save.
        d.extEnabled = false
        d.extPort = "not-a-port"
        assertTrue(d.valid)
        d.extEnabled = true
        assertFalse(d.valid)
        // Equal to the RPC port → conflict.
        d.extPort = d.rpcPort
        assertFalse(d.extPortValid)
        assertFalse(d.valid)
        d.extPort = "6902"
        assertTrue(d.valid)
    }

    @Test
    fun toSpecPreservesOtherEngineFields() {
        val base =
            spec().copy(
                checkCertificate = true,
                saveSession = false,
                rpcListenAll = true,
                rpcAllowOriginAll = true,
                rpcTransport = RpcTransport.HTTP,
                ulLimitKbps = 50,
            )
        val d = SettingsDraft.from(base, ExtApiSpec(), CategorySpec())
        val out = d.toSpec(base)
        assertTrue(out.checkCertificate)
        assertFalse(out.saveSession)
        assertTrue(out.rpcListenAll)
        assertTrue(out.rpcAllowOriginAll)
        assertEquals(RpcTransport.HTTP, out.rpcTransport)
        assertEquals(50, out.ulLimitKbps)
        assertEquals(base.dir, out.dir)
    }

    @Test
    fun extSpecRoundTrip() {
        val d = draft()
        d.extEnabled = false
        d.extPort = "7100"
        d.extToken = "tok"
        d.extListenAll = true
        val out = d.toExtSpec(ExtApiSpec())
        assertFalse(out.enabled)
        assertEquals(7100, out.port)
        assertEquals("tok", out.token)
        assertTrue(out.listenAll)
    }

    @Test
    fun typedFieldWinsOverResidualRow() {
        val d = draft()
        d.residual.addAll(mapOf("split" to "8"))
        d.split = "64"
        assertEquals("64", d.mergedOptions()["split"])
    }

    @Test
    fun numericBounds() {
        val d = draft()
        d.split = "0"
        assertTrue(d.splitValid)
        assertTrue(d.valid)
        d.split = "2147483648" // Int.MAX_VALUE + 1
        assertFalse(d.splitValid)
        assertFalse(d.valid)
    }

    @Test
    fun snapshotRoundTripRestoresEverything() {
        val d = draft(mapOf("split" to "64"))
        d.enableDht = true
        d.enablePeerExchange = false
        // enableLpd stays null (tri-state default).
        d.categories =
            listOf(
                FileCategory(
                    label = "c",
                    extensions = listOf("pdf"),
                    urlPatterns = listOf("x*"),
                    urlPatternMode = PatternMode.REGEX,
                    directory = "Docs",
                    directoryMode = DirectoryMode.ABSOLUTE,
                ),
            )
        // Blank-value rows survive the snapshot (entries, not result()).
        d.residual.add("min-split-size")
        d.residual.addAll(mapOf("custom" to "v"))
        d.rpcToken = "secret"
        d.transport = RpcTransport.HTTP

        val r = SettingsDraft.fromSnapshot(d.snapshot())
        assertEquals(d.defaultDir, r.defaultDir)
        assertEquals(d.rpcPort, r.rpcPort)
        assertEquals("secret", r.rpcToken)
        assertEquals(d.dir, r.dir)
        assertEquals(RpcTransport.HTTP, r.transport)
        assertEquals("64", r.split)
        assertEquals(true, r.enableDht)
        assertEquals(false, r.enablePeerExchange)
        assertNull(r.enableLpd)
        assertEquals(d.categories, r.categories)
        assertEquals(d.categoryEnabled, r.categoryEnabled)
        assertEquals(d.residual.entries.toList(), r.residual.entries.toList())
        assertEquals(d.mergedOptions(), r.mergedOptions())
    }

    @Test
    fun corruptOrOversizedSnapshotsAreDropped() {
        // Bad JSON restores as null → rememberSaveable re-runs the initializer.
        assertNull(SettingsDraft.Saver.restore("{not json"))
        // Over the 200k cap → save() refuses, same fallback.
        val d = draft()
        d.residual.addAll(mapOf("big" to "x".repeat(200_001)))
        assertNull(with(SettingsDraft.Saver) { SaverScope { true }.save(d) })
    }
}
