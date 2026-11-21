package com.slipstream

import com.slipstream.model.Download
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlin.time.Duration.Companion.seconds

/** Settings for the browser-extension API — app-level, NOT part of EngineSpec (no engine restart). */
data class ExtApiSpec(
    val enabled: Boolean = true,
    val port: Int = 6902,
    val token: String = "",
    val listenAll: Boolean = false,
)

/** Outcome of a PendingAdd, awaited by the API handler (ack:"final") only. */
sealed interface AddVerdict {
    data class Added(
        val gid: String,
    ) : AddVerdict

    data object Cancelled : AddVerdict

    data class Failed(
        val reason: String,
    ) : AddVerdict
}

/**
 * A download submitted through the Extension API, parked until the user
 * confirms or declines in the app dialog. [options] is the aria2 option map
 * built from the request (out/referer/user-agent/header).
 */
class PendingAdd(
    val url: String,
    val options: Map<String, String>,
    val displayName: String,
    val sizeBytes: Long?,
    val referer: String?,
    val verdict: CompletableDeferred<AddVerdict> = CompletableDeferred(),
    /** One-shot claim: confirm/dismiss/drain CAS on this so a request can't be double-handled. */
    val claimed: java.util.concurrent.atomic.AtomicBoolean =
        java.util.concurrent.atomic
            .AtomicBoolean(false),
    /** Stable identity — notification buttons must act on the item they SHOWED, not the queue head at click time. */
    val id: Long = IDS.incrementAndGet(),
) {
    companion object {
        private val IDS =
            java.util.concurrent.atomic
                .AtomicLong(0)
    }
}

/**
 * Rayburst-style Extension API: a small HTTP server inside the app that
 * browser extensions (or scripts) submit downloads to. Bearer-token auth,
 * CORS-open (any extension origin may call), every submission lands in
 * [EngineProcess.extPending] for user confirmation — nothing is added
 * silently, matching the intent-handling contract.
 *
 * Hand-rolled HTTP on a raw socket: the surface is 4 JSON routes, so a
 * server framework dependency would buy nothing. Connection: close only.
 */
