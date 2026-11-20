package com.slipstream

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** aria2 option metadata: every valid key + friendly labels for the hot set. */
object OptionCatalog {
    data class Spec(
        val key: String,
        val label: String? = null,
    )

    private val LABELS =
        linkedMapOf(
            "out" to "Rename file",
            "dir" to "Save directory",
            "max-connection-per-server" to "Connections per server (-x)",
            "split" to "Segments (-s)",
            "min-split-size" to "Min split size (-k)",
            "max-download-limit" to "Speed limit (e.g. 10M)",
            "max-upload-limit" to "Upload limit",
            "max-overall-download-limit" to "Global speed limit",
            "max-overall-upload-limit" to "Global upload limit",
            "max-concurrent-downloads" to "Parallel downloads",
            "user-agent" to "User-Agent",
            "referer" to "Referer",
            "header" to "Extra headers",
            "load-cookies" to "Cookie file",
            "all-proxy" to "Proxy",
            "http-proxy" to "HTTP proxy",
            "https-proxy" to "HTTPS proxy",
            "no-proxy" to "Proxy bypass",
            "ftp-user" to "FTP user",
            "ftp-passwd" to "FTP password",
            "http-user" to "HTTP user",
            "http-passwd" to "HTTP password",
            "continue" to "Resume (-c)",
            "always-resume" to "Always resume",
            "file-allocation" to "File allocation",
            "no-file-allocation-limit" to "Allocation size limit",
            "allow-overwrite" to "Overwrite existing",
            "auto-file-renaming" to "Auto rename",
            "check-integrity" to "Verify integrity",
            "conditional-get" to "Conditional get",
            "checksum" to "Checksum (sha-256=…)",
            "connect-timeout" to "Connect timeout (s)",
            "timeout" to "Timeout (s)",
            "max-tries" to "Max tries",
            "retry-wait" to "Retry wait (s)",
            "lowest-speed-limit" to "Drop slow connections",
            "select-file" to "Select files (e.g. 1,3-5)",
            "index-out" to "Rename files inside torrent",
            "seed-ratio" to "Seed ratio",
            "seed-time" to "Seed time (min)",
            "bt-tracker" to "Extra trackers",
            "bt-max-peers" to "Max peers",
            "bt-save-metadata" to "Save .torrent",
            "bt-metadata-only" to "Metadata only",
            "bt-remove-unselected-file" to "Remove unselected files",
            "bt-force-encryption" to "Force encryption",
            "bt-require-crypto" to "Require encryption",
            "bt-request-peer-speed-limit" to "Min peer speed",
            "bt-lpd-interface" to "LPD interface",
            "dht-listen-port" to "DHT port",
            "follow-torrent" to "Follow .torrent",
            "piece-length" to "Piece length",
            "stream-piece-selector" to "Piece selector",
            "optimize-concurrent-downloads" to "Optimize concurrency",
            "disk-cache" to "Disk cache",
            "enable-mmap" to "Memory-map files",
            "max-mmap-limit" to "Mmap size limit",
            "http-accept-gzip" to "Accept gzip",
            "content-disposition-default-utf8" to "UTF-8 filenames",
            "remote-time" to "Use remote timestamp",
            "save-session-interval" to "Session save interval (s)",
            "max-download-result" to "Remember results",
            "async-dns" to "Async DNS",
            "disable-ipv6" to "Disable IPv6",
            "interface" to "Bind interface",
            "multiple-interface" to "Bind interfaces",
            "socket-recv-buffer-size" to "Socket recv buffer",
            "min-tls-version" to "Min TLS version",
            "ca-certificate" to "CA certificate",
        )

    private fun catalog(raw: List<String>): List<Spec> {
        val labeled = LABELS.keys.filter(raw::contains).map { Spec(it, LABELS[it]) }
        val rest = raw.filter { it !in LABELS }.map { Spec(it) }
        return labeled + rest
    }

    /** Options valid for aria2.addUri / aria2.changeOption (per-download). */
    val download: List<Spec> by lazy { catalog(RAW_DOWNLOAD) }

