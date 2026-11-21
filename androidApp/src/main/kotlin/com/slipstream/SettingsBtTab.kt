package com.slipstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** BT tab: seeding, listen port, trackers, DHT/PEX/LPD tri-states. */
@Composable
internal fun SettingsBtTab(draft: SettingsDraft) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypedOptionField(
                value = draft.seedRatio,
                onValueChange = { draft.seedRatio = it.filter { c -> c.isDigit() || c == '.' }.take(6) },
                label = stringResource(R.string.opt_seed_ratio),
                valid = draft.seedRatioValid,
                numeric = true,
                modifier = Modifier.weight(1f),
            )
            TypedOptionField(
                value = draft.seedTime,
                onValueChange = { draft.seedTime = it.filter { c -> c.isDigit() || c == '.' }.take(7) },
                label = stringResource(R.string.opt_seed_time),
                valid = draft.seedTimeValid,
                numeric = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TypedOptionField(
                value = draft.btMaxPeers,
                onValueChange = { draft.btMaxPeers = it.filter(Char::isDigit).take(4) },
                label = stringResource(R.string.opt_bt_max_peers),
                valid = draft.btMaxPeersValid,
                numeric = true,
                modifier = Modifier.weight(1f),
            )
            TypedOptionField(
                value = draft.btListenPort,
                onValueChange = { draft.btListenPort = it.filter(Char::isDigit).take(5) },
                label = stringResource(R.string.opt_bt_listen_port),
                valid = draft.btPortValid,
                errorText = stringResource(R.string.port_invalid),
                numeric = true,
                modifier = Modifier.weight(1f),
            )
        }
        TypedOptionField(
            value = draft.btTracker,
            onValueChange = { draft.btTracker = it },
            label = stringResource(R.string.opt_bt_tracker),
            hint = stringResource(R.string.opt_bt_tracker_sub),
            singleLine = false,
        )

        TriRow(stringResource(R.string.opt_enable_dht), draft.enableDht) { draft.enableDht = it }
        TriRow(stringResource(R.string.opt_enable_pex), draft.enablePeerExchange) { draft.enablePeerExchange = it }
        TriRow(stringResource(R.string.opt_enable_lpd), draft.enableLpd) { draft.enableLpd = it }
    }
}

/** Label + 3-state chips on one row; wraps to a second line on narrow screens. */
@Composable
private fun TriRow(
    title: String,
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TriStateChips(value, onChange)
    }
}
