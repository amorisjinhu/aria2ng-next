package com.slipstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** Network tab: TLS verification, proxy, timeouts, User-Agent. */
@Composable
internal fun SettingsNetworkTab(draft: SettingsDraft) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingSwitch(
            stringResource(R.string.check_cert),
            stringResource(R.string.check_cert_sub),
            draft.checkCert,
        ) { draft.checkCert = it }
        TypedOptionField(
            value = draft.allProxy,
            onValueChange = { draft.allProxy = it },
            label = stringResource(R.string.opt_all_proxy),
        )
        TypedOptionField(
            value = draft.connectTimeout,
            onValueChange = { draft.connectTimeout = it.filter(Char::isDigit).take(4) },
            label = stringResource(R.string.opt_connect_timeout),
            valid = draft.connectTimeoutValid,
            numeric = true,
        )
        TypedOptionField(
            value = draft.timeout,
            onValueChange = { draft.timeout = it.filter(Char::isDigit).take(4) },
            label = stringResource(R.string.opt_timeout),
            valid = draft.timeoutValid,
            numeric = true,
        )
        TypedOptionField(
            value = draft.userAgent,
            onValueChange = { draft.userAgent = it },
            label = stringResource(R.string.opt_user_agent),
        )
    }
}
