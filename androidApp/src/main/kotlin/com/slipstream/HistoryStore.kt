package com.slipstream

import com.slipstream.model.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persists finished download records (complete/error) to disk.
 *
 * aria2 keeps stopped results in memory only (--save-session rescues
 * unfinished tasks, never results) — an engine restart wipes the "done"
 * list. This store merges every live snapshot into history.json so
 * records survive restarts, engine toggles and reboots. Entries are
 * marked persisted=true: their gids are dead engine-side, so only
 * record/file/retry ops make sense on them.
 *
 * All mutations run on ONE thread in submission order: load lands before
 * any merge, and an evict can't be overtaken by a queued merge holding a
 * stale snapshot (which would resurrect the deleted record). Tombstones
 * additionally filter gids out of later merges — a snapshot captured
 * before the evict may still carry the gid.
 */
class HistoryStore(
    filesDir: File,
    private val scope: CoroutineScope,
) {
    private val file = File(filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val tombstones = LinkedHashSet<String>()

    // Add-time options per live gid, consulted when a stopped entry folds
    // into history. Only touched on [dispatcher]; capped like the store.
    private val optionsByGid = LinkedHashMap<String, Map<String, String>>()

    private val _history = MutableStateFlow<List<Download>>(emptyList())
    val history: StateFlow<List<Download>> = _history.asStateFlow()

    init {
        // Off the main thread: construction happens on the caller's thread
        // (MainActivity/EngineService onCreate) — disk I/O there is a
        // StrictMode violation and jank on a full 300-entry file.
        scope.launch(dispatcher) { _history.value = load() }
    }

    private fun load(): List<Download> =
        runCatching {
            if (!file.isFile) return@runCatching emptyList()
            json
                .decodeFromString<List<Download>>(file.readText())
                .map { it.copy(persisted = true) }
        }.getOrDefault(emptyList())

    /**
     * Records the request options a live gid was added with, so its eventual
     * persisted record can replay them on retry. Callers may pass the raw
     * option map — credential-bearing keys (header lines carry cookies, the
     * passwd/private-key family holds secrets) are stripped here: history.json
     * is an unencrypted file and credentials must never sit at rest in it.
     * Repeat calls merge (aria2.changeOption-style: new keys win).
     */
    fun rememberOptions(
        gid: String,
        opts: Map<String, String>,
    ) {
        val clean = opts.filterKeys { !SECRET_OPTION.containsMatchIn(it) }
        if (clean.isEmpty()) return
        scope.launch(dispatcher) {
            optionsByGid[gid] = (optionsByGid[gid] ?: emptyMap()) + clean
            while (optionsByGid.size > MAX_ENTRIES) {
                optionsByGid.remove(optionsByGid.keys.first())
            }
        }
    }

    /** Fold newly-stopped live entries into the store; newest first, capped. */
    fun merge(live: List<Download>) {
        val stopped =
            live.filter {
                it.status == Download.Status.COMPLETE || it.status == Download.Status.ERROR
            }
        if (stopped.isEmpty()) return
        scope.launch(dispatcher) {
            val merged =
                (stopped.filter { it.gid !in tombstones } + _history.value)
                    .distinctBy { it.gid }
                    .take(MAX_ENTRIES)
                    .map { it.copy(persisted = true, options = optionsByGid[it.gid] ?: it.options) }
            if (merged.map { it.gid } != _history.value.map { it.gid }) {
                _history.value = merged
                persist(merged)
            }
        }
    }

    fun evict(gid: String) {
        scope.launch(dispatcher) {
            tombstones.add(gid)
            while (tombstones.size > MAX_ENTRIES) tombstones.remove(tombstones.first())
            // A stale sidecar entry must not ride onto a FUTURE download that
            // happens to reuse this gid (aria2 recycles counters on restart).
            optionsByGid.remove(gid)
            val next = _history.value.filterNot { it.gid == gid }
            if (next.size != _history.value.size) {
                _history.value = next
                persist(next)
            }
        }
    }

    /**
     * temp + atomic move: a process kill mid-write can never truncate the
     * live file. (writeText in place used to leave half a JSON doc → load
     * silently returned empty → the whole history vanished without a trace.)
     */
    private fun persist(entries: List<Download>) {
        runCatching {
            val text = json.encodeToString(entries)
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(text)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private companion object {
        const val MAX_ENTRIES = 300

        // Option keys whose values are credentials — never written to history.json.
        private val SECRET_OPTION = Regex("""header|passwd|private-key|secret""", RegexOption.IGNORE_CASE)
    }
}
