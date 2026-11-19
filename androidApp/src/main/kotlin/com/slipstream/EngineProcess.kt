package com.slipstream

import android.content.Context
import android.util.Log
import com.slipstream.engine.EngineSpec
import com.slipstream.rpc.Aria2Client
import com.slipstream.rpc.DownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Owns the embedded aria2-next child process, the RPC client bound to it,
 * and the DownloadManager polling it. Engine settings come from EngineSpec;
 * updateSpec() rebuilds client+manager and restarts if running.
 */
class EngineProcess private constructor(
    private val context: Context,
    spec: EngineSpec,
) {
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                    Log.e(TAG, "engine coroutine failed", e)
                },
        )

    @Volatile
    private var process: Process? = null
    private var logJob: kotlinx.coroutines.Job? = null
    private var startJob: kotlinx.coroutines.Job? = null
    private var teardownJob: kotlinx.coroutines.Job? = null

    @Volatile
    var spec: EngineSpec = spec
        private set

    @Volatile
    var client: Aria2Client = Aria2Client(spec.rpcEndpoint, spec.rpcToken, scope)
        private set

    // Bound once at init; resolves the current client so spec changes
    // don't orphan the UI's collected StateFlow.
    val manager: DownloadManager = DownloadManager({ client }, scope, ::appendLog)

    private val _state = MutableStateFlow<State>(State.Stopped)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val _engineVersion = MutableStateFlow<String?>(null)
    val engineVersion: StateFlow<String?> = _engineVersion.asStateFlow()

    sealed class State {
        data object Stopped : State()

        data object Starting : State()

        data object Running : State()

        data class Failed(
            val reason: String,
        ) : State()
    }

    @Synchronized
    fun updateSpec(newSpec: EngineSpec) {
        if (newSpec == spec) return
        // Tear down while `client` is still the OLD one: stop() queues a
        // deferred client.close() — swapping first would let it kill the new client.
        // startJob covers the Starting window where `process` is still null.
        val wasRunning =
            process?.isAlive == true ||
                startJob?.isActive == true ||
                _state.value is State.Failed
        if (wasRunning) stop()
        spec = newSpec
        client = Aria2Client(newSpec.rpcEndpoint, newSpec.rpcToken, scope)
        if (wasRunning) start()
    }

    private fun appendLog(line: String) {
        _log.update { (it + line).takeLast(200) }
    }

    @Synchronized
    fun start() {
        // startJob guard: a second tap during Starting must not spawn a twin
        if (process?.isAlive == true || startJob?.isActive == true) return
        _state.value = State.Starting
        startJob = scope.launch { runStart() }
    }

    private suspend fun runStart() {
        // Bind this attempt to the client/spec that existed when start() ran —
        // updateSpec may swap `client` mid-flight; we must not chase it.
        val c = client
        var p: Process? = null
        try {
            // The predecessor may still be holding the RPC port (aria2 saves
            // its session on SIGTERM). Join the serialized teardown chain
            // before spawning, otherwise the new process fails to bind.
            teardownJob?.join()
            coroutineContext.ensureActive()

            val exec = File(context.applicationInfo.nativeLibraryDir, "libaria2c.so")
            check(exec.exists()) { "engine binary missing: ${exec.absolutePath}" }

            val session = File(context.filesDir, "session")
            if (spec.saveSession && !session.exists()) session.createNewFile()

            val isPublicDir =
                spec.dir.startsWith("/storage/emulated/0/") &&
                    !spec.dir.contains("/Android/data/")
            if (isPublicDir &&
                android.os.Build.VERSION.SDK_INT >= 30 &&
                !android.os.Environment.isExternalStorageManager()
            ) {
                appendLog("!! warning: '${spec.dir}' is shared storage — grant 'All files access' in settings or downloads will fail")
            }

            val args = mutableListOf(exec.absolutePath) + spec.args(session.absolutePath)
            Log.i(TAG, "exec: ${args.joinToString(" ")}")

            val spawned =
                ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .apply {
                        // aria2-next keeps resume/stream state under $HOME/.local/state
                        environment()["HOME"] = context.filesDir.absolutePath
                    }.start()
            p = spawned
            process = spawned
            // stop() may have raced us between spawn and here — don't resurrect
            if (!coroutineContext.isActive) return abortStart(spawned, c)
            _state.value = State.Running

            logJob =
                scope.launch {
                    try {
                        spawned.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach {
                                appendLog(it)
                                Log.i(TAG, "[aria2] $it")
                            }
                        }
                    } catch (_: java.io.IOException) {
                        // stream closed on stop()/restart() — normal teardown
                    }
                }

            // Wait for RPC to come up, then verify via getVersion
            var attempts = 0
            while (attempts++ < 20) {
                try {
                    c.connect()
                    val v = c.getVersion()
                    coroutineContext.ensureActive()
                    _engineVersion.value = v.version
                    appendLog(">>> aria2-next ${v.version} ready on ${spec.rpcEndpoint}")
                    manager.start()
                    watchProcess(spawned)
                    return
                } catch (e: Exception) {
                    // ensureActive distinguishes real cancellation from the
                    // TimeoutCancellationException a hung RPC round-trip raises
                    coroutineContext.ensureActive()
                    if (!spawned.isAlive) throw e
                    delay(300)
                }
            }
            // RPC never came up — kill the process or it becomes a zombie
            // that blocks every future start() behind the isAlive guard.
            _state.value = State.Failed("RPC not responding")
            abortStart(spawned, c)
        } catch (e: CancellationException) {
            if (p != null) abortStart(p, c)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "engine start failed", e)
            if (p != null) abortStart(p, c)
            if (coroutineContext.isActive) _state.value = State.Failed(e.message ?: e.toString())
        }
    }

    /** Tears down a half-started process; the caller decides the final state. */
    private fun abortStart(
        p: Process,
        c: Aria2Client,
    ) {
        if (process === p) process = null
        p.destroy()
        scope.launch { runCatching { c.close() } }
        queueTeardown(p)
    }

    /** Marks the engine Failed if aria2 dies on its own while Running. */
    private fun watchProcess(p: Process) {
        scope.launch {
            p.waitFor()
            if (process === p) {
                process = null
                manager.stop()
                _engineVersion.value = null
                val code = runCatching { p.exitValue() }.getOrDefault(-1)
                appendLog("!! aria2 exited (code $code)")
                if (_state.value is State.Running) {
                    _state.value = State.Failed("aria2 exited (code $code)")
                }
            }
        }
    }

    /**
     * Serial chain of post-destroy waits: each teardown joins the previous one,
     * so [runStart] joining [teardownJob] guarantees the port is released.
     * SIGTERM itself is issued by the caller — it must fire even if the scope
     * is already dead (e.g. Service.onDestroy).
     */
    @Synchronized
    private fun queueTeardown(p: Process?) {
        val prev = teardownJob
        teardownJob =
            scope.launch {
                prev?.join()
                if (p != null && !p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly()
            }
    }

    @Synchronized
    fun stop() {
        // Cancel any in-flight start before touching process state — otherwise
        // the start coroutine would finish behind our back and resurrect it.
        startJob?.cancel()
        startJob = null
        manager.stop()
        val p = process
        process = null
        p?.destroy() // SIGTERM now; the wait happens in the teardown chain
        logJob?.cancel()
        logJob = null
        val c = client // capture: the deferred lambda must not see a swapped-in client
        scope.launch { runCatching { c.close() } }
        queueTeardown(p)
        _engineVersion.value = null
        _state.value = State.Stopped
    }

    @Synchronized
    fun restart() {
        stop()
        start()
    }

    companion object {
        private const val TAG = "EngineProcess"

        /** App-private external dir: no storage permission needed, still user-visible in Files. */
        fun defaultDownloadDir(context: Context): String =
            context.getExternalFilesDir("downloads")?.absolutePath
                ?: File(context.filesDir, "downloads").absolutePath

        @Volatile
        private var instance: EngineProcess? = null

        fun get(context: Context): EngineProcess =
            instance ?: synchronized(this) {
                instance ?: EngineProcess(
                    context.applicationContext,
                    EngineSpec(dir = defaultDownloadDir(context)),
                ).also { instance = it }
            }
    }
}
