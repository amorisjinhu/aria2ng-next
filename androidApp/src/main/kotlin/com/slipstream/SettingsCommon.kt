package com.slipstream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

/** Section header — primary-color titleSmall, matching the old screen. */
@Composable
internal fun SettingSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
internal fun SettingSwitch(
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
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onChange)
        }
    }
}

@Composable
internal fun SettingLink(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant, onClick = onClick) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Typed custom-option text field: blank = the key is not emitted at all
 * (engine default), which keeps untouched options out of EngineSpec.
 */
@Composable
internal fun TypedOptionField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    valid: Boolean = true,
    hint: String = stringResource(R.string.blank_engine_default),
    numeric: Boolean = false,
    errorText: String = stringResource(R.string.opt_number_invalid),
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = !valid,
        supportingText = {
            if (valid) {
                Text(hint)
            } else {
                Text(errorText)
            }
        },
        keyboardOptions =
            if (numeric) {
                KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                )
            } else {
                KeyboardOptions.Default
            },
        singleLine = singleLine,
        modifier = modifier.fillMaxWidth(),
    )
}

/** default / on / off — null means the key is not emitted (engine default). */
@Composable
internal fun TriStateChips(
    value: Boolean?,
    onChange: (Boolean?) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = value == null,
            onClick = { onChange(null) },
            label = { Text(stringResource(R.string.tri_default)) },
        )
        FilterChip(
            selected = value == true,
            onClick = { onChange(true) },
            label = { Text(stringResource(R.string.tri_on)) },
        )
        FilterChip(
            selected = value == false,
            onClick = { onChange(false) },
            label = { Text(stringResource(R.string.tri_off)) },
        )
    }
}

/**
 * "primary:Download/x" → "<external storage>/Download/x"; SD volume UUID →
 * "/storage/<UUID>/…". Any other authority (cloud documents etc.) has NO
 * filesystem path — returning a fabricated "/storage/<docId>" would point
 * the engine at garbage, so reject it outright.
 */
internal fun treeUriToPath(uri: android.net.Uri): String? {
    val docId = android.provider.DocumentsContract.getTreeDocumentId(uri) ?: return null
    val parts = docId.split(":", limit = 2)
    val volume = parts[0]
    val base =
        when {
            // The current user's root, not hardcoded user 0 — secondary users
            // and work profiles mount external storage at /storage/emulated/<id>.
            volume.equals("primary", ignoreCase = true) ->
                android.os.Environment
                    .getExternalStorageDirectory()
                    .absolutePath

            Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}").matches(volume) -> "/storage/$volume"
            else -> return null
        }
    val rel = parts.getOrElse(1) { "" }
    return if (rel.isEmpty()) base else "$base/$rel"
}

/** Settings pages can be absent on stripped OEM builds — never crash on the jump. */
internal fun startSettings(
    ctx: android.content.Context,
    intent: android.content.Intent,
) {
    runCatching { ctx.startActivity(intent) }.onFailure {
        android.widget.Toast
            .makeText(ctx, ctx.getString(R.string.settings_page_failed), android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/** Deep-link into the ext-request channel's alert toggles (banner/sound/lockscreen). */
internal fun openChannelSettings(ctx: android.content.Context) {
    startSettings(
        ctx,
        android.content
            .Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, EngineService.EXT_CHANNEL_ID),
    )
}

/**
 * MIUI/HyperOS gates full-screen intents behind a per-app "pop up while in
 * background" switch that only exists in its own permission editor. Fall back
 * to the generic app-details page on other OEMs.
 */
internal fun openBgPopupSettings(ctx: android.content.Context) {
    val miui =
        android.content
            .Intent("miui.intent.action.APP_PERM_EDITOR")
            .setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity",
            ).putExtra("extra_pkgname", ctx.packageName)
    val intent =
        if (miui.resolveActivity(ctx.packageManager) != null) {
            miui
        } else {
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${ctx.packageName}".toUri(),
            )
        }
    startSettings(ctx, intent)
}

/**
 * Deep-link to the doze/battery-optimization whitelist dialog — the single
 * switch that keeps the Extension API reachable while the app is backgrounded.
 * Already whitelisted → fall back to the app details page.
 */
internal fun requestBatteryWhitelist(ctx: android.content.Context) {
    val pm = ctx.getSystemService(android.os.PowerManager::class.java)
    val intent =
        if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) {
            android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${ctx.packageName}".toUri(),
            )
        } else {
            android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                "package:${ctx.packageName}".toUri(),
            )
        }
    startSettings(ctx, intent)
}

/** Jump to this app's "All files access" grant page (API 30+). */
internal fun openAllFilesAccess(ctx: android.content.Context) {
    startSettings(
        ctx,
        android.content.Intent(
            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            "package:${ctx.packageName}".toUri(),
        ),
    )
}