    /** Options valid at engine startup (global command-line flags). */
    val global: List<Spec> by lazy { catalog(RAW_GLOBAL) }

    fun label(key: String): String? = LABELS[key]

    private val RAW_DOWNLOAD =
        listOf(
            "all-proxy",
            "all-proxy-passwd",
            "all-proxy-user",
            "allow-overwrite",
            "allow-piece-length-change",
            "always-resume",
            "async-dns",
            "async-dns-server",
            "auto-file-renaming",
            "auto-save-interval",
            "bt-detach-seed-only",
            "bt-enable-hook-after-hash-check",
            "bt-enable-lpd",
            "bt-exclude-tracker",
            "bt-external-ip",
            "bt-force-encryption",
            "bt-hash-check-seed",
            "bt-load-saved-metadata",
            "bt-lpd-interface",
            "bt-max-open-files",
            "bt-max-peers",
            "bt-metadata-only",
            "bt-min-crypto-level",
            "bt-prioritize-piece",
            "bt-remove-unselected-file",
            "bt-request-peer-speed-limit",
            "bt-require-crypto",
            "bt-save-metadata",
            "bt-seed-unverified",
            "bt-stop-timeout",
            "bt-tracker",
            "bt-tracker-connect-timeout",
            "bt-tracker-interval",
            "bt-tracker-timeout",
            "ca-certificate",
            "certificate",
            "check-certificate",
            "check-integrity",
            "checksum",
            "conditional-get",
            "connect-timeout",
            "console-log-level",
            "content-disposition-default-utf8",
            "continue",
            "dht-entry-point",
            "dht-entry-point6",
            "dht-file-path",
            "dht-file-path6",
            "dht-listen-addr6",
            "dht-listen-port",
            "dht-message-timeout",
            "dir",
            "disable-ipv6",
            "disk-cache",
            "download-result",
            "dscp",
            "enable-async-dns6",
            "enable-dht",
            "enable-dht6",
            "enable-http-keep-alive",
            "enable-http-pipelining",
            "enable-mmap",
            "enable-peer-exchange",
            "enable-rpc",
            "file-allocation",
            "follow-metalink",
            "follow-torrent",
            "force-save",
            "force-sequential",
            "ftp-passwd",
            "ftp-pasv",
            "ftp-proxy",
            "ftp-proxy-passwd",
            "ftp-proxy-user",
            "ftp-reuse-connection",
            "ftp-type",
            "ftp-user",
            "hash-check-only",
            "header",
            "http-accept-gzip",
            "http-auth-challenge",
            "http-no-cache",
            "http-passwd",
            "http-proxy",
            "http-proxy-passwd",
            "http-proxy-user",
            "http-user",
            "https-proxy",
            "https-proxy-passwd",
            "https-proxy-user",
            "index-out",
            "input-file",
            "interface",
            "keep-unfinished-download-result",
            "listen-port",
            "load-cookies",
            "log",
            "log-level",
            "lowest-speed-limit",
            "max-concurrent-downloads",
            "max-connection-per-server",
            "max-download-limit",
            "max-download-result",
            "max-file-not-found",
            "max-mmap-limit",
            "max-overall-download-limit",
            "max-overall-upload-limit",
            "max-resume-failure-tries",
            "max-tries",
            "max-upload-limit",
            "min-split-size",
            "min-tls-version",
            "multiple-interface",
            "netrc-path",
            "no-file-allocation-limit",
            "no-netrc",
            "no-proxy",
            "optimize-concurrent-downloads",
            "out",
            "pause-metadata",
            "peer-agent",
            "peer-id-prefix",
            "private-key",
            "proxy-method",
            "realtime-chunk-checksum",
            "referer",
            "remote-time",
            "remove-control-file",
            "retry-wait",
            "rpc-allow-origin-all",
            "rpc-certificate",
            "rpc-listen-all",
            "rpc-listen-port",
            "rpc-max-request-size",
            "rpc-passwd",
            "rpc-private-key",
            "rpc-secret",
            "rpc-secure",
            "rpc-user",
            "save-cookies",
            "save-not-found",
            "save-session",
            "save-session-interval",
            "seed-ratio",
            "seed-time",
            "select-file",
            "server-stat-if",
            "server-stat-of",
            "server-stat-timeout",
            "socket-recv-buffer-size",
            "split",
            "ssh-host-key-md",
            "stream-piece-selector",
            "timeout",
            "use-head",
            "user-agent",
        )

