package com.slipstream

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Extension-API submissions: same confirm-first contract as VIEW/SEND
 * intents — queue head shows as a dialog; the service notification covers
 * the backgrounded case.
 */
@Composable
internal fun ExtConfirmDialog(
    engine: EngineProcess,
    pending: List<PendingAdd>,
) {
    val p = pending.firstOrNull() ?: return
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { engine.dismissExtAdd(p) },
        title = { Text(stringResource(R.string.ext_req_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DetailRow(
                    stringResource(R.string.ext_req_name),
                    p.displayName + (p.sizeBytes?.let { " (${formatBytes(it)})" } ?: ""),
                )
                DetailRow("URL", p.url)
                p.referer?.let { DetailRow(stringResource(R.string.ext_req_referer), it) }
                if (pending.size > 1) {
                    Text(
                        stringResource(R.string.ext_req_more, pending.size - 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        engine.confirmExtAdd(p).onFailure { e ->
                            Toast
                                .makeText(
                                    ctx,
                                    ctx.getString(R.string.rpc_failed, e.message ?: e.toString()),
                                    Toast.LENGTH_LONG,
                                ).show()
                        }
                    }
                },
            ) { Text(stringResource(R.string.download)) }
        },
        dismissButton = {
            TextButton(onClick = { engine.dismissExtAdd(p) }) {
                Text(stringResource(R.string.ext_req_decline))
            }
        },
    )
}
