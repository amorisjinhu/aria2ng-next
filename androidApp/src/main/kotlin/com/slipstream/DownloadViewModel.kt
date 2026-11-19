package com.slipstream

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.slipstream.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
    }

    // Every manager call can throw (Aria2RpcException / Not connected / timeout).
    // Catch, let cancellation through, and surface the rest as a snackbar.
    private fun rpcOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e ->
                if (e is CancellationException) throw e
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
        _selected.value = downloads.value.map { it.gid }.toSet()
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
        rpcOp { engine.manager.add(urls, opts.takeIf { it.isNotEmpty() }) }
        _addDialogText.value = null
    }

    fun addTorrent(uri: Uri) {
        rpcOp {
            // Payload read off the main thread — a multi-MB .torrent must not jank the UI.
            val base64 =
                withContext(Dispatchers.IO) {
                    val bytes = getApplication<Application>().contentResolver.openInputStream(uri)!!.readBytes()
                    android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT)
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
        rpcOp { engine.manager.setOptions(gid, map) }
        _optionsEdit.value = null
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
                    null
                } else {
                    runCatching { engine.manager.getOption(d.gid) }.getOrNull()
                }
            if (!d.persisted) {
                // error/complete → clear the result; live statuses → remove the task
                if (d.status in setOf(Download.Status.ACTIVE, Download.Status.WAITING, Download.Status.PAUSED)) {
                    runCatching { engine.manager.remove(d.gid) }
                } else {
                    runCatching { engine.manager.clearResult(d.gid) }
                }
            }
            engine.evictHistory(d.gid)
            engine.manager.add(uris, opts)
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
            targets.forEach { d ->
                val live = d.status in setOf(Download.Status.ACTIVE, Download.Status.WAITING, Download.Status.PAUSED)
                // File wipe is only safe once the task is actually gone —
                // deleting payload out from under a live aria2 write loses data.
                val stopped =
                    !live ||
                        runCatching { engine.manager.remove(d.gid) }
                            .onFailure { failed++ }
                            .isSuccess
                runCatching { engine.manager.clearResult(d.gid) }
                engine.evictHistory(d.gid) // persisted records don't exist aria2-side
                if (withFiles && stopped) deleteDownloadFiles(d)
            }
            if (failed > 0) {
                _events.emit(getApplication<Application>().getString(R.string.del_partial, failed))
            }
        }
        _selected.value = emptySet()
        _deleteAsk.value = false
    }

    companion object {
        // Hand-rolled factory: the viewModelFactory DSL lives in lifecycle-viewmodel-ktx,
        // which this app doesn't depend on — the core Factory API is enough.
        val Factory: ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras,
                ): T {
                    val app = extras[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]!!
                    return DownloadViewModel(EngineProcess.get(app), app) as T
                }
            }
    }
}
