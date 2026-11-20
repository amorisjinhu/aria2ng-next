package com.slipstream.engine

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The args() contract is the engine's single source of truth: connectivity
 * params are forced LAST and must beat any conflicting custom option.
 */
class EngineSpecTest {
    private fun spec(custom: Map<String, String> = emptyMap()) = EngineSpec(dir = "/data/test", customOptions = custom)

    @Test
    fun forcedParamsBeatCustomOptions() {
        val args =
            spec(
                mapOf(
                    "rpc-listen-port" to "1",
                    "rpc-secret" to "evil",
                    "dir" to "/evil",
                    "enable-rpc" to "false",
                    "daemon" to "true",
                ),
            ).args("/session")
        assertContains(args, "--rpc-listen-port=6802")
        assertContains(args, "--rpc-secret=gbc")
        assertContains(args, "--dir=/data/test")
        assertContains(args, "--enable-rpc=true")
        assertContains(args, "--daemon=false")
        assertFalse(args.any { it == "--rpc-listen-port=1" })
    }

    @Test
    fun customKeyPrefixIsNormalized() {
        val args = spec(mapOf("--split" to "8")).args("/session")
        assertContains(args, "--split=8")
        assertFalse(args.any { it.startsWith("---") })
    }

    @Test
    fun stopWithProcessEmittedOnlyWithPid() {
        assertContains(spec().args("/s", parentPid = 1234), "--stop-with-process=1234")
        assertFalse(spec().args("/s").any { it.startsWith("--stop-with-process") })
    }

    @Test
    fun limitsAlwaysEmittedAndBeatCustomKeys() {
        // 0 must still be emitted (= unlimited) so a stale custom key can't win.
        val zero = spec(mapOf("max-overall-download-limit" to "99K")).args("/s")
        assertContains(zero, "--max-overall-download-limit=0K")
        assertContains(zero, "--max-overall-upload-limit=0K")
        val typed = EngineSpec(dir = "/d", dlLimitKbps = 512, ulLimitKbps = 64).args("/s")
        assertContains(typed, "--max-overall-download-limit=512K")
        assertContains(typed, "--max-overall-upload-limit=64K")
    }

    @Test
    fun caCertificateOnlyWithCheckAndPath() {
        val on = EngineSpec(dir = "/d", checkCertificate = true).args("/s", caCertificatePath = "/ca.pem")
        assertContains(on, "--check-certificate=true")
        assertContains(on, "--ca-certificate=/ca.pem")
        // check on but no bundle: must NOT emit a dangling --ca-certificate
        val noPath = EngineSpec(dir = "/d", checkCertificate = true).args("/s")
        assertFalse(noPath.any { it.startsWith("--ca-certificate") })
        val off = spec().args("/s", caCertificatePath = "/ca.pem")
        assertContains(off, "--check-certificate=false")
        assertFalse(off.any { it.startsWith("--ca-certificate") })
    }

    @Test
    fun sessionParamsFollowSaveSession() {
        val on = spec().args("/s")
        assertContains(on, "--input-file=/s")
        assertContains(on, "--save-session=/s")
        assertContains(on, "--save-session-interval=30")
        val off = EngineSpec(dir = "/d", saveSession = false).args("/s")
        assertFalse(off.any { it.startsWith("--input-file") || it.startsWith("--save-session") })
    }

    @Test
    fun endpointMatchesTransport() {
        assertEquals("ws://localhost:6802/jsonrpc", spec().rpcEndpoint)
        assertEquals(
            "http://localhost:6802/jsonrpc",
            EngineSpec(dir = "/d", rpcTransport = RpcTransport.HTTP).rpcEndpoint,
        )
    }

    @Test
    fun emptyCustomValueBecomesBareFlag() {
        val args = spec(mapOf("bt-save-metadata" to "")).args("/s")
        assertTrue(args.contains("--bt-save-metadata"))
    }
}
