package com.slipstream.engine

/**
 * Single source of truth for engine settings → command line mapping.
 *
 * Design contract (learned from the legacy app's bugs):
 * - Custom options are loaded FIRST; connectivity-critical params are applied
 *   LAST so they always win. The client resolves its endpoint from the same
 *   fields, so engine and client can never desync.
 * - Everything is deterministic: no random ports/tokens.
 */
enum class RpcTransport { WS, HTTP }

data class EngineSpec(
    val rpcPort: Int = 6802,
    val rpcToken: String = "gbc",
    val rpcListenAll: Boolean = false,
    val rpcAllowOriginAll: Boolean = false,
    val rpcTransport: RpcTransport = RpcTransport.WS,
    val dir: String,
    val checkCertificate: Boolean = false,
    val saveSession: Boolean = true,
    val dlLimitKbps: Int = 0,
    val ulLimitKbps: Int = 0,
    val customOptions: Map<String, String> = emptyMap(),
) {
    // aria2 listens on HTTP and WS on the same port either way — this picks
    // which transport the client uses. HTTP has no server push (no events).
    val rpcEndpoint: String
        get() =
            when (rpcTransport) {
                RpcTransport.WS -> "ws://localhost:$rpcPort/jsonrpc"
                RpcTransport.HTTP -> "http://localhost:$rpcPort/jsonrpc"
            }

    /**
     * @param sessionPath absolute path of the session file (input/save)
     * @param caCertificatePath optional CA bundle for check-certificate
     * @param parentPid app process pid — aria2 self-terminates when it dies,
     *        so a crashed/killed app never leaves an orphan engine holding the port
     */
    fun args(
        sessionPath: String,
        caCertificatePath: String? = null,
        parentPid: Int? = null,
    ): List<String> {
        val params = LinkedHashMap<String, String?>()

        // Overridable defaults
        if (saveSession) params["--save-session-interval"] = "30"
        params["--check-certificate"] = checkCertificate.toString()
        if (checkCertificate && caCertificatePath != null) {
            params["--ca-certificate"] = caCertificatePath
        }

        // User options (lose to forced params below)
        for ((key, value) in customOptions) {
            params["--${key.removePrefix("--")}"] = value
        }

        // Cannot be overridden — required for the embedded app to function
        params["--daemon"] = "false"
        params["--enable-color"] = "false"
        params["--enable-rpc"] = "true"
        params["--rpc-secret"] = rpcToken
        params["--rpc-listen-port"] = rpcPort.toString()
        params["--rpc-listen-all"] = rpcListenAll.toString()
        params["--rpc-allow-origin-all"] = rpcAllowOriginAll.toString()
        params["--dir"] = dir
        // Typed fields beat a conflicting max-overall-* key in customOptions —
        // emitted even at 0 (= unlimited) so a stale custom key can't win.
        params["--max-overall-download-limit"] = "${dlLimitKbps}K"
        params["--max-overall-upload-limit"] = "${ulLimitKbps}K"
        if (parentPid != null) params["--stop-with-process"] = parentPid.toString()
        if (saveSession) {
            params["--input-file"] = sessionPath
            params["--save-session"] = sessionPath
        }

        return params.map { (key, value) ->
            if (value.isNullOrEmpty()) key else "$key=$value"
        }
    }
}
