package com.slipstream

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** URLs from external intents, queued for the confirmation dialog. */
    private var pendingAddUrls by mutableStateOf<List<String>?>(null)

    private val vm: DownloadViewModel by viewModels { DownloadViewModel.Factory }

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
                                        vm = vm,
                                        externalAdd = pendingAddUrls,
                                        onExternalAddHandled = { pendingAddUrls = null },
                                        onOpenDrawer = { drawerScope.launch { drawerState.open() } },
                                        onOpenLog = { screen = Screen.Log },
                                    )
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
                        AboutDialog(engine = engine, onDismiss = { showAbout = false })
                    }

                    val extPending by engine.extPending.collectAsState()
                    ExtConfirmDialog(engine = engine, pending = extPending)
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Without setIntent, a rotation rebuild replays the ORIGINAL intent in
        // onCreate — the newest shared URL is lost and a stale one resurrects.
        setIntent(intent)
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
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
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
