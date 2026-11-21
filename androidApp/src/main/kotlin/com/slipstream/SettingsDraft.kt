package com.slipstream

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import com.slipstream.engine.EngineSpec
import com.slipstream.engine.RpcTransport
import com.slipstream.model.CategorySpec
import com.slipstream.model.FileCategories
import com.slipstream.model.FileCategory
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Cross-tab settings draft: every editable field lives here as snapshot
 * state — survives tab navigation and rotation via rememberSaveable + the
 * JSON snapshot [Saver] below. Typed fields are extracted OUT of
 * EngineSpec.customOptions; what remains lives in [residual] — the same
 * OptionsEditor the old screen used.
 *
 * Merge contract: blank typed fields never emit, so extract→merge is the
 * identity on a conforming spec and untouched keys stay absent (zero
 * behavior change for existing users).
 */
class SettingsDraft internal constructor(
    spec: EngineSpec,
    extSpec: ExtApiSpec,
    categorySpec: CategorySpec,
    residualOptions: Map<String, String>,
) {
    /** Resolved engine dir — base for relative categories and the dir fallback. */
    val defaultDir: String = spec.dir

    var rpcPort by mutableStateOf(spec.rpcPort.toString())
    var rpcToken by mutableStateOf(spec.rpcToken)
    var dir by mutableStateOf(spec.dir)
    var listenAll by mutableStateOf(spec.rpcListenAll)
    var allowOriginAll by mutableStateOf(spec.rpcAllowOriginAll)
    var transport by mutableStateOf(spec.rpcTransport)
    var checkCert by mutableStateOf(spec.checkCertificate)
    var saveSession by mutableStateOf(spec.saveSession)
    var dlLimit by mutableStateOf(spec.dlLimitKbps.takeIf { it > 0 }?.toString() ?: "")
    var ulLimit by mutableStateOf(spec.ulLimitKbps.takeIf { it > 0 }?.toString() ?: "")

    var extEnabled by mutableStateOf(extSpec.enabled)
    var extPort by mutableStateOf(extSpec.port.toString())
    var extToken by mutableStateOf(extSpec.token)
    var extListenAll by mutableStateOf(extSpec.listenAll)

    var categoryEnabled by mutableStateOf(categorySpec.enabled)
    var categories by mutableStateOf(categorySpec.categories)

    // Typed custom-option fields — blank means "engine default, don't emit".
    var maxConcurrentDownloads by mutableStateOf("")
    var split by mutableStateOf("")
    var maxConnections by mutableStateOf("")
    var maxTries by mutableStateOf("")
    var retryWait by mutableStateOf("")
    var fileAllocation by mutableStateOf("")
    var seedRatio by mutableStateOf("")
    var seedTime by mutableStateOf("")
    var btMaxPeers by mutableStateOf("")
    var btListenPort by mutableStateOf("")
    var btTracker by mutableStateOf("")
    var allProxy by mutableStateOf("")
    var connectTimeout by mutableStateOf("")
    var timeout by mutableStateOf("")
    var userAgent by mutableStateOf("")
    var enableDht by mutableStateOf<Boolean?>(null)
    var enablePeerExchange by mutableStateOf<Boolean?>(null)
    var enableLpd by mutableStateOf<Boolean?>(null)

    /** Untyped remainder of customOptions — the old screen's option editor. */
    val residual: OptionsEditorState = OptionsEditorState(residualOptions)

    // ---- validation --------------------------------------------------------

    val portValid: Boolean get() = rpcPort.toIntOrNull() in 1024..65535

    val extPortValid: Boolean
        get() = extPort.toIntOrNull().let { it != null && it in 1024..65535 && it != rpcPort.toIntOrNull() }

    val btPortValid: Boolean get() = btListenPort.isBlank() || btListenPort.toIntOrNull() in 1024..65535

    val maxConcurrentValid: Boolean get() = nonNegInt(maxConcurrentDownloads)
    val splitValid: Boolean get() = nonNegInt(split)
    val maxConnectionsValid: Boolean get() = nonNegInt(maxConnections)
    val maxTriesValid: Boolean get() = nonNegInt(maxTries)
    val retryWaitValid: Boolean get() = nonNegInt(retryWait)
    val seedRatioValid: Boolean get() = nonNegNum(seedRatio) // seed-ratio is a float
    val seedTimeValid: Boolean get() = nonNegNum(seedTime)
    val btMaxPeersValid: Boolean get() = nonNegInt(btMaxPeers)
    val connectTimeoutValid: Boolean get() = nonNegInt(connectTimeout)
    val timeoutValid: Boolean get() = nonNegInt(timeout)

    /** FileCategories.validate code (mapped to text in the UI), null when clean. */
    val categoryError: String?
        get() = if (categoryEnabled) FileCategories.validate(categories, dir.ifEmpty { defaultDir }) else null

    private val numericValid: Boolean
        get() =
            maxConcurrentValid &&
                splitValid &&
                maxConnectionsValid &&
                maxTriesValid &&
                retryWaitValid &&
                seedRatioValid &&
                seedTimeValid &&
                btMaxPeersValid &&
                connectTimeoutValid &&
                timeoutValid

    val valid: Boolean
        get() = portValid && (!extEnabled || extPortValid) && btPortValid && numericValid && categoryError == null

    // ---- merge -------------------------------------------------------------

    /** residual options + typed non-blank fields (typed wins on conflict). */
    fun mergedOptions(): Map<String, String> {
        val out = LinkedHashMap(residual.result())

        fun put(
            key: String,
            value: String,
        ) {
            if (value.isNotBlank()) out[key] = value
        }
        put("max-concurrent-downloads", maxConcurrentDownloads)
        put("split", split)
        put("max-connection-per-server", maxConnections)
        put("max-tries", maxTries)
        put("retry-wait", retryWait)
        put("file-allocation", fileAllocation)
        put("seed-ratio", seedRatio)
        put("seed-time", seedTime)
        put("bt-max-peers", btMaxPeers)
        put("listen-port", btListenPort)
        put("bt-tracker", trackerValue())
        put("all-proxy", allProxy)
        put("connect-timeout", connectTimeout)
        put("timeout", timeout)
        put("user-agent", userAgent)
        enableDht?.let { out["enable-dht"] = it.toString() }
        enablePeerExchange?.let { out["enable-peer-exchange"] = it.toString() }
        enableLpd?.let { out["bt-enable-lpd"] = it.toString() }
        return out
    }

    /** Multi-line tracker input → aria2's comma-separated form. */
    private fun trackerValue(): String =
        if ('\n' in btTracker) {
            btTracker
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(",")
        } else {
            btTracker
        }

    fun toSpec(base: EngineSpec): EngineSpec =
        base.copy(
            rpcPort = rpcPort.toIntOrNull() ?: base.rpcPort,
            rpcToken = rpcToken.ifBlank { base.rpcToken },
            rpcListenAll = listenAll,
            rpcAllowOriginAll = allowOriginAll,
            rpcTransport = transport,
            dir = dir.ifEmpty { base.dir },
            checkCertificate = checkCert,
            saveSession = saveSession,
            dlLimitKbps = dlLimit.toIntOrNull() ?: 0,
            ulLimitKbps = ulLimit.toIntOrNull() ?: 0,
            customOptions = mergedOptions(),
        )

    fun toExtSpec(base: ExtApiSpec): ExtApiSpec =
        base.copy(
            enabled = extEnabled,
            port = extPort.toIntOrNull() ?: base.port,
            token = extToken.ifBlank { base.token },
            listenAll = extListenAll,
        )

    fun toCategorySpec(): CategorySpec = CategorySpec(enabled = categoryEnabled, categories = categories.map(FileCategories::normalize))

    /** JSON form used by [Saver]. Blank residual rows round-trip verbatim. */
    fun snapshot(): String =
        Json.encodeToString(
            DraftSnapshot(
                defaultDir = defaultDir,
                rpcPort = rpcPort,
                rpcToken = rpcToken,
                dir = dir,
                listenAll = listenAll,
                allowOriginAll = allowOriginAll,
                transport = transport.name,
                checkCert = checkCert,
                saveSession = saveSession,
                dlLimit = dlLimit,
                ulLimit = ulLimit,
                extEnabled = extEnabled,
                extPort = extPort,
                extToken = extToken,
                extListenAll = extListenAll,
                categoryEnabled = categoryEnabled,
                categories = categories,
                maxConcurrentDownloads = maxConcurrentDownloads,
                split = split,
                maxConnections = maxConnections,
                maxTries = maxTries,
                retryWait = retryWait,
                fileAllocation = fileAllocation,
                seedRatio = seedRatio,
                seedTime = seedTime,
                btMaxPeers = btMaxPeers,
                btListenPort = btListenPort,
                btTracker = btTracker,
                allProxy = allProxy,
                connectTimeout = connectTimeout,
                timeout = timeout,
                userAgent = userAgent,
                enableDht = enableDht,
                enablePeerExchange = enablePeerExchange,
                enableLpd = enableLpd,
                // entries.toMap() keeps blank-value rows; result() would drop them.
                residual = residual.entries.toMap(),
            ),
        )

    companion object {
        /** Rebuilds a draft from [snapshot] output; throws on corrupt input. */
        fun fromSnapshot(json: String): SettingsDraft {
            val s = Json.decodeFromString<DraftSnapshot>(json)
            return SettingsDraft(
                EngineSpec(dir = s.defaultDir),
                ExtApiSpec(),
                CategorySpec(),
                s.residual,
            ).apply {
                rpcPort = s.rpcPort
                rpcToken = s.rpcToken
                dir = s.dir
                listenAll = s.listenAll
                allowOriginAll = s.allowOriginAll
                transport = RpcTransport.valueOf(s.transport)
                checkCert = s.checkCert
                saveSession = s.saveSession
                dlLimit = s.dlLimit
                ulLimit = s.ulLimit
                extEnabled = s.extEnabled
                extPort = s.extPort
                extToken = s.extToken
                extListenAll = s.extListenAll
                categoryEnabled = s.categoryEnabled
                categories = s.categories
                maxConcurrentDownloads = s.maxConcurrentDownloads
                split = s.split
                maxConnections = s.maxConnections
                maxTries = s.maxTries
                retryWait = s.retryWait
                fileAllocation = s.fileAllocation
                seedRatio = s.seedRatio
                seedTime = s.seedTime
                btMaxPeers = s.btMaxPeers
                btListenPort = s.btListenPort
                btTracker = s.btTracker
                allProxy = s.allProxy
                connectTimeout = s.connectTimeout
                timeout = s.timeout
                userAgent = s.userAgent
                enableDht = s.enableDht
                enablePeerExchange = s.enablePeerExchange
                enableLpd = s.enableLpd
            }
        }

        /**
         * Bundle saver for rememberSaveable. A draft over the cap is dropped
         * instead of saved — urlPatterns have no count limit, so an extreme
         * table could otherwise blow the Binder transaction limit; rotation
         * then just re-runs the initializer. Corrupt JSON restores as null
         * for the same reason.
         */
        val Saver: Saver<SettingsDraft, String> =
            Saver(
                save = { it.snapshot().takeIf { s -> s.length <= 200_000 } },
                restore = { runCatching { fromSnapshot(it) }.getOrNull() },
            )

        /** aria2 keys with dedicated controls — everything else stays residual. */
        private val TYPED_KEYS =
            setOf(
                "max-concurrent-downloads",
                "split",
                "max-connection-per-server",
                "max-tries",
                "retry-wait",
                "file-allocation",
                "seed-ratio",
                "seed-time",
                "bt-max-peers",
                "listen-port",
                "bt-tracker",
                "all-proxy",
                "connect-timeout",
                "timeout",
                "user-agent",
                "enable-dht",
                "enable-peer-exchange",
                "bt-enable-lpd",
            )

        private fun tri(v: String): Boolean? =
            when (v) {
                "true" -> true
                "false" -> false
                else -> null
            }

        fun from(
            spec: EngineSpec,
            extSpec: ExtApiSpec,
            categorySpec: CategorySpec,
        ): SettingsDraft {
            val draft =
                SettingsDraft(
                    spec,
                    extSpec,
                    categorySpec,
                    spec.customOptions.filterKeys { it !in TYPED_KEYS },
                )
            spec.customOptions.forEach { (k, v) ->
                when (k) {
                    "max-concurrent-downloads" -> draft.maxConcurrentDownloads = v
                    "split" -> draft.split = v
                    "max-connection-per-server" -> draft.maxConnections = v
                    "max-tries" -> draft.maxTries = v
                    "retry-wait" -> draft.retryWait = v
                    "file-allocation" -> draft.fileAllocation = v
                    "seed-ratio" -> draft.seedRatio = v
                    "seed-time" -> draft.seedTime = v
                    "bt-max-peers" -> draft.btMaxPeers = v
                    "listen-port" -> draft.btListenPort = v
                    "bt-tracker" -> draft.btTracker = v
                    "all-proxy" -> draft.allProxy = v
                    "connect-timeout" -> draft.connectTimeout = v
                    "timeout" -> draft.timeout = v
                    "user-agent" -> draft.userAgent = v
                    // A non-"true"/"false" value keeps its residual seat —
                    // dropping it would silently lose a user key on save.
                    "enable-dht", "enable-peer-exchange", "bt-enable-lpd" -> {
                        val b = tri(v)
                        if (b == null) {
                            draft.residual.addAll(mapOf(k to v))
                        } else {
                            when (k) {
                                "enable-dht" -> draft.enableDht = b
                                "enable-peer-exchange" -> draft.enablePeerExchange = b
                                else -> draft.enableLpd = b
                            }
                        }
                    }
                }
            }
            return draft
        }
    }
}

