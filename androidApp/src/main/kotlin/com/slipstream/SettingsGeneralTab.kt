package com.slipstream

import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** General tab: appearance (instant apply), app/engine info, engine restart. */
@Composable
internal fun SettingsGeneralTab(
    repo: SettingsRepo,
    engine: EngineProcess,
) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSection(stringResource(R.string.appearance))
        val themeMode by repo.themeMode.collectAsState(initial = 0)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                stringResource(R.string.theme_system) to 0,
                stringResource(R.string.theme_light) to 1,
                stringResource(R.string.theme_dark) to 2,
            ).forEach { (label, mode) ->
                FilterChip(
                    selected = themeMode == mode,
                    onClick = { scope.launch { repo.setThemeMode(mode) } },
                    label = { Text(label) },
                )
            }
        }

        SettingSection(stringResource(R.string.about))
        val engineVersion by engine.engineVersion.collectAsState()
        val versionName =
            remember {
                runCatching {
                    ctx.packageManager
                        .getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
                        .versionName
                }.getOrNull() ?: "?"
            }
        DetailRow(stringResource(R.string.app_version), versionName)
        DetailRow(stringResource(R.string.build_time), BuildConfig.BUILD_TIME_UTC)
        DetailRow(
            stringResource(R.string.engine_version_label),
            engineVersion?.let { stringResource(R.string.aria2_next_version, it) }
                ?: stringResource(R.string.engine_stopped),
        )
        DetailRow(
            stringResource(R.string.platform),
            stringResource(R.string.platform_value, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
        )

        // Ordered intent pair: STOP is processed first; START re-runs the
        // deferred spec-apply path and brings the engine back up.
        OutlinedButton(onClick = {
            EngineService.stop(ctx)
            EngineService.start(ctx)
        }) { Text(stringResource(R.string.restart_engine)) }
    }
}
