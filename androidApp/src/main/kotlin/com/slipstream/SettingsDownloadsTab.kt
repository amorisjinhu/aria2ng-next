package com.slipstream

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Downloads tab: target dir + storage grants, session save, speed limits,
 * typed throughput/retry options, file categories, perf preset.
 */
@Composable
internal fun SettingsDownloadsTab(draft: SettingsDraft) {
    val ctx = LocalContext.current
    var showManager by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = draft.dir,
            onValueChange = { draft.dir = it },
            label = { Text(stringResource(R.string.download_dir)) },
            modifier = Modifier.fillMaxWidth(),
        )
        val isPublicDir = isPublicStoragePath(draft.dir)
        // minSdk 34 ≥ API 30 → "All files access" is the only storage grant for
        // public dirs; the runtime WRITE_EXTERNAL_STORAGE path no longer exists.
        val storageGranted = android.os.Environment.isExternalStorageManager()
        // SAF tree picker → /storage/... path the engine can actually use
        val dirPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let { treeUriToPath(it) }?.let { draft.dir = it }
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { dirPicker.launch(null) }) {
                Text(stringResource(R.string.browse_dir))
            }
            TextButton(onClick = {
                // Current user's public Download dir — user 0 is wrong on
                // secondary users and work profiles.
                draft.dir =
                    java.io
                        .File(
                            android.os.Environment.getExternalStorageDirectory(),
                            "Download",
                        ).absolutePath
                if (!android.os.Environment.isExternalStorageManager()) openAllFilesAccess(ctx)
            }) { Text(stringResource(R.string.use_public_download)) }
            if (isPublicDir && !storageGranted) {
                // Tap-through to the grant page — a bare warning leaves users stuck
                Text(
                    stringResource(R.string.storage_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                    modifier = Modifier.clickable { openAllFilesAccess(ctx) },
                )
            }
        }
        SettingSwitch(
            stringResource(R.string.save_session),
            stringResource(R.string.save_session_sub),
            draft.saveSession,
        ) { draft.saveSession = it }

        SettingSection(stringResource(R.string.speed_limits))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.dlLimit,
                onValueChange = { draft.dlLimit = it.filter(Char::isDigit).take(7) },
                label = { Text(stringResource(R.string.dl_limit)) },
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = draft.ulLimit,
                onValueChange = { draft.ulLimit = it.filter(Char::isDigit).take(7) },
                label = { Text(stringResource(R.string.ul_limit)) },
                keyboardOptions =
                    androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    ),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.limits_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        TypedOptionField(
            value = draft.maxConcurrentDownloads,
            onValueChange = { draft.maxConcurrentDownloads = it.filter(Char::isDigit).take(3) },
            label = stringResource(R.string.opt_max_concurrent),
            valid = draft.maxConcurrentValid,
            numeric = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypedOptionField(
                value = draft.split,
                onValueChange = { draft.split = it.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.opt_split),
                valid = draft.splitValid,
                numeric = true,
                modifier = Modifier.weight(1f),
            )
            TypedOptionField(
                value = draft.maxConnections,
                onValueChange = { draft.maxConnections = it.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.opt_max_connections),
                valid = draft.maxConnectionsValid,
                numeric = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypedOptionField(
                value = draft.maxTries,
                onValueChange = { draft.maxTries = it.filter(Char::isDigit).take(3) },
                label = stringResource(R.string.opt_max_tries),
                valid = draft.maxTriesValid,
                numeric = true,
                modifier = Modifier.weight(1f),
            )
            TypedOptionField(
                value = draft.retryWait,
                onValueChange = { draft.retryWait = it.filter(Char::isDigit).take(4) },
                label = stringResource(R.string.opt_retry_wait),
                valid = draft.retryWaitValid,
                numeric = true,
                modifier = Modifier.weight(1f),
            )
        }
        Text(stringResource(R.string.opt_file_allocation), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.fileAllocation.isEmpty(),
                onClick = { draft.fileAllocation = "" },
                label = { Text(stringResource(R.string.tri_default)) },
            )
            listOf("none", "trunc", "prealloc", "falloc").forEach { v ->
                FilterChip(
                    selected = draft.fileAllocation == v,
                    onClick = { draft.fileAllocation = v },
                    label = { Text(v) },
                )
            }
        }

        SettingSection(stringResource(R.string.cat_manage))
        SettingSwitch(
            stringResource(R.string.cat_enabled),
            stringResource(R.string.cat_enabled_sub),
            draft.categoryEnabled,
        ) { draft.categoryEnabled = it }
        if (draft.categoryEnabled) {
            SettingLink(
                stringResource(R.string.cat_manage),
                pluralStringResource(R.plurals.cat_count, draft.categories.size, draft.categories.size),
            ) { showManager = true }
            draft.categoryError?.let { Text(categoryErrorText(it), color = MaterialTheme.colorScheme.error) }
        }

        // Performance preset writes the TYPED fields (split/-x/file-allocation)
        // — only untyped keys go to residual, or the preset would shadow the
        // typed controls while mergedOptions emitted values they display blank.
        TextButton(onClick = {
            draft.split = "64"
            draft.maxConnections = "16"
            draft.fileAllocation = "none"
            draft.residual.addAll(mapOf("min-split-size" to "1M")) // only untyped keys stay residual
        }) { Text(stringResource(R.string.perf_preset)) }
    }

    if (showManager) {
        FileCategoryManager(
            initial = draft.categories,
            baseDir = draft.dir.ifEmpty { draft.defaultDir },
            onSave = {
                draft.categories = it
                showManager = false
            },
            onDismiss = { showManager = false },
        )
    }
}