    private val RAW_GLOBAL =
        listOf(
            "all-proxy",
            "all-proxy-passwd",
            "all-proxy-user",
            "allow-overwrite",
            "allow-piece-length-change",
            "always-resume",
            "async-dns",
            "async-dns-server",
            "auto-file-renaming",
            "auto-save-interval",
            "bt-detach-seed-only",
            "bt-enable-hook-after-hash-check",
            "bt-enable-lpd",
            "bt-exclude-tracker",
            "bt-external-ip",
            "bt-force-encryption",
            "bt-hash-check-seed",
            "bt-load-saved-metadata",
            "bt-lpd-interface",
            "bt-max-open-files",
            "bt-max-peers",
            "bt-metadata-only",
            "bt-min-crypto-level",
            "bt-prioritize-piece",
            "bt-remove-unselected-file",
            "bt-request-peer-speed-limit",
            "bt-require-crypto",
            "bt-save-metadata",
            "bt-seed-unverified",
            "bt-stop-timeout",
            "bt-tracker",
            "bt-tracker-connect-timeout",
            "bt-tracker-interval",
            "bt-tracker-timeout",
            "ca-certificate",
            "certificate",
            "check-integrity",
            "conditional-get",
            "connect-timeout",
            "console-log-level",
            "content-disposition-default-utf8",
            "continue",
            "dht-entry-point",
            "dht-entry-point6",
            "dht-file-path",
            "dht-file-path6",
            "dht-listen-addr6",
            "dht-listen-port",
            "dht-message-timeout",
            "disable-ipv6",
            "disk-cache",
            "download-result",
            "dscp",
            "enable-async-dns6",
            "enable-dht",
            "enable-dht6",
            "enable-http-keep-alive",
            "enable-http-pipelining",
            "enable-mmap",
            "enable-peer-exchange",
            "file-allocation",
            "follow-metalink",
            "follow-torrent",
            "force-save",
            "force-sequential",
            "ftp-passwd",
            "ftp-pasv",
            "ftp-proxy",
            "ftp-proxy-passwd",
            "ftp-proxy-user",
            "ftp-reuse-connection",
            "ftp-type",
            "ftp-user",
            "hash-check-only",
            "header",
            "http-accept-gzip",
            "http-auth-challenge",
            "http-no-cache",
            "http-passwd",
            "http-proxy",
            "http-proxy-passwd",
            "http-proxy-user",
            "http-user",
            "https-proxy",
            "https-proxy-passwd",
            "https-proxy-user",
            "interface",
            "keep-unfinished-download-result",
            "listen-port",
            "load-cookies",
            "log",
            "log-level",
            "lowest-speed-limit",
            "max-concurrent-downloads",
            "max-connection-per-server",
            "max-download-limit",
            "max-download-result",
            "max-file-not-found",
            "max-mmap-limit",
            "max-overall-download-limit",
            "max-overall-upload-limit",
            "max-resume-failure-tries",
            "max-tries",
            "max-upload-limit",
            "min-split-size",
            "min-tls-version",
            "multiple-interface",
            "netrc-path",
            "no-file-allocation-limit",
            "no-netrc",
            "no-proxy",
            "optimize-concurrent-downloads",
            "pause-metadata",
            "peer-agent",
            "peer-id-prefix",
            "piece-length",
            "private-key",
            "proxy-method",
            "realtime-chunk-checksum",
            "referer",
            "remote-time",
            "remove-control-file",
            "retry-wait",
            "rpc-certificate",
            "rpc-max-request-size",
            "rpc-passwd",
            "rpc-private-key",
            "rpc-save-upload-metadata",
            "rpc-secure",
            "rpc-user",
            "save-cookies",
            "save-not-found",
            "save-session-interval",
            "seed-ratio",
            "seed-time",
            "server-stat-if",
            "server-stat-of",
            "server-stat-timeout",
            "socket-recv-buffer-size",
            "split",
            "ssh-host-key-md",
            "stream-piece-selector",
            "timeout",
            "use-head",
            "user-agent",
        )
}

