package com.slipstream

import android.content.ClipData
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slipstream.model.Download
import kotlinx.coroutines.launch

@Composable
internal fun AddDownloadDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onAdd: (List<String>, Map<String, String>) -> Unit,
    onPickTorrent: () -> Unit,
    categorySpec: com.slipstream.model.CategorySpec,
    baseDir: String,
) {
    // Keyed on initialText: a new share/intent prefill must replace the draft,
    // not be swallowed by a remembered stale one.
    var text by remember(initialText) { mutableStateOf(initialText) }
    var options by remember(initialText) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var showOptions by remember(initialText) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val clipScope = rememberCoroutineScope()

    // Live "save to" preview: shows the resolved category dir, or the base
    // dir when nothing classifies — hidden for explicit dir / disabled rules.
    val categoryPreview =
        remember(text, options, categorySpec, baseDir) {
            if (!categorySpec.enabled || !options["dir"].isNullOrBlank()) {
                null
            } else {
                val urls = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                com.slipstream.model.FileCategories
                    .resolveDir(
                        com.slipstream.model.FileCategories
                            .addCandidates(urls, options),
                        categorySpec.categories,
                        baseDir,
                    ) ?: baseDir
            }
        }

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
                categoryPreview?.let { DetailRow(stringResource(R.string.detail_dir), it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        clipScope.launch {
                            clipboard
                                .getClipEntry()
                                ?.clipData
                                ?.getItemAt(0)
                                ?.text
                                ?.let { text = it.toString() }
                        }
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
internal fun DownloadDetailDialog(
    download: Download,
    onDismiss: () -> Unit,
    onEditOptions: () -> Unit,
    onRetry: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboard.current
    // Resolved in composition so the async failure callback below stays
    // configuration-aware (LocalContext.getString is not).
    val shaFailed = stringResource(R.string.sha_failed)
    val shaScope = rememberCoroutineScope()
    var shaResult by remember { mutableStateOf<String?>(null) }
    var shaPct by remember { mutableIntStateOf(-1) } // -1 = idle

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
                if (download.downloadSpeed > 0) {
                    DetailRow(
                        stringResource(R.string.detail_speed),
                        "↓ ${formatSpeed(download.downloadSpeed)}",
                    )
                }
                DetailRow(stringResource(R.string.detail_dir), download.dir)
                download.errorMessage?.let { DetailRow(stringResource(R.string.detail_error), it) }
                when {
                    shaPct >= 0 -> DetailRow("SHA-256", stringResource(R.string.computing, shaPct))
                    shaResult != null ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { DetailRow("SHA-256", shaResult!!) }
                            TextButton(onClick = {
                                shaResult?.let { result ->
                                    shaScope.launch {
                                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("sha256", result)))
                                    }
                                }
                            }) {
                                Text(stringResource(R.string.copy))
                            }
                        }
                }
                if (download.files.size > 1) {
                    Text(
                        stringResource(R.string.detail_files, download.files.size),
                        style = MaterialTheme.typography.titleSmall,
                    )
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
                                        .makeText(ctx, shaFailed, Toast.LENGTH_SHORT)
                                        .show()
                                }
                            }
                        },
                    ) { Text("SHA-256") }
                }
                // Retry needs source URIs; persisted entries have no live gid
                // so option editing is meaningless on them.
                if ((download.status == Download.Status.ERROR || download.persisted) &&
                    download.files.any { it.uris.isNotEmpty() }
                ) {
                    TextButton(onClick = {
                        onRetry()
                        onDismiss()
                    }) {
                        Text(stringResource(R.string.retry))
                    }
                }
                if (!download.persisted &&
                    (
                        download.status == Download.Status.ACTIVE ||
                            download.status == Download.Status.PAUSED ||
                            download.status == Download.Status.WAITING
                    )
                ) {
                    TextButton(onClick = onEditOptions) { Text(stringResource(R.string.edit_options)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            }
        },
    )
}

@Composable
internal fun DeleteConfirmDialog(
    count: Int,
    onDismiss: () -> Unit,
    onDeleteRecords: () -> Unit,
    onDeleteWithFiles: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(pluralStringResource(R.plurals.del_title, count, count)) },
        text = { Text(stringResource(R.string.del_text)) },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDeleteRecords) {
                    Text(stringResource(R.string.del_records_only))
                }
                TextButton(onClick = onDeleteWithFiles) {
                    Text(stringResource(R.string.del_with_files), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
