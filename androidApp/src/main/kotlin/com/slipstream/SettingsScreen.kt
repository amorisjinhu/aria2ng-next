package com.slipstream

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.slipstream.engine.EngineSpec
import com.slipstream.engine.RpcTransport
import kotlinx.coroutines.launch

/**
 * Engine settings screen. Saving writes all fields to DataStore in one edit;
 * EngineService's debounced collector then restarts the engine with the new
 * EngineSpec — the single-source-of-truth contract from the legacy fixes.
 */
@Composable
fun SettingsScreen(
    repo: SettingsRepo,
    onBack: () -> Unit,
) {
    val spec by repo.spec.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    val s = spec ?: return // first DataStore emission hasn't arrived

    var port by remember(s) { mutableStateOf(s!!.rpcPort.toString()) }
    var token by remember(s) { mutableStateOf(s!!.rpcToken) }
    var dir by remember(s) { mutableStateOf(s!!.dir) }
    var listenAll by remember(s) { mutableStateOf(s!!.rpcListenAll) }
    var allowOriginAll by remember(s) { mutableStateOf(s!!.rpcAllowOriginAll) }
    var transport by remember(s) { mutableStateOf(s!!.rpcTransport) }
    var checkCert by remember(s) { mutableStateOf(s!!.checkCertificate) }
    var saveSession by remember(s) { mutableStateOf(s!!.saveSession) }
    var dlLimit by remember(s) { mutableStateOf(s!!.dlLimitKbps.takeIf { it > 0 }?.toString() ?: "") }
    var ulLimit by remember(s) { mutableStateOf(s!!.ulLimitKbps.takeIf { it > 0 }?.toString() ?: "") }
    val customOptions = remember(s) { OptionsEditorState(s!!.customOptions) }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)

        Text(stringResource(R.string.appearance), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
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

        Text(stringResource(R.string.rpc_section), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)

        // 0-1023 need root on Android; >65535 is invalid — either way the engine
        // fails to bind and the user lands in the Failed state with no clue why
        val portNum = port.toIntOrNull()
        val portValid = portNum != null && portNum in 1024..65535
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.rpc_port)) },
            isError = !portValid,
            supportingText = {
                if (!portValid) Text(stringResource(R.string.port_invalid))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text(stringResource(R.string.rpc_token)) },
                modifier = Modifier.weight(1f),
            )
            // Persisted tokens survive upgrades on purpose; the dice rotates a
            // known/weak secret on demand. Draft only — persisted on Save.
            val cdRandomToken = stringResource(R.string.cd_random_token)
            IconButton(onClick = { token = SettingsRepo.randomToken() }) {
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
                selected = transport == RpcTransport.WS,
                onClick = { transport = RpcTransport.WS },
                label = { Text("WebSocket") },
            )
            FilterChip(
                selected = transport == RpcTransport.HTTP,
                onClick = { transport = RpcTransport.HTTP },
                label = { Text("HTTP") },
            )
        }
        Text(
            stringResource(R.string.rpc_transport_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SettingSwitch(stringResource(R.string.listen_all), stringResource(R.string.listen_all_sub), listenAll) { listenAll = it }
        SettingSwitch(
            stringResource(R.string.allow_origin),
            stringResource(R.string.allow_origin_sub),
            allowOriginAll,
        ) { allowOriginAll = it }

        Text(
            stringResource(R.string.engine_section),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        OutlinedTextField(
            value = dir,
            onValueChange = { dir = it },
            label = { Text(stringResource(R.string.download_dir)) },
            modifier = Modifier.fillMaxWidth(),
        )
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val isPublicDir = dir.startsWith("/storage/emulated/0/") && !dir.contains("/Android/data/")
        val storageGranted =
            Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
        // SAF tree picker → /storage/... path the engine can actually use
        val dirPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                uri?.let { treeUriToPath(it) }?.let { dir = it }
            }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { dirPicker.launch(null) }) {
                Text(stringResource(R.string.browse_dir))
            }
            TextButton(onClick = {
                dir = "/storage/emulated/0/Download"
                if (Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                    ctx.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            android.net.Uri.parse("package:${ctx.packageName}"),
                        ),
                    )
                }
            }) { Text(stringResource(R.string.use_public_download)) }
            if (isPublicDir && !storageGranted) {
                Text(
                    stringResource(R.string.storage_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        SettingSwitch(stringResource(R.string.check_cert), stringResource(R.string.check_cert_sub), checkCert) { checkCert = it }
        SettingSwitch(stringResource(R.string.save_session), stringResource(R.string.save_session_sub), saveSession) { saveSession = it }

        Text(stringResource(R.string.speed_limits), style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = dlLimit,
                onValueChange = { dlLimit = it.filter(Char::isDigit).take(7) },
                label = { Text(stringResource(R.string.dl_limit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = ulLimit,
                onValueChange = { ulLimit = it.filter(Char::isDigit).take(7) },
                label = { Text(stringResource(R.string.ul_limit)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            stringResource(R.string.limits_sub),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            stringResource(R.string.custom_options),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.forced_params_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OptionsEditor(state = customOptions, catalog = OptionCatalog.global)
        TextButton(onClick = {
            customOptions.addAll(
                mapOf(
                    "max-connection-per-server" to "16",
                    "split" to "64",
                    "min-split-size" to "1M",
                    "file-allocation" to "none",
                ),
            )
        }) { Text(stringResource(R.string.perf_preset)) }

        Button(
            onClick = {
                scope.launch {
                    repo.save(
                        EngineSpec(
                            rpcPort = portNum ?: s!!.rpcPort,
                            rpcToken = token.ifBlank { s!!.rpcToken },
                            rpcListenAll = listenAll,
                            rpcAllowOriginAll = allowOriginAll,
                            rpcTransport = transport,
                            dir = dir.ifEmpty { s!!.dir },
                            checkCertificate = checkCert,
                            saveSession = saveSession,
                            dlLimitKbps = dlLimit.toIntOrNull() ?: 0,
                            ulLimitKbps = ulLimit.toIntOrNull() ?: 0,
                            customOptions = customOptions.result(),
                        ),
                    )
                    onBack()
                }
            },
            enabled = portValid,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.save_restart)) }
    }
}

/** "primary:Download/x" → "/storage/emulated/0/Download/x"; sdcard volume id → "/storage/<id>/…". */
private fun treeUriToPath(uri: android.net.Uri): String? {
    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return null
    val parts = docId.split(":", limit = 2)
    val base =
        if (parts[0].equals("primary", ignoreCase = true)) {
            "/storage/emulated/0"
        } else {
            "/storage/${parts[0]}"
        }
    val rel = parts.getOrElse(1) { "" }
    return if (rel.isEmpty()) base else "$base/$rel"
}

@Composable
private fun SettingSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}
