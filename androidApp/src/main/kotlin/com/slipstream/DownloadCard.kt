package com.slipstream

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.slipstream.model.Download

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DownloadCard(
    download: Download,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
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
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }

            // Fixed-width right slot keeps every card's content edge
            // aligned: one contextual action (pause/resume/retry), a
            // static tick for COMPLETE, or a spacer. Deletion lives in
            // long-press selection mode instead.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when (download.status) {
                    Download.Status.ACTIVE, Download.Status.WAITING ->
                        IconButton(onClick = onPause) {
                            Icon(Icons.Filled.Pause, contentDescription = stringResource(R.string.cd_pause))
                        }
                    Download.Status.PAUSED ->
                        IconButton(onClick = onResume) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_resume))
                        }
                    Download.Status.ERROR ->
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_retry))
                        }
                    Download.Status.COMPLETE ->
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    else -> {}
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
internal fun statusLabel(status: Download.Status): String =
    when (status) {
        Download.Status.ACTIVE -> stringResource(R.string.status_active)
        Download.Status.WAITING -> stringResource(R.string.status_waiting)
        Download.Status.PAUSED -> stringResource(R.string.status_paused)
        Download.Status.ERROR -> stringResource(R.string.status_error)
        Download.Status.COMPLETE -> stringResource(R.string.status_complete)
        Download.Status.REMOVED -> stringResource(R.string.status_removed)
        Download.Status.UNKNOWN -> stringResource(R.string.error_unknown)
    }
