package com.slipstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.slipstream.engine.RpcTransport

/** Advanced tab: RPC endpoint/transport and the free-form custom-option editor. */
@Composable
internal fun SettingsAdvancedTab(draft: SettingsDraft) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSection(stringResource(R.string.rpc_section))

        // 0-1023 need root on Android; >65535 is invalid — either way the engine
        // fails to bind and the user lands in the Failed state with no clue why
        OutlinedTextField(
            value = draft.rpcPort,
            onValueChange = { draft.rpcPort = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.rpc_port)) },
            isError = !draft.portValid,
            supportingText = {
                if (!draft.portValid) Text(stringResource(R.string.port_invalid))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.rpcToken,
                onValueChange = { draft.rpcToken = it },
                label = { Text(stringResource(R.string.rpc_token)) },
                modifier = Modifier.weight(1f),
            )
            // Persisted tokens survive upgrades on purpose; the dice rotates a
            // known/weak secret on demand. Draft only — persisted on Save.
            val cdRandomToken = stringResource(R.string.cd_random_token)
            IconButton(onClick = { draft.rpcToken = SettingsRepo.randomToken() }) {
                Text(
                    "🎲",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { contentDescription = cdRandomToken },
                )
            }
        }
        // aria2 exposes JSON-RPC over both transports on the same port; this
        // picks which one the app uses. HTTP is stateless — no server push,
        // so engine-event notifications only work over WebSocket.
        Text(stringResource(R.string.rpc_transport), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = draft.transport == RpcTransport.WS,
                onClick = { draft.transport = RpcTransport.WS },
                label = { Text("WebSocket") },
            )
            FilterChip(
                selected = draft.transport == RpcTransport.HTTP,
                onClick = { draft.transport = RpcTransport.HTTP },
                label = { Text("HTTP") },
            )
        }
        Text(
            stringResource(R.string.rpc_transport_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingSwitch(
            stringResource(R.string.listen_all),
            stringResource(R.string.listen_all_sub),
            draft.listenAll,
        ) { draft.listenAll = it }
        SettingSwitch(
            stringResource(R.string.allow_origin),
            stringResource(R.string.allow_origin_sub),
            draft.allowOriginAll,
        ) { draft.allowOriginAll = it }

        SettingSection(stringResource(R.string.custom_options))
        Text(
            stringResource(R.string.forced_params_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Typed-key entries must not be addable here: mergedOptions lets typed
        // fields win, so a residual "split" would either lie in the UI (typed
        // blank → "engine default" shown while the residual value applies) or
        // be silently dropped (typed set → residual ignored).
        OptionsEditor(
            state = draft.residual,
            catalog = OptionCatalog.global,
            excludedKeys = SettingsDraft.TYPED_KEYS,
        )
    }
}
