@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.slipstream.rpc

import com.slipstream.model.GlobalStat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.atomics.AtomicLong

/**
 * Minimal aria2 JSON-RPC client over WebSocket.
 *
 * Responses are correlated by the `id` we send; frames carrying a `method`
 * field are server notifications (aria2.on* events) and go to [events].
 * In-flight calls hold a [CompletableDeferred] in [pending], registered
 * BEFORE the request is sent — a SharedFlow broadcast would drop responses
 * that land before the caller starts collecting.
 */
class Aria2Client(
    private val url: String,
    private val secret: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var http = HttpClient { install(WebSockets) }

    @Volatile
    private var closed = false
    private val requestIds = AtomicLong(0)

    @Volatile
    private var session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null

    @Volatile
    private var readerJob: Job? = null

    private class PendingCall(
        val session: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        val deferred: CompletableDeferred<JsonObject>,
    )

    // JVM-only type in commonMain — deliberate: the shared module targets
    // Android only today. A common replacement needs a non-suspending lock;
    // a Mutex around call()'s finally-cleanup would leak waiters whenever the
    // caller is cancelled. Revisit via expect/actual if a non-Android target
    // is ever added.
    private val pending = ConcurrentHashMap<Long, PendingCall>()

    // aria2 serves JSON-RPC over both HTTP POST and WebSocket on one port.
    // The endpoint scheme picks the transport; HTTP is stateless (no session,
    // no server push — [events] just never fires).
    private val useHttp = url.startsWith("http")

    private val handler =
        kotlinx.coroutines.CoroutineExceptionHandler { _, _ ->
            // ws reader failures are recoverable — DownloadManager's poll loop
            // notices !isConnected and calls connect() again with backoff
        }

    val events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)

    private val _connected = MutableStateFlow(false)
    val connected = _connected.asStateFlow()
    val isConnected: Boolean
        get() =
            if (useHttp) {
                _connected.value && !closed
            } else {
                session?.isActive == true
            }

    suspend fun connect() {
        if (closed) { // a previous close() destroyed the engine — recreate
            http = HttpClient { install(WebSockets) }
            closed = false
        }
        if (useHttp) {
            // Nothing persistent to establish — per-request failure surfaces in call()
            _connected.value = true
            return
        }
        readerJob?.cancel()
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "reconnect"))
        val s = http.webSocketSession(url)
        session = s
        _connected.value = true
        readerJob =
            scope.launch(handler) {
                try {
                    for (frame in s.incoming) {
                        if (frame !is Frame.Text) continue
                        val obj =
                            runCatching { json.parseToJsonElement(frame.readText()).jsonObject }
                                .getOrNull() ?: continue

                        if (obj.containsKey("method")) {
                            events.emit(obj)
                        } else {
                            val respId = obj["id"]?.jsonPrimitive?.content?.toLongOrNull()
                            val waiter = respId?.let { pending.remove(it) }
                            // unmatched response (arrived after our timeout) — drop
                            waiter?.deferred?.complete(obj)
                        }
                    }
                } finally {
                    // only tear down state we own — a newer connect() may have swapped session
                    if (session === s) {
                        session = null
                        _connected.value = false
                    }
                    failPending(s, IllegalStateException("Disconnected"))
                }
            }
    }

    suspend fun call(
        method: String,
        params: JsonArray = JsonArray(emptyList()),
    ): JsonElement {
        val id = requestIds.addAndFetch(1)

        val fullParams =
            buildJsonArray {
                add(JsonPrimitive("token:$secret"))
                params.forEach { add(it) }
            }

        val request =
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id.toString())
                put("method", method)
                put("params", fullParams)
            }

        val response =
            if (useHttp) {
                httpRoundTrip(request)
            } else {
                wsRoundTrip(request, id)
            }

        val error = response["error"]?.jsonObject
        if (error != null) {
            throw Aria2RpcException(
                error["code"]?.jsonPrimitive?.int ?: -1,
                error["message"]?.jsonPrimitive?.content ?: "unknown error",
            )
        }

        return response["result"] ?: JsonNull
    }

    private suspend fun wsRoundTrip(
        request: JsonObject,
        id: Long,
    ): JsonObject {
        // Wait out a cold-start race: caller may fire before connect() lands
        val s =
            session ?: withTimeout(10_000) {
                connected.first { it }
                session
            }
                ?: throw IllegalStateException("Not connected")

        val waiter = CompletableDeferred<JsonObject>()
        pending[id] = PendingCall(s, waiter)
        try {
            s.send(Frame.Text(request.toString()))
            return withTimeout(10_000) { waiter.await() }
        } finally {
            pending.remove(id)
        }
    }

    private suspend fun httpRoundTrip(request: JsonObject): JsonObject {
        val response =
            withTimeout(10_000) {
                http.post(url) {
                    setBody(TextContent(request.toString(), ContentType.Application.Json))
                }
            }
        return json.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    suspend fun getVersion(): Version {
        val result = call("aria2.getVersion").jsonObject
        return Version(
            version = result["version"]!!.jsonPrimitive.content,
            enabledFeatures =
                result["enabledFeatures"]!!.let { f ->
                    (f as JsonArray).map { it.jsonPrimitive.content }
                },
        )
    }

    suspend fun addUri(
        uris: List<String>,
        options: JsonObject? = null,
        position: Int? = null,
    ): String {
        val params =
            buildJsonArray {
                add(buildJsonArray { uris.forEach { add(JsonPrimitive(it)) } })
                if (options != null || position != null) add(options ?: buildJsonObject {})
                if (position != null) add(JsonPrimitive(position))
            }
        return call("aria2.addUri", params).jsonPrimitive.content
    }

    suspend fun addTorrent(
        torrentBase64: String,
        uris: List<String>? = null,
        options: JsonObject? = null,
        position: Int? = null,
    ): String {
        val params =
            buildJsonArray {
                add(JsonPrimitive(torrentBase64))
                if (uris != null) add(buildJsonArray { uris.forEach { add(JsonPrimitive(it)) } })
                if (options != null || position != null) add(options ?: buildJsonObject {})
                if (position != null) add(JsonPrimitive(position))
            }
        return call("aria2.addTorrent", params).jsonPrimitive.content
    }

    suspend fun tellStatus(gid: String): JsonObject = call("aria2.tellStatus", gidParams(gid)).jsonObject

    suspend fun tellActive(): List<JsonObject> =
        call("aria2.tellActive", buildJsonArray { add(STATUS_KEYS) })
            .jsonArray
            .map { it.jsonObject }

    suspend fun tellWaiting(
        offset: Int = 0,
        num: Int = 100,
    ): List<JsonObject> = call("aria2.tellWaiting", pageParams(offset, num)).jsonArray.map { it.jsonObject }

    suspend fun tellStopped(
        offset: Int = 0,
        num: Int = 100,
    ): List<JsonObject> = call("aria2.tellStopped", pageParams(offset, num)).jsonArray.map { it.jsonObject }

    suspend fun getGlobalStat(): GlobalStat = GlobalStat.fromJson(call("aria2.getGlobalStat").jsonObject)

    suspend fun getOption(gid: String): Map<String, String> =
        call("aria2.getOption", gidParams(gid))
            .jsonObject
            .mapValues { it.value.jsonPrimitive.content }

    suspend fun changeOption(
        gid: String,
        options: Map<String, String>,
    ) {
        call(
            "aria2.changeOption",
            buildJsonArray {
                add(JsonPrimitive(gid))
                add(buildJsonObject { options.forEach { (k, v) -> put(k, v) } })
            },
        )
    }

    suspend fun pause(gid: String) {
        call("aria2.pause", gidParams(gid))
    }

    suspend fun unpause(gid: String) {
        call("aria2.unpause", gidParams(gid))
    }

    suspend fun remove(gid: String) {
        call("aria2.remove", gidParams(gid))
    }

    suspend fun forceRemove(gid: String) {
        call("aria2.forceRemove", gidParams(gid))
    }

    suspend fun removeDownloadResult(gid: String) {
        call("aria2.removeDownloadResult", gidParams(gid))
    }

    suspend fun pauseAll() {
        call("aria2.pauseAll")
    }

    suspend fun unpauseAll() {
        call("aria2.unpauseAll")
    }

    private fun gidParams(gid: String) = buildJsonArray { add(JsonPrimitive(gid)) }

    private fun pageParams(
        offset: Int,
        num: Int,
    ) = buildJsonArray {
        add(JsonPrimitive(offset))
        add(JsonPrimitive(num))
        add(STATUS_KEYS)
    }

    suspend fun close() {
        closed = true
        _connected.value = false
        readerJob?.cancel()
        session?.close(CloseReason(CloseReason.Codes.NORMAL, "closing"))
        session = null
        http.close()
        pending.values.forEach { it.deferred.completeExceptionally(IllegalStateException("Client closed")) }
        pending.clear()
    }

    /** Fail every waiter whose request went out on session [s] (dead by now). */
    private fun failPending(
        s: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        cause: Throwable,
    ) {
        for (e in pending.entries) {
            if (e.value.session === s && pending.remove(e.key, e.value)) {
                e.value.deferred.completeExceptionally(cause)
            }
        }
    }

    data class Version(
        val version: String,
        val enabledFeatures: List<String>,
    )

    class Aria2RpcException(
        val code: Int,
        message: String,
    ) : Exception("aria2 error $code: $message")

    companion object {
        private val STATUS_KEYS =
            JsonArray(
                listOf(
                    "gid",
                    "status",
                    "totalLength",
                    "completedLength",
                    "downloadSpeed",
                    "uploadSpeed",
                    "connections",
                    "dir",
                    "errorCode",
                    "errorMessage",
                    "bittorrent",
                    "files",
                ).map(::JsonPrimitive),
            )
    }
}
