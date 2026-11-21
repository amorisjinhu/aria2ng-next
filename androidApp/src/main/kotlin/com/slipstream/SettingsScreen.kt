package com.slipstream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Settings destinations — the menu lists them, each opens its own tab file. */
internal enum class SettingsTab(
    val labelRes: Int,
    val subRes: Int,
    val icon: ImageVector,
) {
    GENERAL(R.string.tab_general, R.string.set_sub_general, Icons.Default.Settings),
    DOWNLOADS(R.string.tab_downloads, R.string.set_sub_downloads, Icons.Default.Download),
    BT(R.string.tab_bt, R.string.set_sub_bt, Icons.Default.Hub),
    NETWORK(R.string.tab_network, R.string.set_sub_network, Icons.Default.Public),
    EXT(R.string.tab_ext, R.string.set_sub_ext, Icons.Default.Extension),
    ADVANCED(R.string.tab_advanced, R.string.set_sub_advanced, Icons.Default.Code),
}

/** App-level pages (never restart the engine). */
private val appTabs = listOf(SettingsTab.GENERAL, SettingsTab.EXT)

/** Engine pages (a changed EngineSpec restarts once, via the spec collector). */
private val engineTabs = listOf(SettingsTab.DOWNLOADS, SettingsTab.BT, SettingsTab.NETWORK, SettingsTab.ADVANCED)

/**
 * Master-detail settings: the root is a RikkaHub-style entry list; tapping an
 * entry drills into that page. All editable state lives in a shared
 * [SettingsDraft] remembered across navigation; Save writes each flow only
 * when it actually changed — ext/category writes never reach the engine spec,
 * so the engine restarts only when an EngineSpec field moved.
 */
@Composable
fun SettingsScreen(
    repo: SettingsRepo,
    engine: EngineProcess,
    onBack: () -> Unit,
) {
    val spec by repo.spec.collectAsState(initial = null)
    val extSpec by repo.extSpec.collectAsState(initial = null)
    val catSpec by repo.categorySpec.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    // Save runs in a coroutine; resolve the error template via LocalResources
    // so it tracks configuration changes (LocalContext.getString does not).
    val res = LocalResources.current

    val s = spec ?: return // first DataStore emission hasn't arrived
    val es = extSpec ?: return
    val cs = catSpec ?: return

    // Shared draft: survives tab navigation and rotation via rememberSaveable
    // + a JSON snapshot saver (unchanged inputs restore the snapshot; a fresh
    // spec/ext/category emission rebuilds the draft — same semantics as before).
    val draft = rememberSaveable(s, es, cs, saver = SettingsDraft.Saver) { SettingsDraft.from(s, es, cs) }
    var tab by rememberSaveable { mutableStateOf<SettingsTab?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp),
    ) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val slide = tween<IntOffset>(220, easing = FastOutSlowInEasing)
                val fade = tween<Float>(160)
                // Drill-in: page enters from the right over the menu; back:
                // the menu slides in from the left (same language as the
                // outer screen switcher in MainActivity).
                if (targetState != null) {
                    (slideInHorizontally(slide) { it } + fadeIn(fade))
                        .togetherWith(slideOutHorizontally(slide) { -it / 3 } + fadeOut(fade))
                } else {
                    (slideInHorizontally(slide) { -it } + fadeIn(fade))
                        .togetherWith(slideOutHorizontally(slide) { it / 3 } + fadeOut(fade))
                }
            },
            label = "settingsNav",
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            if (current == null) {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
                    SettingSection(stringResource(R.string.app_section))
                    appTabs.forEach { SettingsEntryRow(it) { tab = it } }
                    SettingSection(stringResource(R.string.engine_section))
                    engineTabs.forEach { SettingsEntryRow(it) { tab = it } }
                }
            } else {
                // Composed only on a sub-page, so back drills up to the menu
                // instead of leaving settings (the outer handler then applies).
                BackHandler { tab = null }
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { tab = null }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back),
                            )
                        }
                        Text(stringResource(current.labelRes), style = MaterialTheme.typography.titleLarge)
                    }
                    Box(Modifier.weight(1f)) {
                        when (current) {
                            SettingsTab.GENERAL -> SettingsGeneralTab(repo, engine)
                            SettingsTab.DOWNLOADS -> SettingsDownloadsTab(draft)
                            SettingsTab.BT -> SettingsBtTab(draft)
                            SettingsTab.NETWORK -> SettingsNetworkTab(draft)
                            SettingsTab.EXT -> SettingsExtTab(draft)
                            SettingsTab.ADVANCED -> SettingsAdvancedTab(draft)
                        }
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                // DataStore I/O can throw (disk full/IO error) — an
                                // uncaught exception crashes the app from a button tap.
                                runCatching {
                                    // Unchanged flows are not rewritten: ext/category
                                    // stores are engine-independent, and an identical
                                    // EngineSpec write would restart for nothing.
                                    val newExt = draft.toExtSpec(es)
                                    if (newExt != es) repo.saveExt(newExt)
                                    val newCat = draft.toCategorySpec()
                                    if (newCat != cs) repo.saveCategories(newCat)
                                    val newSpec = draft.toSpec(s)
                                    if (newSpec != s) repo.save(newSpec)
                                }.onSuccess {
                                    onBack()
                                }.onFailure { e ->
                                    android.widget.Toast
                                        .makeText(
                                            ctx,
                                            res.getString(R.string.save_failed, e.message ?: e.toString()),
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                }
                            }
                        },
                        enabled = draft.valid,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.save_restart)) }
                }
            }
        }
    }
}

/** Menu row: tonal icon circle + title + subtitle, RikkaHub-style. */
@Composable
private fun SettingsEntryRow(
    tab: SettingsTab,
    onClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        onClick = onClick,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        tab.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(tab.labelRes), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(tab.subRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
