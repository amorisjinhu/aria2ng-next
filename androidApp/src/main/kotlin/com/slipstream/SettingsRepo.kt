package com.slipstream

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slipstream.engine.EngineSpec
import com.slipstream.engine.RpcTransport
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val Context.settingsStore by preferencesDataStore("settings")

/**
 * Single source of truth for engine settings (DataStore). EngineSpec reads
 * are consumed by EngineProcess; any change triggers an engine restart.
 */
class SettingsRepo(
    private val context: Context,
) {
    /**
     * First-run seeding: write a random RPC token so the engine never listens
     * on a publicly-known default ("gbc" shipped in source). Idempotent — the
     * edit is transactional, so concurrent callers can't clobber each other.
     * Persisted tokens are deliberately kept across upgrades; users rotate a
     * known/weak secret via the dice button in settings.
     */
    suspend fun ensureDefaults() =
        context.settingsStore.edit { p ->
            if (p[RPC_TOKEN].isNullOrBlank()) p[RPC_TOKEN] = randomToken()
        }

    val spec: Flow<EngineSpec> =
        context.settingsStore.data.map { p ->
            EngineSpec(
                rpcPort = p[RPC_PORT] ?: BuildConfig.DEFAULT_RPC_PORT,
                rpcToken = p[RPC_TOKEN] ?: "gbc",
                rpcListenAll = p[LISTEN_ALL] ?: false,
                rpcAllowOriginAll = p[ALLOW_ORIGIN_ALL] ?: false,
                rpcTransport = p[RPC_TRANSPORT]?.let { runCatching { RpcTransport.valueOf(it) }.getOrNull() } ?: RpcTransport.WS,
                dir = p[DIR] ?: EngineProcess.defaultDownloadDir(context),
                checkCertificate = p[CHECK_CERT] ?: false,
                saveSession = p[SAVE_SESSION] ?: true,
                dlLimitKbps = p[DL_LIMIT] ?: 0,
                ulLimitKbps = p[UL_LIMIT] ?: 0,
                customOptions =
                    p[CUSTOM_OPTIONS]
                        ?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                        ?.mapValues { it.value.jsonPrimitive.content }
                        ?: emptyMap(),
            )
        }

    /** Writes all fields in one edit → one emission → one engine restart. */
    suspend fun save(spec: EngineSpec) =
        context.settingsStore.edit {
            it[RPC_PORT] = spec.rpcPort
            it[RPC_TOKEN] = spec.rpcToken
            it[LISTEN_ALL] = spec.rpcListenAll
            it[ALLOW_ORIGIN_ALL] = spec.rpcAllowOriginAll
            it[RPC_TRANSPORT] = spec.rpcTransport.name
            it[DIR] = spec.dir
            it[CHECK_CERT] = spec.checkCertificate
            it[SAVE_SESSION] = spec.saveSession
            it[DL_LIMIT] = spec.dlLimitKbps
            it[UL_LIMIT] = spec.ulLimitKbps
            it[CUSTOM_OPTIONS] =
                if (spec.customOptions.isEmpty()) {
                    ""
                } else {
                    kotlinx.serialization.json
                        .buildJsonObject {
                            spec.customOptions.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
                        }.toString()
                }
        }

    /** UI-only pref — theme changes must not restart the engine. */
    val themeMode: Flow<Int> = context.settingsStore.data.map { it[THEME_MODE] ?: 0 }

    suspend fun setThemeMode(mode: Int) = context.settingsStore.edit { it[THEME_MODE] = mode }

    suspend fun setRpcPort(port: Int) = context.settingsStore.edit { it[RPC_PORT] = port }

    suspend fun setRpcToken(token: String) = context.settingsStore.edit { it[RPC_TOKEN] = token }

    suspend fun setListenAll(v: Boolean) = context.settingsStore.edit { it[LISTEN_ALL] = v }

    suspend fun setAllowOriginAll(v: Boolean) = context.settingsStore.edit { it[ALLOW_ORIGIN_ALL] = v }

    suspend fun setDir(dir: String) = context.settingsStore.edit { it[DIR] = dir }

    suspend fun setCheckCertificate(v: Boolean) = context.settingsStore.edit { it[CHECK_CERT] = v }

    suspend fun setSaveSession(v: Boolean) = context.settingsStore.edit { it[SAVE_SESSION] = v }

    companion object {
        /** 16 random bytes as hex — used for first-run seeding and the settings dice button. */
        fun randomToken(): String {
            val bytes = ByteArray(16)
            java.security.SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        private val RPC_PORT = intPreferencesKey("rpc_port")
        private val RPC_TOKEN = stringPreferencesKey("rpc_token")
        private val LISTEN_ALL = booleanPreferencesKey("listen_all")
        private val ALLOW_ORIGIN_ALL = booleanPreferencesKey("allow_origin_all")
        private val RPC_TRANSPORT = stringPreferencesKey("rpc_transport")
        private val DIR = stringPreferencesKey("dir")
        private val CHECK_CERT = booleanPreferencesKey("check_certificate")
        private val SAVE_SESSION = booleanPreferencesKey("save_session")
        private val DL_LIMIT = intPreferencesKey("dl_limit")
        private val UL_LIMIT = intPreferencesKey("ul_limit")
        private val CUSTOM_OPTIONS = stringPreferencesKey("custom_options")
        private val THEME_MODE = intPreferencesKey("theme_mode")
    }
}
