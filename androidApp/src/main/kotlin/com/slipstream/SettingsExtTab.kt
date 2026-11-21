package com.slipstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Extension tab: browser-extension API port/token/listen + OS-level alert links. */
@Composable
internal fun SettingsExtTab(draft: SettingsDraft) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSwitch(
            stringResource(R.string.ext_enabled),
            stringResource(R.string.ext_enabled_sub),
            draft.extEnabled,
        ) { draft.extEnabled = it }
        if (draft.extEnabled) {
            OutlinedTextField(
                value = draft.extPort,
                onValueChange = { draft.extPort = it.filter(Char::isDigit).take(5) },
                label = { Text(stringResource(R.string.ext_port)) },
                isError = !draft.extPortValid,
                supportingText = {
                    if (!draft.extPortValid) Text(stringResource(R.string.ext_port_invalid))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft.extToken,
                    onValueChange = { draft.extToken = it },
                    label = { Text(stringResource(R.string.ext_token)) },
                    modifier = Modifier.weight(1f),
                )
                val cdExtToken = stringResource(R.string.cd_random_token)
                IconButton(onClick = { draft.extToken = SettingsRepo.randomToken() }) {
                    Text(
                        "🎲",
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.semantics { contentDescription = cdExtToken },
                    )
                }
            }
            SettingSwitch(
                stringResource(R.string.listen_all),
                stringResource(R.string.ext_listen_all_sub),
                draft.extListenAll,
            ) { draft.extListenAll = it }
            // Request alerts live on the OS side — channel toggles plus the
            // OEM-specific "pop up while in background" grant that gates FSI.
            SettingLink(
                stringResource(R.string.ext_notif_alerts),
                stringResource(R.string.ext_notif_alerts_sub),
            ) { openChannelSettings(ctx) }
            SettingLink(
                stringResource(R.string.ext_bg_popup),
                stringResource(R.string.ext_bg_popup_sub),
            ) { openBgPopupSettings(ctx) }
            SettingLink(
                stringResource(R.string.ext_battery),
                stringResource(R.string.ext_battery_sub),
            ) { requestBatteryWhitelist(ctx) }
        }
    }
}
