package com.slipstream

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.slipstream.engine.EngineSpec
import com.slipstream.engine.RpcTransport
import com.slipstream.model.CategorySpec
import com.slipstream.model.FileCategories
import com.slipstream.model.FileCategory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// Corruption (disk fault / power loss mid-write) must not crash-loop the app:
// the handler swaps in empty prefs, ensureDefaults() re-seeds tokens, and the
// .catch below covers any other IOException on the read path.
private val Context.settingsStore by preferencesDataStore(
    name = "settings",
    corruptionHandler =
        androidx.datastore.core.handlers
            .ReplaceFileCorruptionHandler { emptyPreferences() },
)

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
            if (p[EXT_TOKEN].isNullOrBlank()) p[EXT_TOKEN] = randomToken()
        }

    val spec: Flow<EngineSpec> =
        context.settingsStore.data
            .catch { e ->
                android.util.Log.e("SettingsRepo", "settings read failed — falling back to defaults", e)
                emit(emptyPreferences())
            }.map { p ->
                EngineSpec(
                    rpcPort = p[RPC_PORT] ?: BuildConfig.DEFAULT_RPC_PORT,
                    // Never fall back to the publicly-known "gbc": on a corrupt
                    // store the engine would otherwise restart on a default secret.
                    rpcToken = p[RPC_TOKEN]?.takeIf { it.isNotBlank() } ?: randomToken(),
                    rpcListenAll = p[LISTEN_ALL] ?: false,
                    rpcAllowOriginAll = p[ALLOW_ORIGIN_ALL] ?: false,
                    rpcTransport =
                        p[RPC_TRANSPORT]?.let { runCatching { RpcTransport.valueOf(it) }.getOrNull() }
                            ?: RpcTransport.WS,
                    dir = p[DIR] ?: EngineProcess.defaultDownloadDir(context),
                    // With a bundled CA the safe default is ON; without one, ON
                    // would break every HTTPS download (no system store on Android).
                    checkCertificate = p[CHECK_CERT] ?: (EngineProcess.caBundleFile(context) != null),
                    saveSession = p[SAVE_SESSION] ?: true,
                    dlLimitKbps = p[DL_LIMIT] ?: 0,
                    ulLimitKbps = p[UL_LIMIT] ?: 0,
                    // Whole parse chain guarded: a non-primitive value (nested
                    // object/array) must not throw out of the DataStore flow and
                    // kill the collector.
                    customOptions =
                        p[CUSTOM_OPTIONS]
                            ?.let { raw ->
                                runCatching {
                                    Json.parseToJsonElement(raw).jsonObject.mapValues { it.value.jsonPrimitive.content }
                                }.getOrNull()
                            } ?: emptyMap(),
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

    /** Extension-API prefs — consumed by updateExtApi, never restarts the engine. */
    val extSpec: Flow<ExtApiSpec> =
        context.settingsStore.data
            .catch { e ->
                android.util.Log.e("SettingsRepo", "ext settings read failed — falling back to defaults", e)
                emit(emptyPreferences())
            }.map { p ->
                ExtApiSpec(
                    enabled = p[EXT_ENABLED] ?: true,
                    port = p[EXT_PORT] ?: BuildConfig.DEFAULT_EXT_PORT,
                    token = p[EXT_TOKEN] ?: "",
                    listenAll = p[EXT_LISTEN_ALL] ?: false,
                )
            }

    suspend fun saveExt(spec: ExtApiSpec) =
        context.settingsStore.edit {
            it[EXT_ENABLED] = spec.enabled
            it[EXT_PORT] = spec.port
            it[EXT_TOKEN] = spec.token
            it[EXT_LISTEN_ALL] = spec.listenAll
        }

    /**
     * File-category prefs — app-level like extSpec, never restarts the engine.
     * Read-side fallbacks: blank/bad JSON or "enabled with an empty table"
     * hydrate to the built-in set, matching RayBurst's hydration guard.
     */
    val categorySpec: Flow<CategorySpec> =
        context.settingsStore.data
            .catch { e ->
                android.util.Log.e("SettingsRepo", "category settings read failed — falling back to defaults", e)
                emit(emptyPreferences())
            }.map { p ->
                val enabled = p[CATEGORY_ENABLED] ?: false
                val parsed =
                    p[CATEGORIES]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { raw ->
                            runCatching {
                                Json.decodeFromString(ListSerializer(FileCategory.serializer()), raw)
                            }.getOrNull()
                        }
                val categories =
                    when {
                        parsed == null -> FileCategories.BUILTIN
                        enabled && parsed.isEmpty() -> FileCategories.BUILTIN
                        else -> parsed
                    }
                CategorySpec(enabled, categories.map(FileCategories::normalize))
            }

    suspend fun saveCategories(spec: CategorySpec) =
        context.settingsStore.edit {
            it[CATEGORY_ENABLED] = spec.enabled
            it[CATEGORIES] =
                if (spec.categories.isEmpty()) {
                    ""
                } else {
                    Json.encodeToString(ListSerializer(FileCategory.serializer()), spec.categories)
                }
        }

    /** UI-only pref — theme changes must not restart the engine. */
    val themeMode: Flow<Int> =
        context.settingsStore.data
            .catch { emit(emptyPreferences()) }
            .map { it[THEME_MODE] ?: 0 }

    suspend fun setThemeMode(mode: Int) = context.settingsStore.edit { it[THEME_MODE] = mode }

    suspend fun setRpcPort(port: Int) = context.settingsStore.edit { it[RPC_PORT] = port }

    suspend fun setRpcToken(token: String) = context.settingsStore.edit { it[RPC_TOKEN] = token }

    suspend fun setListenAll(v: Boolean) = context.settingsStore.edit { it[LISTEN_ALL] = v }

    suspend fun setAllowOriginAll(v: Boolean) = context.settingsStore.edit { it[ALLOW_ORIGIN_ALL] = v }

    suspend fun setDir(dir: String) = context.settingsStore.edit { it[DIR] = dir }

    suspend fun setCheckCertificate(v: Boolean) = context.settingsStore.edit { it[CHECK_CERT] = v }

    suspend fun setSaveSession(v: Boolean) = context.settingsStore.edit { it[SAVE_SESSION] = v }

    companion object {
        private val secureRandom = java.security.SecureRandom()

        /** 16 random bytes as hex — used for first-run seeding and the settings dice button. */
        fun randomToken(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
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
        private val EXT_ENABLED = booleanPreferencesKey("ext_enabled")
        private val EXT_PORT = intPreferencesKey("ext_port")
        private val EXT_TOKEN = stringPreferencesKey("ext_token")
        private val EXT_LISTEN_ALL = booleanPreferencesKey("ext_listen_all")
        private val CATEGORY_ENABLED = booleanPreferencesKey("category_enabled")
        private val CATEGORIES = stringPreferencesKey("categories")
    }
}
