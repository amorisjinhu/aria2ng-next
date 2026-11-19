package com.slipstream

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.slipstream.model.Download
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class MainActivity : ComponentActivity() {
    /** URLs from external intents, queued for the confirmation dialog. */
    private var pendingAddUrls by mutableStateOf<List<String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val engine = EngineProcess.get(this)
        EngineService.start(this)

        val settingsRepo = SettingsRepo(this)

        setContent {
            val themeMode by settingsRepo.themeMode.collectAsState(initial = 0)
            SlipstreamTheme(themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var screen by remember { mutableStateOf(Screen.List) }
                    var showAbout by remember { mutableStateOf(false) }
                    val drawerState = rememberDrawerState(DrawerValue.Closed)
                    val drawerScope = rememberCoroutineScope()

                    fun go(s: Screen) {
                        screen = s
                        drawerScope.launch { drawerState.close() }
                    }
                    ModalNavigationDrawer(
                        drawerState = drawerState,
                        drawerContent = {
                            // Material rule: screen − 56dp of peeking content, capped at 360dp
                            val drawerWidth =
                                (LocalConfiguration.current.screenWidthDp.dp - 56.dp)
                                    .coerceAtMost(360.dp)
                            ModalDrawerSheet(modifier = Modifier.width(drawerWidth)) {
                                Text(
                                    stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 20.dp),
                                )
                                HorizontalDivider()
                                NavigationDrawerItem(
                                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                    label = { Text(stringResource(R.string.settings)) },
                                    selected = screen == Screen.Settings,
                                    onClick = { go(Screen.Settings) },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                )
                                NavigationDrawerItem(
                                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                    label = { Text(stringResource(R.string.engine_log)) },
                                    selected = screen == Screen.Log,
                                    onClick = { go(Screen.Log) },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                )
                                HorizontalDivider()
                                NavigationDrawerItem(
                                    icon = { Icon(Icons.Default.Info, contentDescription = null) },
                                    label = { Text(stringResource(R.string.about)) },
                                    selected = false,
                                    onClick = {
                                        showAbout = true
                                        drawerScope.launch { drawerState.close() }
                                    },
                                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                )
                            }
                        },
                    ) {
                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                val slide = tween<IntOffset>(220, easing = FastOutSlowInEasing)
                                val fade = tween<Float>(160)
                                if (targetState == Screen.List) {
                                    (slideInHorizontally(slide) { -it } + fadeIn(fade))
                                        .togetherWith(slideOutHorizontally(slide) { it / 3 } + fadeOut(fade))
                                } else {
                                    (slideInHorizontally(slide) { it } + fadeIn(fade))
                                        .togetherWith(slideOutHorizontally(slide) { -it / 3 } + fadeOut(fade))
                                }
                            },
                            label = "screen",
                        ) { s ->
                            when (s) {
                                Screen.List ->
                                    DownloadScreen(
                                        engine = engine,
                                        externalAdd = pendingAddUrls,
                                        onExternalAddHandled = { pendingAddUrls = null },
                                    ) {
                                        drawerScope.launch { drawerState.open() }
                                    }
                                Screen.Settings -> {
                                    BackHandler { screen = Screen.List }
                                    SettingsScreen(settingsRepo) { screen = Screen.List }
                                }
                                Screen.Log -> {
                                    BackHandler { screen = Screen.List }
                                    LogScreen(engine)
                                }
                            }
                        }
                    }

                    if (showAbout) {
                        val engineVersion by engine.engineVersion.collectAsState()
                        val ctx = LocalContext.current
                        val versionName =
                            remember {
                                runCatching {
                                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
                                }.getOrNull() ?: "?"
                            }
                        AlertDialog(
                            onDismissRequest = { showAbout = false },
                            title = { Text(stringResource(R.string.app_name)) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    DetailRow(stringResource(R.string.app_version), versionName)
                                    DetailRow(stringResource(R.string.build_time), BuildConfig.BUILD_TIME_UTC)
                                    DetailRow(
                                        stringResource(R.string.engine_version_label),
                                        engineVersion?.let { stringResource(R.string.aria2_next_version, it) }
                                            ?: stringResource(R.string.engine_stopped),
                                    )
                                    DetailRow("RPC", engine.spec.rpcEndpoint)
                                    DetailRow(stringResource(R.string.detail_dir), engine.spec.dir)
                                    DetailRow(
                                        stringResource(R.string.license_label),
                                        stringResource(R.string.license_engine),
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = { showAbout = false }) {
                                    Text(stringResource(R.string.ok))
                                }
                            },
                        )
                    }
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        // Don't add silently: external apps/webpages can fire these intents at
        // will. Pre-fill the add dialog so the user confirms instead.
        when (intent.action) {
            Intent.ACTION_VIEW ->
                intent.dataString?.let { url -> pendingAddUrls = listOf(url) }
            Intent.ACTION_SEND ->
                intent
                    .getStringExtra(Intent.EXTRA_TEXT)
                    ?.let { text -> URL_REGEX.findAll(text).map { it.value }.toList() }
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { urls -> pendingAddUrls = urls }
        }
    }

    companion object {
        private val URL_REGEX = Regex("""(https?|magnet|ftp|ftps|sftp)://\S+|magnet:\?\S+""")
    }
}

