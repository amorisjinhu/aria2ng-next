package com.slipstream

import com.slipstream.model.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists finished download records (complete/error) to disk.
 *
 * aria2 keeps stopped results in memory only (--save-session rescues
 * unfinished tasks, never results) — an engine restart wipes the "done"
 * list. This store merges every live snapshot into history.json so
 * records survive restarts, engine toggles and reboots. Entries are
 * marked persisted=true: their gids are dead engine-side, so only
 * record/file/retry ops make sense on them.
 */
class HistoryStore(
    filesDir: File,
    private val scope: CoroutineScope,
) {
    private val file = File(filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    private val _history = MutableStateFlow(load())
    val history: StateFlow<List<Download>> = _history.asStateFlow()

    private fun load(): List<Download> =
        runCatching {
            if (!file.isFile) return@runCatching emptyList()
            json
                .decodeFromString<List<Download>>(file.readText())
                .map { it.copy(persisted = true) }
        }.getOrDefault(emptyList())

    /** Fold newly-stopped live entries into the store; newest first, capped. */
    fun merge(live: List<Download>) {
        val stopped =
            live.filter {
                it.status == Download.Status.COMPLETE || it.status == Download.Status.ERROR
            }
        if (stopped.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val merged =
                    (stopped + _history.value)
                        .distinctBy { it.gid }
                        .take(MAX_ENTRIES)
                        .map { it.copy(persisted = true) }
                if (merged.map { it.gid } != _history.value.map { it.gid }) {
                    _history.value = merged
                    runCatching { file.writeText(json.encodeToString(merged)) }
                }
            }
        }
    }

    fun evict(gid: String) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                val next = _history.value.filterNot { it.gid == gid }
                if (next.size != _history.value.size) {
                    _history.value = next
                    runCatching { file.writeText(json.encodeToString(next)) }
                }
            }
        }
    }

    private companion object {
        const val MAX_ENTRIES = 300
    }
}
