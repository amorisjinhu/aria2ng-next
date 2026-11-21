package com.slipstream

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** URLs from external intents, queued for the confirmation dialog. */
    private var pendingAddUrls by mutableStateOf<List<String>?>(null)

    /**
     * Fingerprint of the intent whose payload already reached the add dialog —
     * a recreate re-runs onCreate→handleIntent on the SAME intent (rotation)
     * or re-delivers the original (process death), so without this the
     * dialog would resurrect a URL the user already dealt with. A key rather
     * than a flag, so a genuinely NEW share landing next to old saved state
     * still gets handled.
     */
    private var handledIntentKey: String? = null

    private val vm: DownloadViewModel by viewModels { DownloadViewModel.Factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                            // Proportions and style follow v2rayNG's MainDrawer: 75% width,
                            // surface container, a centered 180dp logo header, and a single
                            // full-width divider between the primary and secondary groups.
                            // Items stay unhighlighted (selected=false) like v2rayNG — no
                            // persistent selection pill after navigating.
                            ModalDrawerSheet(
                                modifier = Modifier.fillMaxWidth(0.75f),
                                drawerContainerColor = MaterialTheme.colorScheme.surface,
                                windowInsets = WindowInsets.systemBars,
                            ) {
                                Column(Modifier.verticalScroll(rememberScrollState())) {
                                    Surface(Modifier.fillMaxWidth().height(180.dp)) {
                                        Column(
                                            Modifier.fillMaxSize().padding(16.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center,
                                        ) {
                                            Image(
                                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                                contentDescription = null,
                                                modifier = Modifier.size(120.dp),
                                                colorFilter =
                                                    ColorFilter.tint(
                                                        MaterialTheme.colorScheme.onSurface,
                                                        BlendMode.SrcIn,
                                                    ),
                                            )
                                            Text(
                                                stringResource(R.string.app_name),
                                                style = MaterialTheme.typography.titleLarge,
                                                color = MaterialTheme.colorScheme.onSurface,
                                            )
                                        }
                                    }
                                    NavigationDrawerItem(
                                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                        label = { Text(stringResource(R.string.settings)) },
                                        selected = false,
                                        onClick = { go(Screen.Settings) },
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                    )
                                    NavigationDrawerItem(
                                        icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                                        label = { Text(stringResource(R.string.engine_log)) },
                                        selected = false,
                                        onClick = { go(Screen.Log) },
                                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                                    )
                                    HorizontalDivider(
                                        Modifier.fillMaxWidth(),
                                        thickness = 1.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                    )
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
                                        repo = settingsRepo,
                                        vm = vm,
                                        externalAdd = pendingAddUrls,
                                        onExternalAddHandled = {
                                            pendingAddUrls = null
                                            // The dialog owns the URLs now —
                                            // consume the intent so a recreate
                                            // can't replay it into a second add.
                                            intent?.let {
                                                handledIntentKey = intentKey(it)
                                                it.action = null
                                                it.data = null
                                                it.removeExtra(Intent.EXTRA_TEXT)
                                            }
                                        },
                                        onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                                        onOpenLog = { screen = Screen.Log },
                                    )

                                Screen.Settings -> {
                                    BackHandler { screen = Screen.List }
                                    SettingsScreen(repo = settingsRepo, engine = engine) { screen = Screen.List }
                                }

                                Screen.Log -> {
                                    BackHandler { screen = Screen.List }
                                    LogScreen(engine)
                                }
                            }
                        }
                    }

                    if (showAbout) {
                        AboutDialog(engine = engine, onDismiss = { showAbout = false })
                    }

                    val extPending by engine.extPending.collectAsState()
                    ExtConfirmDialog(engine = engine, pending = extPending)
                }
            }
        }

        handledIntentKey = savedInstanceState?.getString(KEY_INTENT_HANDLED)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Without setIntent, a rotation rebuild replays the ORIGINAL intent in
        // onCreate — the newest shared URL is lost and a stale one resurrects.
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Process death re-delivers the ORIGINAL intent (our in-place consume
        // doesn't survive re-parceling) — the key is the only witness.
        outState.putString(KEY_INTENT_HANDLED, handledIntentKey)
    }

    private fun intentKey(intent: Intent): String =
        listOf(intent.action, intent.dataString, intent.getStringExtra(Intent.EXTRA_TEXT))
            .joinToString("|")

    private fun handleIntent(intent: Intent?) {
        if (intent == null || intentKey(intent) == handledIntentKey) return
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
        private const val KEY_INTENT_HANDLED = "intent_handled"

        // ftps/sftp are not engine-supported schemes — don't offer to add them.
        private val URL_REGEX = Regex("""(https?|magnet|ftp)://\S+|magnet:\?\S+""")
    }
}

private enum class Screen { List, Settings, Log }

@Composable
private fun AboutDialog(
    engine: EngineProcess,
    onDismiss: () -> Unit,
) {
    val engineVersion by engine.engineVersion.collectAsState()
    val ctx = LocalContext.current
    val versionName =
        remember {
            runCatching {
                ctx.packageManager
                    .getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
                    .versionName
            }.getOrNull() ?: "?"
        }
    AlertDialog(
        onDismissRequest = onDismiss,
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
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        },
    )
}