/** Editable (key → value) option rows; blank values are dropped in [result]. */
class OptionsEditorState(
    initial: Map<String, String>,
) {
    val entries =
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initial.toList())
        }

    fun add(key: String) {
        if (entries.none { it.first == key }) entries.add(key to "")
    }

    fun addAll(options: Map<String, String>) =
        options.forEach { (k, v) ->
            val i = entries.indexOfFirst { it.first == k }
            if (i >= 0) entries[i] = k to v else entries.add(k to v)
        }

    fun result(): Map<String, String> =
        entries
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()
}

@Composable
fun rememberOptionsEditorState(initial: Map<String, String>) = remember { OptionsEditorState(initial) }

/**
 * Key-driven option editor: pick a key from the aria2 catalog, then fill in
 * only the value. Used both inside dialogs (per-download) and inline (settings).
 */
@Composable
fun OptionsEditor(
    state: OptionsEditorState,
    catalog: List<OptionCatalog.Spec>,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.entries.forEachIndexed { i, (key, value) ->
            val label = OptionCatalog.label(key)
            OutlinedTextField(
                value = value,
                onValueChange = { state.entries[i] = key to it },
                label = { Text(label ?: key) },
                supportingText = label?.let { { Text(key) } },
                trailingIcon = {
                    IconButton(onClick = { state.entries.removeAt(i) }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_remove_option))
                    }
                },
                // header takes multiple lines (one per header) — singleLine
                // would make multi-header values impossible to enter.
                singleLine = key != "header",
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = { showPicker = true }) { Text(stringResource(R.string.add_option)) }
    }

    if (showPicker) {
        OptionKeyPickerDialog(
            catalog = catalog,
            existing = state.entries.map { it.first }.toSet(),
            onDismiss = { showPicker = false },
            onPick = { key ->
                state.add(key)
                showPicker = false
            },
        )
    }
}

@Composable
private fun OptionKeyPickerDialog(
    catalog: List<OptionCatalog.Spec>,
    existing: Set<String>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered =
        catalog.filter {
            it.key !in existing &&
                (query.isBlank() || it.key.contains(query, true) || it.label?.contains(query, true) == true)
        }
    val customKey =
        query
            .trim()
            .lowercase()
            // Must start alphanumeric: a leading '-' would produce malformed
            // keys like "---split" once args() prefixes "--".
            .takeIf {
                it.isNotEmpty() &&
                    it.first().isLetterOrDigit() &&
                    it.all { c -> c.isLetterOrDigit() || c == '-' } &&
                    it !in existing
            }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_option_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    customKey?.let { k ->
                        item {
                            Column(Modifier.fillMaxWidth().clickable { onPick(k) }.padding(vertical = 10.dp)) {
                                Text(stringResource(R.string.use_custom_key), style = MaterialTheme.typography.bodyLarge)
                                Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider()
                        }
                    }
                    items(filtered, key = { it.key }) { spec ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(spec.key) }.padding(vertical = 10.dp)) {
                            Text(spec.label ?: spec.key, style = MaterialTheme.typography.bodyLarge)
                            if (spec.label != null) {
                                Text(
                                    spec.key,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Dialog wrapper for per-download editing. For a new download the result feeds
 * aria2.addUri; for an existing gid it feeds aria2.changeOption (live for most
 * perf options).
 */
@Composable
fun OptionsEditorDialog(
    title: String,
    initial: Map<String, String>,
    onDismiss: () -> Unit,
    onApply: (Map<String, String>) -> Unit,
) {
    val state = rememberOptionsEditorState(initial)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OptionsEditor(
                state = state,
                catalog = OptionCatalog.download,
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.options_live_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Button(onClick = { onApply(state.result()) }) { Text(stringResource(R.string.apply)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
