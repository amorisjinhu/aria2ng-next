package com.slipstream

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun DownloadScreen(
    engine: EngineProcess,
    vm: DownloadViewModel,
    externalAdd: List<String>?,
    onExternalAddHandled: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val state by engine.state.collectAsState()
    val downloads by vm.downloads.collectAsState()
    val globalStat by engine.manager.globalStat.collectAsState()
    val selected by vm.selected.collectAsState()
    val addDialogText by vm.addDialogText.collectAsState()
    val detail by vm.detail.collectAsState()
    val optionsEdit by vm.optionsEdit.collectAsState()
    val deleteAsk by vm.deleteAsk.collectAsState()
    val speedHistory by vm.speedHistory.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(externalAdd) {
        if (externalAdd != null) {
            vm.openAddWith(externalAdd)
            onExternalAddHandled()
        }
    }

    LaunchedEffect(Unit) {
        vm.events.collect { snackbarHostState.showSnackbar(it) }
    }

    val torrentPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let { vm.addTorrent(it) }
        }

    if (Build.VERSION.SDK_INT >= 33) {
        val launcher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) {}
        LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Surface(shadowElevation = 4.dp, modifier = Modifier.statusBarsPadding()) {
                Column {
                    if (selected.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { vm.clearSelection() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sel_exit))
                            }
                            Text(stringResource(R.string.sel_count, selected.size), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { vm.selectAll() }) {
                                Text(stringResource(R.string.sel_all))
                            }
                            IconButton(onClick = { vm.askDelete() }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_remove))
                            }
                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onOpenDrawer) {
                                Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.cd_menu))
                            }
                            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.width(16.dp))
                            globalStat?.let {
                                Text(
                                    stringResource(R.string.speed_line, formatSpeed(it.downloadSpeed), formatSpeed(it.uploadSpeed)),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = {
                                // Route through the service: it owns wantRunning
                                // + the foreground state — a bare engine.stop()
                                // leaves the FGS spinning and lets a STICKY
                                // restart resurrect the engine.
                                if (state is EngineProcess.State.Running) {
                                    EngineService.stop(context)
                                } else {
                                    EngineService.start(context)
                                }
                            }) {
                                when (state) {
                                    is EngineProcess.State.Running ->
                                        Icon(
                                            Icons.Filled.Circle,
                                            contentDescription = null,
                                            tint = Color(0xFF4CAF50),
                                            modifier = Modifier.size(12.dp),
                                        )
                                    is EngineProcess.State.Starting ->
                                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    else ->
                                        Icon(
                                            Icons.Outlined.Circle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(12.dp),
                                        )
                                }
                            }
                        }
                        SpeedGraph(speedHistory, Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.openAddWith(emptyList()) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cd_add))
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (downloads.isEmpty()) {
                Text(
                    stringResource(R.string.empty_list_hint),
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(downloads, key = { it.gid }) { d ->
                        DownloadCard(
                            modifier = Modifier.animateItem(),
                            download = d,
                            selected = d.gid in selected,
                            // Long-press enters selection mode; in selection
                            // mode taps toggle instead of opening the detail
                            onClick = { if (selected.isNotEmpty()) vm.toggleSel(d.gid) else vm.showDetail(d) },
                            onLongClick = { if (selected.isEmpty()) vm.toggleSel(d.gid) },
                            onPause = { vm.pause(d.gid) },
                            onResume = { vm.resume(d.gid) },
                            onRetry = { vm.retry(d) },
                        )
                    }
                }
            }
        }
    }

    addDialogText?.let { initial ->
        AddDownloadDialog(
            initialText = initial,
            onDismiss = { vm.dismissAdd() },
            onAdd = { urls, opts -> vm.addUrls(urls, opts) },
            onPickTorrent = {
                torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
                vm.dismissAdd()
            },
        )
    }

    detail?.let { d ->
        DownloadDetailDialog(
            download = d,
            onDismiss = { vm.dismissDetail() },
            onEditOptions = { vm.openOptions(d.gid) },
            onRetry = { vm.retry(d) },
        )
    }

    if (deleteAsk) {
        DeleteConfirmDialog(
            count = selected.size,
            onDismiss = { vm.dismissDelete() },
            onDeleteRecords = { vm.deleteBatch(false) },
            onDeleteWithFiles = { vm.deleteBatch(true) },
        )
    }

    optionsEdit?.let { (gid, opts) ->
        OptionsEditorDialog(
            title = stringResource(R.string.options_dialog_prefix, downloads.find { it.gid == gid }?.name ?: gid.take(8)),
            initial = opts,
            onDismiss = { vm.dismissOptions() },
            onApply = { map -> vm.applyOptions(gid, map) },
        )
    }
}
