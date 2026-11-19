package com.slipstream.rpc

import com.slipstream.model.Download
import com.slipstream.model.GlobalStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.coroutineContext

/**
 * Keeps a live view of the engine's download list: a 1s poll loop plus an
 * immediate refresh whenever aria2 pushes an on* notification. When the
 * WebSocket drops, the poll loop reconnects with exponential backoff.
 */
class DownloadManager(
    private val clientProvider: () -> Aria2Client,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val logger: (String) -> Unit = {},
) {
    private val client get() = clientProvider()
    private val _downloads = MutableStateFlow<List<Download>>(emptyList())
    val downloads = _downloads.asStateFlow()

    private val _globalStat = MutableStateFlow<GlobalStat?>(null)
    val globalStat = _globalStat.asStateFlow()

    private var pollJob: Job? = null
    private var eventsJob: Job? = null
    private var notifyJob: Job? = null
    private val refreshMutex = Mutex()

    // Every aria2.on* event, forwarded untouched for user-facing alerts.
    // Kept separate from the refresh trigger, which conflates bursts and
    // would drop events needed by notification subscribers.
    private val _events = MutableSharedFlow<JsonObject>(extraBufferCapacity = 64)
    val events = _events.asSharedFlow()

    fun start() {
        if (pollJob?.isActive == true) return
        pollJob =
            scope.launch {
                var backoff = 1_000L
                while (isActive) {
                    if (client.isConnected) {
                        backoff = 1_000L
                        refresh()
                        delay(1_000)
                    } else {
                        runCatching { client.connect() }
                            .onFailure { logger("reconnect failed: ${it.message}") }
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(15_000)
                    }
                }
            }
        eventsJob =
            scope.launch {
                // collectLatest: a burst of on* notifications collapses into one
                // refresh instead of queueing 4 RPCs per event
                client.events.collectLatest { refresh() }
            }
        notifyJob =
            scope.launch {
                client.events.collect { _events.emit(it) }
            }
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        eventsJob?.cancel()
        eventsJob = null
        notifyJob?.cancel()
        notifyJob = null
        _downloads.value = emptyList()
        _globalStat.value = null
    }

    suspend fun refresh() {
        if (!client.isConnected) return
        // Serialized: a slower refresh can't overwrite a newer snapshot
        refreshMutex.withLock {
            try {
                val all = client.tellActive() + client.tellAllWaiting() + client.tellAllStopped()
                val stat = client.getGlobalStat()
                // stop() may have cancelled us during the RPCs and cleared the
                // flows — don't write a stale snapshot back over that clear.
                coroutineContext.ensureActive()
                // A just-removed gid can briefly appear in BOTH a live list
                // and tellStopped during aria2's transition — the UI keys
                // LazyColumn items by gid, so dupes are a hard crash. First
                // occurrence wins → live state beats the stale stopped copy.
                _downloads.value = all.map { Download.fromJson(it) }.distinctBy { it.gid }
                _globalStat.value = stat
            } catch (e: Exception) {
                // ensureActive rethrows CancellationException only when OUR job
                // was cancelled — a TimeoutCancellationException from an inner
                // call() must not kill the caller's coroutine
                coroutineContext.ensureActive()
                logger("refresh failed: ${e.message}")
            }
        }
    }

    suspend fun add(
        uris: List<String>,
        options: Map<String, String>? = null,
    ) {
        client.addUri(
            uris,
            options?.let {
                kotlinx.serialization.json.buildJsonObject {
                    it.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                }
            },
        )
        refresh()
    }

    suspend fun getOption(gid: String): Map<String, String> = client.getOption(gid)

    suspend fun setOptions(
        gid: String,
        options: Map<String, String>,
    ) {
        client.changeOption(gid, options)
        refresh()
    }

    suspend fun addTorrent(torrentBase64: String) {
        client.addTorrent(torrentBase64)
        refresh()
    }

    suspend fun pauseAll() {
        client.pauseAll()
        refresh()
    }

    suspend fun unpauseAll() {
        client.unpauseAll()
        refresh()
    }

    suspend fun pause(gid: String) {
        client.pause(gid)
        refresh()
    }

    suspend fun resume(gid: String) {
        client.unpause(gid)
        refresh()
    }

    suspend fun remove(gid: String) {
        client.remove(gid)
        refresh()
    }

    /** Removes stopped/error results from the list without touching the download file. */
    suspend fun clearResult(gid: String) {
        client.removeDownloadResult(gid)
        refresh()
    }
}
