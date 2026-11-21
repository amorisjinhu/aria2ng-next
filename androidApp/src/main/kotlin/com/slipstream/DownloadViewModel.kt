package com.slipstream

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.slipstream.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * List-screen UI state + download orchestration. Survives configuration
 * changes, so selection and open dialogs no longer reset on rotation.
 * Process-death restoration (SavedStateHandle) is deliberately out of scope.
 */
class DownloadViewModel(
    private val engine: EngineProcess,
    app: Application,
) : AndroidViewModel(app) {
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    private val _addDialogText = MutableStateFlow<String?>(null)
    val addDialogText: StateFlow<String?> = _addDialogText.asStateFlow()

    // Click-time snapshot on purpose: the detail dialog shows the entry as
    // it was when opened, matching the pre-ViewModel behavior.
    private val _detail = MutableStateFlow<Download?>(null)
    val detail: StateFlow<Download?> = _detail.asStateFlow()

    private val _optionsEdit = MutableStateFlow<Pair<String, Map<String, String>>?>(null)
    val optionsEdit: StateFlow<Pair<String, Map<String, String>>?> = _optionsEdit.asStateFlow()

    private val _deleteAsk = MutableStateFlow(false)
    val deleteAsk: StateFlow<Boolean> = _deleteAsk.asStateFlow()

    // Live engine state first, then persisted finished records — aria2 itself
    // forgets stopped results on restart, the store keeps them visible.
    val downloads: StateFlow<List<Download>> =
        combine(engine.manager.downloads, engine.history) { live, hist ->
            live + hist.filter { h -> live.none { it.gid == h.gid } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _scope = MutableStateFlow(TaskScope.ALL)
    val scope: StateFlow<TaskScope> = _scope.asStateFlow()

    val scopedDownloads: StateFlow<List<Download>> =
        combine(downloads, _scope) { list, s -> list.filter { s.matches(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val scopeCounts: StateFlow<Map<TaskScope, Int>> =
        downloads
            .map { scopeCounts(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), scopeCounts(emptyList()))

    fun setScope(s: TaskScope) {
        _scope.value = s
        // Hidden entries must not stay selected — a batch delete on stale
        // gids would wipe tasks the user can't see.
        clearSelection()
    }

    private val _speedHistory = MutableStateFlow<List<Long>>(emptyList())
    val speedHistory: StateFlow<List<Long>> = _speedHistory.asStateFlow()

    // Snackbar texts, resolved here so the UI only shows strings. Best-effort:
    // an unobserved burst drops the oldest instead of stalling the operation.
    private val _events =
        MutableSharedFlow<String>(
            extraBufferCapacity = 8,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            engine.manager.globalStat.collect { stat ->
                stat?.let { _speedHistory.update { h -> (h + it.downloadSpeed).takeLast(60) } }
            }
        }
        // Selection must stay a subset of the visible scope — a task migrating
        // partitions (ACTIVE → COMPLETE) would otherwise leave a stale gid that
        // deleteBatch (which snapshots the full list) would wipe unseen. Prune
        // on EVERY emission, including an empty snapshot: engine stop clears
        // the list → selection mode exits; after a restart the user re-selects.
        // The VM's own subscription keeps scopedDownloads alive (one filter per
        // refresh — acceptable); setScope() still clears eagerly rather than
        // waiting for the next emission.
        viewModelScope.launch {
            scopedDownloads.collect { list ->
                val visible = visibleGids(list)
                _selected.update { pruneSelection(it, visible) }
            }
        }
    }

    // Every manager call can throw (Aria2RpcException / Not connected / timeout).
    // Catch and surface as a snackbar — including INNER timeouts: the client's
    // withTimeout raises TimeoutCancellationException (a CancellationException),
    // but our job is still active, so rethrowing it would kill this coroutine
    // silently and the user gets zero feedback on a hung engine.
    private fun rpcOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e ->
                if (e is CancellationException && !isActive) throw e
                _events.emit(
                    getApplication<Application>().getString(R.string.rpc_failed, e.message ?: e.toString()),
                )
            }
        }
    }

    fun toggleSel(gid: String) {
        _selected.update { if (gid in it) it - gid else it + gid }
    }

    fun selectAll() {
        _selected.value = scopedDownloads.value.map { it.gid }.toSet()
    }

    fun clearSelection() {
        _selected.value = emptySet()
    }

    fun openAddWith(urls: List<String>) {
        _addDialogText.value = urls.joinToString("\n")
    }

    fun dismissAdd() {
        _addDialogText.value = null
    }

    fun showDetail(d: Download) {
        _detail.value = d
    }

    fun dismissDetail() {
        _detail.value = null
    }

    fun askDelete() {
        _deleteAsk.value = true
    }

    fun dismissDelete() {
        _deleteAsk.value = false
    }

    fun dismissOptions() {
        _optionsEdit.value = null
    }

    fun pause(gid: String) = rpcOp { engine.manager.pause(gid) }

    fun resume(gid: String) = rpcOp { engine.manager.resume(gid) }

    fun addUrls(
        urls: List<String>,
        opts: Map<String, String>,
    ) {
        rpcOp {
            var options = opts
            val catSpec = engine.categorySpec
            // An explicit dir always wins over classification — same rule as
            // RayBurst's dirUserModified/rememberedDirectory suppression.
            if (catSpec.enabled && options["dir"].isNullOrBlank()) {
                com.slipstream.model.FileCategories
                    .resolveDir(
                        com.slipstream.model.FileCategories
                            .addCandidates(urls, options),
                        catSpec.categories,
                        engine.spec.dir,
                    )?.let { options = options + ("dir" to it) }
            }
            val gid = engine.manager.add(urls, options.takeIf { it.isNotEmpty() })
            // Keep the request options for the day this task lands in history —
            // a persisted retry needs them (credentials stripped at the store).
            engine.historyStore.rememberOptions(gid, options)
            // Close only on success — a failed add must not eat the draft.
            _addDialogText.value = null
        }
    }

    fun addTorrent(uri: Uri) {
        rpcOp {
            // No file-category routing for torrents: the payload filename is
            // unknown until metadata resolves. RayBurst classifies torrents
            // post-metadata via its BT file selector, which this app lacks.
            // Payload read off the main thread — a multi-MB .torrent must not jank the UI.
            val base64 =
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    val input =
                        app.contentResolver.openInputStream(uri)
                            ?: throw java.io.IOException(app.getString(R.string.torrent_unreadable))
                    val bytes =
                        input.use { ins ->
                            // Bounded read: a mispicked giant file must not OOM the app.
                            val buf = java.io.ByteArrayOutputStream()
                            val chunk = ByteArray(8192)
                            var total = 0
                            while (true) {
                                val n = ins.read(chunk)
                                if (n < 0) break
                                total += n
                                if (total > MAX_TORRENT_BYTES) {
                                    throw java.io.IOException(app.getString(R.string.torrent_too_large))
                                }
                                buf.write(chunk, 0, n)
                            }
                            buf.toByteArray()
                        }
                    // NO_WRAP: DEFAULT inserts newlines the engine may not tolerate.
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            engine.manager.addTorrent(base64)
        }
    }

    fun openOptions(gid: String) {
        rpcOp { _optionsEdit.value = gid to engine.manager.getOption(gid) }
    }

    fun applyOptions(
        gid: String,
        map: Map<String, String>,
    ) {
        rpcOp {
            engine.manager.setOptions(gid, map)
            // changeOption merges — so does the recorded set for a future retry.
            engine.historyStore.rememberOptions(gid, map)
            _optionsEdit.value = null // keep the editor open on failure
        }
    }

    /** aria2 has no retry RPC: clear the dead entry, re-add with its URIs. */
    fun retry(d: Download) {
        rpcOp {
            val uris = d.files.flatMap { it.uris }.distinct()
            if (uris.isEmpty()) {
                _events.emit(getApplication<Application>().getString(R.string.retry_no_source))
                return@rpcOp
            }
            val opts =
                if (d.persisted) {
                    // Records carry add-time options minus credential keys —
                    // referer/out/dir replay, cookies don't. Records from
                    // before this feature have none: restore at least the
                    // target dir and warn that request context can't replay.
                    if (d.options.isEmpty()) {
                        _events.emit(getApplication<Application>().getString(R.string.retry_no_options))
                    }
                    buildMap {
                        putAll(d.options)
                        if (d.dir.isNotBlank()) put("dir", d.dir)
                    }.takeIf { it.isNotEmpty() }
                } else {
                    runCatching { engine.manager.getOption(d.gid) }.getOrNull()
                }
            if (!d.persisted) {
                // error/complete → clear the result; live statuses → remove the task.
                // A failed remove must ABORT: re-adding on top of a live task is a
                // double download of the same URI.
                if (d.status in setOf(Download.Status.ACTIVE, Download.Status.WAITING, Download.Status.PAUSED)) {
                    val removed = runCatching { engine.manager.remove(d.gid) }
                    if (removed.isFailure) {
                        _events.emit(getApplication<Application>().getString(R.string.retry_remove_failed))
                        return@rpcOp
                    }
                } else {
                    runCatching { engine.manager.clearResult(d.gid) }
                }
            }
            engine.evictHistory(d.gid)
            val newGid = engine.manager.add(uris, opts)
            // Carry the options onto the NEW gid — the retried task's own
            // history record will need them for a future persisted retry.
            opts?.let { engine.historyStore.rememberOptions(newGid, it) }
        }
    }

    /**
     * Batch delete: remove live tasks, clear the stopped record, and — when
     * [withFiles] — also wipe the payloads + .aria2 control files from disk.
     * Per-gid failures are swallowed so one bad entry can't abort the batch.
     */
    fun deleteBatch(withFiles: Boolean) {
        val targets = downloads.value.filter { it.gid in _selected.value }
        rpcOp {
            var failed = 0
            var fileProblems = 0
            targets.forEach { d ->
                val live = d.status in setOf(Download.Status.ACTIVE, Download.Status.WAITING, Download.Status.PAUSED)
                // forceRemove, not remove: remove stops active tasks asynchronously
                // (disk flush can take seconds) and the immediate clearResult below
                // would hit "GID not found" → the stopped result survives → the
                // "deleted" task resurrects on the next refresh.
                val stopped =
                    !live ||
                        runCatching { engine.manager.forceRemove(d.gid) }
                            .onFailure { failed++ }
                            .isSuccess
                if (!d.persisted) {
                    if (runCatching { engine.manager.clearResult(d.gid) }.isFailure && live) {
                        // forceRemove registers the result on the engine's next
                        // event-loop tick — one short retry covers the transition.
                        delay(300)
                        runCatching { engine.manager.clearResult(d.gid) }
                    }
                }
                engine.evictHistory(d.gid) // persisted records don't exist aria2-side
                // File wipe is only safe once the task is actually gone —
                // deleting payload out from under a live aria2 write loses data.
                if (withFiles && stopped) fileProblems += deleteDownloadFiles(d)
            }
            if (failed > 0) {
                _events.emit(
                    getApplication<Application>().resources.getQuantityString(R.plurals.del_partial, failed, failed),
                )
            }
            if (fileProblems > 0) {
                _events.emit(
                    getApplication<Application>().resources.getQuantityString(
                        R.plurals.del_files_skipped,
                        fileProblems,
                        fileProblems,
                    ),
                )
            }
        }
        _selected.value = emptySet()
        _deleteAsk.value = false
    }

    companion object {
        private const val MAX_TORRENT_BYTES = 10 * 1024 * 1024

        // Standard factory DSL (lifecycle-viewmodel core; no KTX artifact needed).
        // The initializer's return type registers DownloadViewModel and avoids the
        // unchecked cast a hand-rolled Factory.create would require.
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    DownloadViewModel(EngineProcess.get(app), app)
                }
            }
    }
}