private fun nonNegInt(s: String): Boolean = s.isBlank() || s.toIntOrNull()?.let { it >= 0 } == true

private fun nonNegNum(s: String): Boolean = s.isBlank() || s.toDoubleOrNull()?.let { it >= 0 } == true

/** Exhaustive serializable mirror of every draft field — see [SettingsDraft.snapshot]. */
@Serializable
private data class DraftSnapshot(
    val defaultDir: String,
    val rpcPort: String,
    val rpcToken: String,
    val dir: String,
    val listenAll: Boolean,
    val allowOriginAll: Boolean,
    val transport: String,
    val checkCert: Boolean,
    val saveSession: Boolean,
    val dlLimit: String,
    val ulLimit: String,
    val extEnabled: Boolean,
    val extPort: String,
    val extToken: String,
    val extListenAll: Boolean,
    val categoryEnabled: Boolean,
    val categories: List<FileCategory>,
    val maxConcurrentDownloads: String,
    val split: String,
    val maxConnections: String,
    val maxTries: String,
    val retryWait: String,
    val fileAllocation: String,
    val seedRatio: String,
    val seedTime: String,
    val btMaxPeers: String,
    val btListenPort: String,
    val btTracker: String,
    val allProxy: String,
    val connectTimeout: String,
    val timeout: String,
    val userAgent: String,
    val enableDht: Boolean?,
    val enablePeerExchange: Boolean?,
    val enableLpd: Boolean?,
    val residual: Map<String, String>,
)