private enum class Screen { List, Settings, Log }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(
    engine: EngineProcess,
    externalAdd: List<String>?,
    onExternalAddHandled: () -> Unit,
    onOpenDrawer: () -> Unit,
) {
    val state by engine.state.collectAsState()
    val downloads by engine.manager.downloads.collectAsState()
    val globalStat by engine.manager.globalStat.collectAsState()
    var addDialogText by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<Download?>(null) }
    var optionsEdit by remember { mutableStateOf<Pair<String, Map<String, String>>?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var deleteAsk by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(externalAdd) {
        if (externalAdd != null) {
            addDialogText = externalAdd.joinToString("\n")
            onExternalAddHandled()
        }
    }

    // Every manager call can throw (Aria2RpcException / Not connected / timeout).
    // rememberCoroutineScope has no exception handler — a bare launch crashes
    // the process. Catch, let cancellation through, and surface the rest.
    fun rpcOp(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { e ->
                if (e is CancellationException) throw e
                snackbarHostState.showSnackbar(
                    context.getString(R.string.rpc_failed, e.message ?: e.toString()),
                )
            }
        }
    }

    fun toggleSel(gid: String) {
        selected = if (gid in selected) selected - gid else selected + gid
    }

    /**
     * Batch delete: remove live tasks, clear the stopped record, and — when
     * [withFiles] — also wipe the payloads + .aria2 control files from disk.
     * Per-gid failures are swallowed so one bad entry can't abort the batch.
     */
    fun deleteBatch(withFiles: Boolean) {
        val targets = downloads.filter { it.gid in selected }
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
                if (withFiles && stopped) deleteDownloadFiles(d)
            }
            if (failed > 0) {
                snackbarHostState.showSnackbar(context.getString(R.string.del_partial, failed))
            }
        }
        selected = emptySet()
        deleteAsk = false
    }

    val speedHistory = remember { androidx.compose.runtime.mutableStateListOf<Long>() }
    LaunchedEffect(globalStat) {
        globalStat?.let {
            speedHistory.add(it.downloadSpeed)
            if (speedHistory.size > 60) speedHistory.removeAt(0)
        }
    }

    val torrentPicker =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            uri?.let {
                rpcOp {
                    val bytes = context.contentResolver.openInputStream(it)!!.readBytes()
                    engine.manager.addTorrent(android.util.Base64.encodeToString(bytes, android.util.Base64.DEFAULT))
                }
            }
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
                            IconButton(onClick = { selected = emptySet() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sel_exit))
                            }
                            Text(stringResource(R.string.sel_count, selected.size), style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { selected = downloads.map { it.gid }.toSet() }) {
                                Text(stringResource(R.string.sel_all))
                            }
                            IconButton(onClick = { deleteAsk = true }) {
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
                            TextButton(onClick = {
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
                                Text(
                                    when (state) {
                                        is EngineProcess.State.Running -> "●"
                                        is EngineProcess.State.Starting -> "…"
                                        else -> "○"
                                    },
                                )
                            }
                        }
                        SpeedGraph(speedHistory, Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 16.dp))
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { addDialogText = "" }) {
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
                            onClick = { if (selected.isNotEmpty()) toggleSel(d.gid) else detail = d },
                            onLongClick = { if (selected.isEmpty()) toggleSel(d.gid) },
                            onPause = { rpcOp { engine.manager.pause(d.gid) } },
                            onResume = { rpcOp { engine.manager.resume(d.gid) } },
                            onRemove = { rpcOp { engine.manager.remove(d.gid) } },
                            onClear = { rpcOp { engine.manager.clearResult(d.gid) } },
                        )
                    }
                }
            }
        }
    }

    addDialogText?.let { initial ->
        AddDownloadDialog(
            initialText = initial,
            onDismiss = { addDialogText = null },
            onAdd = { urls, opts ->
                rpcOp { engine.manager.add(urls, opts.takeIf { it.isNotEmpty() }) }
                addDialogText = null
            },
            onPickTorrent = {
                torrentPicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
                addDialogText = null
            },
        )
    }

    detail?.let { d ->
        DownloadDetailDialog(
            download = d,
            onDismiss = { detail = null },
            onEditOptions = {
                rpcOp { optionsEdit = d.gid to engine.manager.getOption(d.gid) }
            },
        )
    }

    if (deleteAsk) {
        AlertDialog(
            onDismissRequest = { deleteAsk = false },
            title = { Text(stringResource(R.string.del_title, selected.size)) },
            text = { Text(stringResource(R.string.del_text)) },
            confirmButton = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = { deleteBatch(false) }) {
                        Text(stringResource(R.string.del_records_only))
                    }
                    TextButton(onClick = { deleteBatch(true) }) {
                        Text(stringResource(R.string.del_with_files), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteAsk = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    optionsEdit?.let { (gid, opts) ->
        OptionsEditorDialog(
            title = stringResource(R.string.options_dialog_prefix, downloads.find { it.gid == gid }?.name ?: gid.take(8)),
            initial = opts,
            onDismiss = { optionsEdit = null },
            onApply = { map ->
                rpcOp { engine.manager.setOptions(gid, map) }
                optionsEdit = null
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DownloadCard(
    download: Download,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors =
            if (selected) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            } else {
                CardDefaults.cardColors()
            },
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                }
                StatusChip(download.status)
                Spacer(Modifier.width(8.dp))
                Text(
                    download.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            LinearProgressIndicator(
                progress = { download.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "${formatBytes(download.completedLength)} / ${formatBytes(download.totalLength)}" +
                        "  (${(download.progress * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    when (download.status) {
                        Download.Status.ACTIVE ->
                            stringResource(
                                R.string.card_speed_eta,
                                formatSpeed(download.downloadSpeed),
                                formatEta(download.etaSeconds),
                                download.connections,
                            )
                        Download.Status.ERROR -> download.errorMessage ?: stringResource(R.string.error_unknown)
                        else -> statusLabel(download.status)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        when (download.status) {
                            Download.Status.ACTIVE -> MaterialTheme.colorScheme.primary
                            Download.Status.ERROR -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (download.status) {
                    Download.Status.ACTIVE ->
                        IconButton(onClick = onPause) {
                            Text("⏸")
                        }
                    Download.Status.PAUSED ->
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_resume))
                        }
                    else -> {}
                }
                if (download.status == Download.Status.ACTIVE ||
                    download.status == Download.Status.PAUSED ||
                    download.status == Download.Status.WAITING
                ) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_remove))
                    }
                } else {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_clear))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(status: Download.Status) {
    val (bg, fg) =
        when (status) {
            Download.Status.ACTIVE -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
            Download.Status.PAUSED -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
            Download.Status.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            Download.Status.COMPLETE -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            statusLabel(status),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun statusLabel(status: Download.Status): String =
    when (status) {
        Download.Status.ACTIVE -> stringResource(R.string.status_active)
        Download.Status.WAITING -> stringResource(R.string.status_waiting)
        Download.Status.PAUSED -> stringResource(R.string.status_paused)
        Download.Status.ERROR -> stringResource(R.string.status_error)
        Download.Status.COMPLETE -> stringResource(R.string.status_complete)
        Download.Status.REMOVED -> stringResource(R.string.status_removed)
        Download.Status.UNKNOWN -> stringResource(R.string.error_unknown)
    }

@Composable
private fun AddDownloadDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onAdd: (List<String>, Map<String, String>) -> Unit,
    onPickTorrent: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    var options by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showOptions by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_download)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.urls_hint)) },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clipboard.getText()?.text?.let { text = it }
                    }) { Text(stringResource(R.string.paste)) }
                    TextButton(onClick = onPickTorrent) { Text(stringResource(R.string.pick_torrent)) }
                    TextButton(onClick = { showOptions = true }) {
                        Text(
                            if (options.isEmpty()) {
                                stringResource(R.string.options)
                            } else {
                                stringResource(R.string.options_count, options.size)
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                    if (urls.isNotEmpty()) onAdd(urls, options)
                },
            ) { Text(stringResource(R.string.download)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )

    if (showOptions) {
        OptionsEditorDialog(
            title = stringResource(R.string.options_new),
            initial = options,
            onDismiss = { showOptions = false },
            onApply = {
                options = it
                showOptions = false
            },
        )
    }
}

@Composable
private fun SpeedGraph(
    samples: List<Long>,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
    androidx.compose.foundation.Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        val max = samples.maxOrNull()?.coerceAtLeast(1) ?: return@Canvas
        val stepX = size.width / (samples.size - 1)
        val path =
            androidx.compose.ui.graphics
                .Path()
        val fill =
            androidx.compose.ui.graphics
                .Path()
        samples.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - (v.toFloat() / max) * size.height
            if (i == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, size.height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo((samples.size - 1) * stepX, size.height)
        fill.close()
        drawPath(fill, fillColor)
        drawPath(
            path,
            lineColor,
            style =
                androidx.compose.ui.graphics.drawscope
                    .Stroke(width = 2.dp.toPx()),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DownloadDetailDialog(
    download: Download,
    onDismiss: () -> Unit,
    onEditOptions: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shaScope = rememberCoroutineScope()
    var shaResult by remember { mutableStateOf<String?>(null) }
    var shaPct by remember { mutableStateOf(-1) } // -1 = idle

    // Primary payload: first selected file, else first file, else dir/name guess
    val primaryPath =
        download.files.firstOrNull { it.selected && it.path.isNotEmpty() }?.path
            ?: download.files
                .firstOrNull()
                ?.path
                ?.takeIf { it.isNotEmpty() }
            ?: download.name.takeIf { it != download.gid }?.let { "${download.dir}/$it" }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(download.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(stringResource(R.string.detail_status), statusLabel(download.status))
                DetailRow(stringResource(R.string.detail_gid), download.gid)
                DetailRow(
                    stringResource(R.string.detail_progress),
                    "${formatBytes(download.completedLength)} / ${formatBytes(download.totalLength)}",
                )
                if (download.downloadSpeed > 0) DetailRow(stringResource(R.string.detail_speed), "↓ ${formatSpeed(download.downloadSpeed)}")
                DetailRow(stringResource(R.string.detail_dir), download.dir)
                download.errorMessage?.let { DetailRow(stringResource(R.string.detail_error), it) }
                when {
                    shaPct >= 0 -> DetailRow("SHA-256", stringResource(R.string.computing, shaPct))
                    shaResult != null ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { DetailRow("SHA-256", shaResult!!) }
                            TextButton(onClick = { clipboard.setText(AnnotatedString(shaResult!!)) }) {
                                Text(stringResource(R.string.copy))
                            }
                        }
                }
                if (download.files.size > 1) {
                    Text(stringResource(R.string.detail_files, download.files.size), style = MaterialTheme.typography.titleSmall)
                    download.files.take(8).forEach { f ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { openLocalFile(ctx, f.path) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                f.path.substringAfterLast('/'),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${(f.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (download.files.size > 8) {
                        Text(
                            stringResource(R.string.files_more, download.files.size - 8),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (primaryPath != null) {
                    TextButton(onClick = { openLocalFile(ctx, primaryPath) }) {
                        Text(stringResource(R.string.open_file))
                    }
                    TextButton(
                        enabled = shaPct < 0,
                        onClick = {
                            shaScope.launch {
                                shaResult = null
                                shaPct = 0
                                shaResult =
                                    runCatching { sha256Hex(primaryPath) { shaPct = it } }
                                        .getOrNull()
                                shaPct = -1
                                if (shaResult == null) {
                                    Toast
                                        .makeText(ctx, ctx.getString(R.string.sha_failed), Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                    ) { Text("SHA-256") }
                }
                if (download.status == Download.Status.ACTIVE ||
                    download.status == Download.Status.PAUSED ||
                    download.status == Download.Status.WAITING
                ) {
                    TextButton(onClick = onEditOptions) { Text(stringResource(R.string.edit_options)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

/** Open a downloaded file via FileProvider — file:// URIs are banned on API 24+. */
private fun openLocalFile(
    ctx: android.content.Context,
    path: String,
) {
    val f = File(path)
    if (!f.isFile) {
        Toast.makeText(ctx, ctx.getString(R.string.file_missing), Toast.LENGTH_SHORT).show()
        return
    }
    // getUriForFile throws IllegalArgumentException when no <paths> root
    // matches (e.g. a secondary SD-card volume) — it must be inside the guard.
    val uri =
        runCatching {
            androidx.core.content.FileProvider
                .getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
        }.getOrNull()
    if (uri == null) {
        Toast.makeText(ctx, ctx.getString(R.string.open_failed), Toast.LENGTH_SHORT).show()
        return
    }
    val mime =
        android.webkit.MimeTypeMap
            .getSingleton()
            .getMimeTypeFromExtension(f.extension.lowercase())
            ?: "*/*"
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }.onFailure {
        Toast.makeText(ctx, ctx.getString(R.string.no_app), Toast.LENGTH_SHORT).show()
    }
}

/** Streamed SHA-256 — 1 MiB chunks with a progress callback, off the main thread. */
private suspend fun sha256Hex(
    path: String?,
    onProgress: (Int) -> Unit,
): String? =
    withContext(Dispatchers.IO) {
        val f = File(path ?: return@withContext null)
        if (!f.isFile) return@withContext null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val total = f.length().coerceAtLeast(1)
        var done = 0L
        f.inputStream().buffered().use { ins ->
            val buf = ByteArray(1 shl 20)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
                done += n
                onProgress((done * 100 / total).toInt())
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

/**
 * Wipe a download's payload: every listed file + its .aria2 control file,
 * then walk up empty dirs (BT layout) without ever leaving the download dir.
 */
private suspend fun deleteDownloadFiles(d: Download) =
    withContext(Dispatchers.IO) {
        val paths =
            d.files.map { it.path }.filter { it.isNotEmpty() }.ifEmpty {
                listOf(File(d.dir, d.name).path)
            }
        paths.forEach { p ->
            runCatching {
                val f = File(p)
                f.delete()
                File("$p.aria2").delete()
                if (d.dir.isNotEmpty()) {
                    // Canonical paths + trailing separator: "/a/bc" must NOT
                    // match root "/a/b" (bare startsWith would delete a
                    // sibling's empty dirs).
                    val rootPath = File(d.dir).canonicalPath
                    var parent = f.canonicalFile.parentFile
                    while (
                        parent != null &&
                        parent.canonicalPath != rootPath &&
                        parent.canonicalPath.startsWith("$rootPath/")
                    ) {
                        if (!parent.delete()) break // non-empty → stop
                        parent = parent.parentFile
                    }
                }
            }
        }
    }

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

/** Live aria2-next stdout tail — same role as v2rayNG's Logcat entry. */
@Composable
private fun LogScreen(engine: EngineProcess) {
    val log by engine.log.collectAsState()
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    LaunchedEffect(log.size) {
        if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1)
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        Text(
            stringResource(R.string.engine_log),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(log.size) { i ->
                Text(
                    log[i],
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }
}