class ExtensionApiServer(
    private val spec: ExtApiSpec,
    private val engine: EngineProcess,
    private val scope: CoroutineScope,
    private val logger: (String) -> Unit,
) {
    private var acceptJob: Job? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    // Loopback is reachable by ANY app on the device — cap concurrent handlers
    // so a slow-loris herd can't exhaust coroutines/sockets.
    private val connLimit = Semaphore(MAX_CONNECTIONS)

    fun start() {
        acceptJob =
            scope.launch(Dispatchers.IO) {
                // v4 loopback: extension apiBase is documented as 127.0.0.1,
                // so an explicit v4 bind avoids localhost→::1 mismatches.
                val bind = if (spec.listenAll) null else InetAddress.getByName("127.0.0.1")
                // A predecessor may hold the port for a few ms after stop()
                // (accept() is uninterruptible; its socket closes when the
                // cancelled loop exits) — retry the bind instead of dying for good.
                var ss: ServerSocket? = null
                var attempts = 0
                while (isActive && ss == null && attempts++ < BIND_RETRIES) {
                    ss =
                        try {
                            ServerSocket(spec.port, 16, bind)
                        } catch (e: SocketException) {
                            delay(BIND_RETRY_MS)
                            null
                        }
                }
                if (ss == null) {
                    if (isActive) logger("ext-api bind failed :${spec.port} — gave up after $BIND_RETRIES attempts")
                    return@launch
                }
                try {
                    ss.use { server ->
                        serverSocket = server
                        logger("ext-api listening on ${if (spec.listenAll) "0.0.0.0" else "127.0.0.1"}:${spec.port}")
                        while (isActive) {
                            val s = server.accept()
                            launch {
                                connLimit.withPermit {
                                    try {
                                        s.use { handle(it) }
                                    } catch (e: InterruptedIOException) {
                                        // header/body read timeout — drop silently
                                    } catch (e: SocketException) {
                                        // client went away mid-request — normal
                                    } catch (e: Exception) {
                                        logger("ext-api: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                } catch (e: SocketException) {
                    if (isActive) logger("ext-api failed :${spec.port} — ${e.message}")
                } catch (e: Exception) {
                    if (isActive) logger("ext-api failed: ${e.message}")
                } finally {
                    serverSocket = null
                }
            }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        // Unblocks a pending accept() — job cancellation alone can't interrupt it
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private suspend fun handle(s: Socket) {
        s.soTimeout = READ_TIMEOUT_MS
        // Buffered: readLineLimited below walks byte-by-byte — unbuffered that
        // is one syscall per byte. The deadline caps the WHOLE request: soTimeout
        // alone resets per read, so a slow-loris drip could hold us forever.
        val input = BufferedInputStream(s.getInputStream(), 8192)
        val out = s.getOutputStream()
        val deadline = System.nanoTime() + REQUEST_DEADLINE_S.seconds.inWholeNanoseconds

        val requestLine = readLineLimited(input, deadline) ?: return
        val parts = requestLine.split(' ')
        if (parts.size < 2) return respond(out, 400, """{"error":"bad-request"}""")
        val method = parts[0].uppercase()
        val path = parts[1].substringBefore('?')

        val headers = HashMap<String, String>(16)
        var rawLines = 0
        while (true) {
            val line = readLineLimited(input, deadline) ?: return
            if (line.isEmpty()) break
            // Raw line cap: the unique-name cap below doesn't count duplicates.
            if (++rawLines > MAX_HEADER_LINES) return respond(out, 431, """{"error":"headers-too-large"}""")
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            if (headers.size > MAX_HEADERS) return respond(out, 431, """{"error":"headers-too-large"}""")
        }

        // DNS-rebinding guard: a hostile page resolving its domain to 127.0.0.1
        // sends Host: attacker.example — only literal loopback/LAN IPs pass.
        val host = headers["host"]?.substringBeforeLast(':')?.trim('[', ']')
        if (host != null && !HOST_LITERAL.matches(host)) {
            return respond(out, 403, """{"error":"forbidden-host"}""")
        }

        if (method == "OPTIONS") {
            out.write(
                (
                    "HTTP/1.1 204 No Content\r\n" + CORS_HEADERS +
                        "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                        "Access-Control-Allow-Headers: authorization, content-type\r\n" +
                        "Access-Control-Max-Age: 86400\r\n" +
                        "Connection: close\r\n\r\n"
                ).toByteArray(),
            )
            out.flush()
            return
        }

        // Unauthenticated discovery, same as the real desktop: Rayburst Connect
        // sends no auth on /ping or /version. We claim product "rayburst" —
        // this server implements its protocol-3 contract, so store-installed
        // Rayburst Connect works with this app unmodified.
        if (method == "GET" && path == "/ping") {
            return respond(
                out,
                200,
                """{"product":"rayburst","status":"ok","version":"${BuildConfig.VERSION_NAME}"}""",
            )
        }
        if (method == "GET" && path == "/version") {
            val phase =
                when (engine.state.value) {
                    is EngineProcess.State.Running -> "running"
                    is EngineProcess.State.Starting -> "starting"
                    is EngineProcess.State.Failed -> "failed"
                    EngineProcess.State.Stopped -> "stopped"
                }
            return respond(out, 200, """{"app":"${BuildConfig.VERSION_NAME}","engine":"$phase"}""")
        }

        // Blank token must fail closed ("" would make "Bearer " authenticate),
        // and the compare is constant-time against timing oracles.
        val expected = "Bearer ${spec.token}".toByteArray(Charsets.UTF_8)
        val actual = headers["authorization"].orEmpty().toByteArray(Charsets.UTF_8)
        if (spec.token.isBlank() || !java.security.MessageDigest.isEqual(expected, actual)) {
            return respond(out, 401, """{"error":"unauthorized"}""")
        }

        if (engine.state.value !is EngineProcess.State.Running) {
            // The extension's 503 taxonomy: starting → it retries,
            // unavailable → the browser keeps the download.
            val code =
                if (engine.state.value is EngineProcess.State.Starting) "engine_starting" else "engine_unavailable"
            return respond(out, 503, """{"error":"$code"}""")
        }

        when {
            method == "GET" && path == "/downloads/capabilities" ->
                respond(
                    out,
                    200,
                    """{"product":"rayburst","protocolVersion":3}""",
                )

            method == "GET" && path == "/stat" -> respond(out, 200, statJson().toString())
            method == "GET" && path == "/tasks" -> respond(out, 200, tasksJson())
            method == "POST" && path == "/add" -> handleAdd(input, out, headers, deadline)
            method == "POST" && path == "/pause-all" -> handleAll(out, pause = true)
            method == "POST" && path == "/resume-all" -> handleAll(out, pause = false)
            method == "POST" && path.startsWith("/tasks/") -> handleTaskOp(out, path)
            else -> respond(out, 404, """{"error":"not-found"}""")
        }
    }

    /**
     * POST /add — Rayburst protocol-3-inspired request. Required: `url`.
     * Optional: finalUrl, referer, cookie, userAgent, filename(+filenameSource
     * "browser"|"suggested"), requestHeaders[{name,value}], mime, size, ack
     * ("early"|"final"). The request is ALWAYS queued for in-app confirmation;
     * `ack` only controls whether the HTTP response waits for the answer.
     */
    private suspend fun handleAdd(
        input: InputStream,
        out: OutputStream,
        headers: Map<String, String>,
        deadline: Long,
    ) {
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0) return respond(out, 411, """{"error":"length-required"}""")
        if (len > MAX_BODY) return respond(out, 413, """{"error":"body-too-large"}""")
        val body = ByteArray(len).also { input.readFully(it, deadline) }

        val obj =
            runCatching { Json.parseToJsonElement(body.decodeToString()).jsonObject }.getOrNull()
                ?: return respond(out, 400, """{"error":"bad-json"}""")

        fun str(key: String) = obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        val url = str("url") ?: return respond(out, 400, """{"error":"url-required"}""")
        if (!SUPPORTED_SCHEME.containsMatchIn(url)) {
            return respond(out, 400, """{"error":"unsupported-scheme"}""")
        }

        // Merge caller headers + cookie/UA/referer into aria2 options.
        // CRLF in a name/value would inject extra header lines into the engine.
        val headerLines = mutableListOf<String>()
        (obj["requestHeaders"] as? JsonArray)?.forEach { h ->
            val o = h as? JsonObject ?: return@forEach
            val n = o["name"]?.jsonPrimitive?.contentOrNull?.trim()
            val v = o["value"]?.jsonPrimitive?.contentOrNull
            if (!n.isNullOrEmpty() && v != null && !n.hasCrlf() && !v.hasCrlf()) headerLines += "$n: $v"
        }
        str("cookie")?.takeIf { !it.hasCrlf() }?.let { headerLines += "Cookie: $it" }

        val opts = mutableMapOf<String, String>()
        if (headerLines.isNotEmpty()) opts["header"] = headerLines.joinToString("\r\n")
        str("userAgent")?.let { opts["user-agent"] = it }
        str("referer")?.let { opts["referer"] = it }
        val filename = str("filename")?.let(::sanitizeFilename)
        // Only a browser-observed name may force `out` — a "suggested" hint must
        // not override the server's Content-Disposition (Rayburst precedence).
        if (filename != null && str("filenameSource") == "browser") opts["out"] = filename

        // verify: browser held the download — prove the engine would receive
        // the same file before asking the user (aria2.probe, first byte with
        // the request's own context). >8s or a transport failure → unreachable.
        // An engine without the method (upstream aria2) skips verification.
        var probeInfo: ProbeInfo? = null
        if (str("verify") == "true" && url.startsWith("http")) {
            when (val r = verifyProbe(url, opts, str("mime"))) {
                is ProbeResult.Mismatch ->
                    return respond(out, 200, """{"action":"fallback","reason":"${r.reason}"}""")

                ProbeResult.Unavailable ->
                    return respond(out, 200, """{"action":"fallback","reason":"unreachable"}""")

                is ProbeResult.Ok -> probeInfo = r.info
            }
        }

        // File categories (post-probe so aria2.probe sees the same options as
        // before): one candidate stands for the whole submission. `dir` never
        // arrives in the request schema, so an enabled rule always applies.
        val catSpec = engine.categorySpec
        if (catSpec.enabled) {
            com.slipstream.model.FileCategories
                .resolveDir(
                    listOf(com.slipstream.model.Candidate(path = filename ?: url, urls = listOf(url))),
                    catSpec.categories,
                    engine.spec.dir,
                )?.let { opts["dir"] = it }
        }

        // Meta enrichment: the engine's probe beats the browser's fields —
        // Rayburst Connect sends `size` as bytes-received-so-far (often KBs
        // on a GB download), while probe.totalLength is authoritative. The
        // engine-resolved filename fills in only when the browser gave none.
        val pending =
            PendingAdd(
                url = url,
                options = opts,
                displayName =
                    filename ?: probeInfo?.filename ?: url
                        .substringAfterLast('/')
                        .substringBefore('?')
                        .ifBlank { url },
                sizeBytes = probeInfo?.totalLength ?: obj["size"]?.jsonPrimitive?.longOrNull,
                referer = str("referer"),
            )
        if (engine.extPending.value.size >= EngineProcess.MAX_EXT_PENDING) {
            return respond(out, 429, """{"error":"queue-full"}""")
        }
        engine.enqueueExtAdd(pending)
        // Query strings can carry credentials — keep them out of the log.
        logger(
            "ext-api add: ${pending.displayName} (${pending.sizeBytes ?: -1}B)" +
                opts["dir"]?.let { " → $it" }.orEmpty() +
                " ← ${url.substringBefore('?')}",
        )

        if (str("ack") != "final") {
            // early: extension drops its download now; the in-app dialog owns it
            return respond(out, 200, """{"action":"accepted"}""")
        }
        // final: hold the answer until the user decides (or timeout → browser
        // keeps the download; the queue entry is withdrawn)
        when (val v = withTimeoutOrNull(CONFIRM_TIMEOUT_MS) { pending.verdict.await() }) {
            is AddVerdict.Added -> respond(out, 200, """{"action":"added","gid":"${v.gid}"}""")
            AddVerdict.Cancelled -> respond(out, 200, """{"action":"cancelled"}""")
            is AddVerdict.Failed -> respond(out, 200, """{"action":"fallback","reason":"rejected"}""")
            null -> {
                // The dialog may have claimed it a hair before the timeout —
                // give the in-flight add a grace window instead of letting the
                // browser resume into a double download.
                val late =
                    if (pending.claimed.get()) {
                        withTimeoutOrNull(CONFIRM_GRACE_MS) { pending.verdict.await() }
                    } else {
                        null
                    }
                when (late) {
                    is AddVerdict.Added -> respond(out, 200, """{"action":"added","gid":"${late.gid}"}""")
                    AddVerdict.Cancelled -> respond(out, 200, """{"action":"cancelled"}""")
                    is AddVerdict.Failed -> respond(out, 200, """{"action":"fallback","reason":"rejected"}""")
                    null -> {
                        engine.dismissExtAdd(pending)
                        respond(out, 200, """{"action":"fallback","reason":"unconfirmed"}""")
                    }
                }
            }
        }
    }

    private fun String.hasCrlf(): Boolean = any { it == '\r' || it == '\n' }

    /**
     * aria2.probe(uri, options) — first byte with the request's own options.
     * Only definitive mismatches fail the handoff; anything ambiguous passes,
     * since "no check" is also a supported mode.
     */
    private data class ProbeInfo(
        val filename: String?,
        val totalLength: Long?,
    )

    private sealed interface ProbeResult {
        data class Ok(
            val info: ProbeInfo,
        ) : ProbeResult

        data object Unavailable : ProbeResult

        data class Mismatch(
            val reason: String,
        ) : ProbeResult
    }

    private suspend fun verifyProbe(
        url: String,
        opts: Map<String, String>,
        mime: String?,
    ): ProbeResult {
        val params =
            kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(url))
                add(buildJsonObject { opts.forEach { (k, v) -> put(k, v) } })
            }
        val probe =
            try {
                withTimeoutOrNull(VERIFY_TIMEOUT_MS) { engine.client.call("aria2.probe", params) }
            } catch (e: com.slipstream.rpc.Aria2Client.Aria2RpcException) {
                // -32601 = method absent (upstream aria2) → unverified add.
                // Any OTHER RPC error is a real probe failure → fallback.
                if (e.code == -32601) {
                    logger("ext-api: probe unsupported RPC-side — skipping verify")
                    return ProbeResult.Ok(ProbeInfo(null, null))
                }
                logger("ext-api: probe failed RPC-side (${e.message})")
                return ProbeResult.Unavailable
            } catch (_: Exception) {
                null
            }
        val o = (probe as? JsonObject) ?: return ProbeResult.Unavailable
        if (o["error"]?.jsonPrimitive?.contentOrNull != null) return ProbeResult.Unavailable

        val ct = o["contentType"]?.jsonPrimitive?.contentOrNull
        // The engine got HTML where the browser got a file — usually an
        // expired session. Saying web-page hands it back to the browser.
        if (ct != null &&
            mime != null &&
            ct.startsWith("text/html") &&
            !mime.startsWith("text/html")
        ) {
            return ProbeResult.Mismatch("web-page")
        }
        // Deliberately no size check: Rayburst Connect's `size` is
        // bytes-received-so-far, not the payload total — comparing it to
        // probe.totalLength false-mismatches every large download.
        val info =
            ProbeInfo(
                filename =
                    o["filename"]?.jsonPrimitive?.contentOrNull?.let(Companion::sanitizeFilename),
                totalLength = o["totalLength"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
            )
        logger("ext-api probe: ct=$ct len=${info.totalLength} name=${info.filename}")
        return ProbeResult.Ok(info)
    }

    /** Rayburst's /stat shape: every counter is a decimal STRING. */
    private fun statJson(): JsonObject {
        val stat = engine.manager.globalStat.value
        val stopped =
            engine.manager.downloads.value.count {
                it.status in setOf(Download.Status.COMPLETE, Download.Status.ERROR, Download.Status.REMOVED)
            }
        return buildJsonObject {
            put("downloadSpeed", (stat?.downloadSpeed ?: 0).toString())
            put("uploadSpeed", (stat?.uploadSpeed ?: 0).toString())
            put("numActive", (stat?.numActive ?: 0).toString())
            put("numWaiting", (stat?.numWaiting ?: 0).toString())
            put("numStopped", (stat?.numStopped ?: stopped).toString())
            // history already folds in this session's finished items — adding
            // numStopped on top would double-count them.
            put(
                "numStoppedTotal",
                engine.history.value.size
                    .toString(),
            )
        }
    }

    private fun tasksJson(): String {
        val all = engine.manager.downloads.value
        val live =
            all.filter { it.status in setOf(Download.Status.ACTIVE, Download.Status.WAITING, Download.Status.PAUSED) }
        // Contract: unfinished in queue order, then the ten latest finished —
        // history included, so /tasks isn't empty right after an engine restart.
        val finished =
            (all - live.toSet()) +
                engine.history.value.filterNot { h -> all.any { it.gid == h.gid } }
        return buildJsonObject {
            put("stat", statJson())
            put(
                "tasks",
                JsonArray(
                    (live + finished.take(10)).map { d: Download ->
                        buildJsonObject {
                            put("gid", d.gid)
                            put("name", d.name)
                            put("status", d.status.name.lowercase())
                            put("totalLength", d.totalLength.toString())
                            put("completedLength", d.completedLength.toString())
                            put("downloadSpeed", d.downloadSpeed.toString())
                            d.errorMessage?.takeIf { it.isNotBlank() }?.let { put("errorMessage", it) }
                        }
                    },
                ),
            )
        }.toString()
    }

    private suspend fun handleTaskOp(
        out: OutputStream,
        path: String,
    ) {
        val m =
            Regex("""^/tasks/([0-9a-fA-F]+)/(pause|resume|reveal)$""").matchEntire(path)
                ?: return respond(out, 404, """{"error":"not-found"}""")
        val (gid, op) = m.destructured
        val ok =
            runCatching {
                when (op) {
                    "pause" -> {
                        engine.manager.pause(gid)
                        true
                    }

                    "resume" -> {
                        engine.manager.resume(gid)
                        true
                    }

                    else -> engine.revealExtTask(gid)
                }
            }.getOrDefault(false)
        respond(
            out,
            200,
            if (ok) """{"status":"ok"}""" else """{"status":"error","error":"op-failed"}""",
        )
    }

    private suspend fun handleAll(
        out: OutputStream,
        pause: Boolean,
    ) {
        runCatching { if (pause) engine.manager.pauseAll() else engine.manager.unpauseAll() }
            .fold(
                { respond(out, 200, """{"status":"ok"}""") },
                { respond(out, 200, """{"status":"error","error":"rpc-failed"}""") },
            )
    }

    private fun respond(
        out: OutputStream,
        code: Int,
        body: String,
    ) {
        val reason =
            when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                404 -> "Not Found"
                411 -> "Length Required"
                413 -> "Content Too Large"
                429 -> "Too Many Requests"
                431 -> "Request Header Fields Too Large"
                500 -> "Internal Server Error"
                503 -> "Service Unavailable"
                else -> code.toString()
            }
        val bytes = body.toByteArray()
        out.write(
            (
                "HTTP/1.1 $code $reason\r\n" + CORS_HEADERS +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
            ).toByteArray(),
        )
        out.write(bytes)
        out.flush()
    }

    private fun InputStream.readFully(
        buf: ByteArray,
        deadline: Long,
    ) {
        var off = 0
        while (off < buf.size) {
            if (System.nanoTime() > deadline) throw SocketTimeoutException("request deadline")
            val n = read(buf, off, buf.size - off)
            if (n < 0) throw SocketException("eof")
            off += n
        }
    }

    /** Reads one CRLF/LF line with a hard cap — protects the parser from junk. */
    private fun readLineLimited(
        input: InputStream,
        deadline: Long,
    ): String? {
        val buf = StringBuilder()
        while (buf.length < MAX_LINE) {
            if (System.nanoTime() > deadline) throw SocketTimeoutException("request deadline")
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return buf.toString().removeSuffix("\r")
            buf.append(b.toChar())
        }
        return null // overlong line
    }

    companion object {
        private const val MAX_LINE = 8192
        private const val MAX_HEADERS = 64
        private const val MAX_HEADER_LINES = 128
        private const val MAX_BODY = 256 * 1024 // Rayburst's body limit
        private const val MAX_CONNECTIONS = 8
        private const val READ_TIMEOUT_MS = 30_000
        private const val REQUEST_DEADLINE_S = 30L
        private const val CONFIRM_TIMEOUT_MS = 120_000L
        private const val CONFIRM_GRACE_MS = 10_000L
        private const val VERIFY_TIMEOUT_MS = 8_000L // Rayburst's probe budget
        private const val BIND_RETRIES = 20
        private const val BIND_RETRY_MS = 300L
        private const val CORS_HEADERS = "Access-Control-Allow-Origin: *\r\n"
        private val SUPPORTED_SCHEME = Regex("""^(https?|ftp|magnet|ed2k|thunder):""", RegexOption.IGNORE_CASE)

        // Loopback names + literal IPv4/IPv6 only — a DNS-rebound Host is a name.
        private val HOST_LITERAL = Regex("""^(localhost|127\.0\.0\.1|::1|(\d{1,3}\.){3}\d{1,3}|[0-9A-Fa-f:]+)$""")

        /**
         * Filename hints cross a trust boundary (browser → engine fs): keep one
         * basename, drop separators/control chars, no path traversal.
         */
        fun sanitizeFilename(raw: String): String? =
            raw
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .replace(Regex("""[\x00-\x1f<>"|?*]"""), "")
                .trim()
                .takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }
}
