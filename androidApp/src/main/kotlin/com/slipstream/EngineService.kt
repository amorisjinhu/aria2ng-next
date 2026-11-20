package com.slipstream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Foreground service owning the embedded engine. Keeps the process (and the
 * aria2 child process) alive while the app is backgrounded, and applies
 * settings changes by restarting the engine — same contract as the legacy
 * app, without the legacy bugs.
 */
class EngineService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var engine: EngineProcess
    private lateinit var repo: SettingsRepo
    private val eventQueue = Channel<JsonObject>(Channel.UNLIMITED)

    // Set by a start intent, cleared by stop. The deferred start below waits
    // for DataStore; a stop landing in that window must not be undone by it.
    private var wantRunning = false

    override fun onCreate() {
        super.onCreate()
        engine = EngineProcess.get(this)
        repo = SettingsRepo(this)
        createChannel()

        scope.launch {
            repo.ensureDefaults() // seed random RPC + ext tokens on first run
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            repo.spec
                .debounce(800) // absorb burst writes → single restart
                .collect { spec -> engine.updateSpec(spec) }
        }

        // Ext-API spec is separate: swapping the little HTTP listener must not
        // restart aria2. Same debounce — settings writes land in bursts too.
        scope.launch {
            // Idempotent seeding FIRST — collecting extSpec before ensureDefaults
            // writes can hand a blank token to the server (fail-open auth hole).
            repo.ensureDefaults()
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            repo.extSpec
                .debounce(800)
                .collect { spec -> engine.updateExtApi(spec) }
        }

        // Pending ext submissions → one notification that tracks the queue:
        // posted on first request, refreshed per change, gone when drained.
        scope.launch {
            engine.extPending.collect { list ->
                if (list.isEmpty()) {
                    NotificationManagerCompat.from(this@EngineService).cancel(EXT_NOTIFICATION_ID)
                } else {
                    runCatching { postExtRequestNotification(list) }
                }
            }
        }

        // aria2.on* push events → user notification. Two-stage: the collector
        // drains the flow into an unbounded channel (never blocks the emitter);
        // the flusher waits for a first event, keeps absorbing for a short
        // window, then posts ONE notification per batch — so a torrent finish
        // firing onDownloadStop + onBtDownloadComplete + onDownloadComplete
        // in the same second doesn't notification-spam the user.
        scope.launch { engine.manager.events.collect { eventQueue.trySend(it) } }
        scope.launch {
            while (isActive) {
                val batch = mutableListOf(eventQueue.receive())
                delay(EVENT_WINDOW_MS)
                while (true) batch.add(eventQueue.tryReceive().getOrNull() ?: break)
                runCatching { postDownloadEvent(batch) } // a malformed event must not kill the loop
            }
        }

        // The persistent FGS notification must track reality: a static "is running"
        // line kept claiming success while the engine sat in Failed — with no
        // other Failed.reason surface, catastrophic failures were undiagnosable.
        scope.launch {
            engine.state.collect { st ->
                if (wantRunning) {
                    runCatching {
                        NotificationManagerCompat.from(this@EngineService).notify(NOTIFICATION_ID, buildNotification(st))
                    }
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                wantRunning = false
                engine.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_PAUSE_ALL -> scope.launch { runCatching { engine.manager.pauseAll() } }
            ACTION_RESUME_ALL -> scope.launch { runCatching { engine.manager.unpauseAll() } }
            // Notification action buttons — act on the item the notification
            // SHOWED (by id), never the queue head at click time: the head may
            // have been claimed/replaced since, and confirming a request the
            // user never saw breaks the confirm-first invariant.
            ACTION_EXT_CONFIRM -> {
                val id = intent.getLongExtra(EXTRA_PENDING_ID, -1)
                engine.extPending.value.firstOrNull { it.id == id }?.let { p ->
                    scope.launch { runCatching { engine.confirmExtAdd(p) } }
                }
            }
            ACTION_EXT_DECLINE -> {
                val id = intent.getLongExtra(EXTRA_PENDING_ID, -1)
                engine.extPending.value
                    .firstOrNull { it.id == id }
                    ?.let { engine.dismissExtAdd(it) }
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                wantRunning = true
                // Apply the persisted spec (incl. the seeded token) BEFORE the
                // first start — otherwise run 1 launches on the "gbc" fallback
                // and the debounced collector immediately restarts the engine.
                scope.launch {
                    repo.ensureDefaults()
                    engine.updateSpec(repo.spec.first())
                    if (wantRunning) engine.start()
                }
            }
        }
        return START_STICKY
    }

    // API 34 (1-arg) / API 35+ (2-arg): dataSync FGS hits its 6h/24h cap and the
    // system calls back here — not stopping in time crashes with
    // ForegroundServiceDidNotStopInTimeException. Stop the engine so aria2 can
    // save its session before the process winds down. Tell the USER too: a
    // silent stop mid-download looks like "still downloading" forever.
    @Suppress("DEPRECATION")
    override fun onTimeout(startId: Int) {
        wantRunning = false
        postTimeoutNotification()
        engine.stop()
        stopSelf()
    }

    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        wantRunning = false
        postTimeoutNotification()
        engine.stop()
        stopSelf()
    }

    private fun postTimeoutNotification() {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val n =
            NotificationCompat
                .Builder(this, EVENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notif_timeout))
                .setStyle(NotificationCompat.BigTextStyle().bigText(getString(R.string.notif_timeout)))
                .setContentIntent(openAppIntent())
                .setAutoCancel(true)
                .build()
        runCatching { NotificationManagerCompat.from(this).notify(TIMEOUT_NOTIFICATION_ID, n) }
    }

    override fun onDestroy() {
        engine.stop() // issues SIGTERM synchronously even though scope is dying
        engine.releaseExtApi() // close the HTTP listener explicitly, don't rely on process death
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun openAppIntent() =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun buildNotification(state: EngineProcess.State = engine.state.value): Notification {
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, EngineService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val pauseAll =
            PendingIntent.getService(
                this,
                2,
                Intent(this, EngineService::class.java).setAction(ACTION_PAUSE_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val resumeAll =
            PendingIntent.getService(
                this,
                3,
                Intent(this, EngineService::class.java).setAction(ACTION_RESUME_ALL),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val text =
            when (state) {
                is EngineProcess.State.Running -> getString(R.string.notif_engine_running)
                is EngineProcess.State.Starting -> getString(R.string.notif_engine_starting)
                is EngineProcess.State.Failed -> getString(R.string.notif_engine_failed, state.reason)
                EngineProcess.State.Stopped -> getString(R.string.notif_engine_stopped)
            }
        return NotificationCompat
            .Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.app_name) + " engine")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .addAction(0, getString(R.string.notif_pause_all), pauseAll)
            .addAction(0, getString(R.string.notif_resume_all), resumeAll)
            .addAction(0, getString(R.string.notif_stop), stop)
            .build()
    }

    private fun eventLabel(method: String?) =
        getString(
            when (method) {
                "aria2.onDownloadStart" -> R.string.ev_started
                "aria2.onDownloadComplete", "aria2.onBtDownloadComplete" -> R.string.ev_completed
                "aria2.onDownloadError" -> R.string.ev_error
                "aria2.onDownloadPause" -> R.string.ev_paused
                "aria2.onDownloadStop" -> R.string.ev_stopped
                else -> R.string.ev_updated
            },
        )

    private fun postDownloadEvent(batch: List<JsonObject>) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return

        val text =
            if (batch.size == 1) {
                val event = batch.first()
                // payload: {"method": "...", "params": [{"gid": "..."}]}
                val gid =
                    event["params"]
                        ?.jsonArray
                        ?.firstOrNull()
                        ?.jsonObject
                        ?.get("gid")
                        ?.jsonPrimitive
                        ?.content
                val name =
                    engine.manager.downloads.value
                        .firstOrNull { it.gid == gid }
                        ?.name
                        ?: gid?.take(8)
                        ?: "?"
                getString(R.string.ev_single, name, eventLabel(event["method"]?.jsonPrimitive?.content))
            } else {
                batch
                    .groupBy { it["method"]?.jsonPrimitive?.content }
                    .entries
                    .joinToString(", ") { (method, events) -> "${eventLabel(method)} ×${events.size}" }
            }

        val n =
            NotificationCompat
                .Builder(this, EVENT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(openAppIntent())
                .setOnlyAlertOnce(true) // batch updates stay silent
                .setAutoCancel(true)
                .build()
        // One fixed id: each batch replaces the previous post instead of
        // stacking yet another entry in the notification drawer.
        NotificationManagerCompat.from(this).notify(EVENT_NOTIFICATION_ID, n)
    }

    private fun postExtRequestNotification(list: List<PendingAdd>) {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val n =
            NotificationCompat
                .Builder(this, EXT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(getString(R.string.ext_req_title))
                .setContentText(getString(R.string.ext_req_notif, list.size, list.first().displayName))
                .setContentIntent(openAppIntent())
                // A held browser download decays fast — surface like a call:
                // heads-up banner plus full-screen intent where the OEM
                // (and API 34+) permits it. Both are best-effort.
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(openAppIntent(), true)
                .addAction(
                    0,
                    getString(R.string.ext_req_accept),
                    PendingIntent.getService(
                        this,
                        10,
                        Intent(this, EngineService::class.java)
                            .setAction(ACTION_EXT_CONFIRM)
                            .putExtra(EXTRA_PENDING_ID, list.first().id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).addAction(
                    0,
                    getString(R.string.ext_req_decline),
                    PendingIntent.getService(
                        this,
                        11,
                        Intent(this, EngineService::class.java)
                            .setAction(ACTION_EXT_DECLINE)
                            .putExtra(EXTRA_PENDING_ID, list.first().id),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).setAutoCancel(true)
                .build()
        NotificationManagerCompat.from(this).notify(EXT_NOTIFICATION_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_engine),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    EVENT_CHANNEL_ID,
                    getString(R.string.channel_events),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
            // Separate channel: IMPORTANCE_HIGH is locked at creation and must
            // never share a channel with routine engine events.
            nm.createNotificationChannel(
                NotificationChannel(
                    EXT_CHANNEL_ID,
                    getString(R.string.channel_ext_requests),
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "engine"
        const val EVENT_CHANNEL_ID = "downloads"
        const val EXT_CHANNEL_ID = "ext_requests"
        private const val NOTIFICATION_ID = 1
        private const val EVENT_NOTIFICATION_ID = 2
        private const val EXT_NOTIFICATION_ID = 3
        const val REVEAL_NOTIFICATION_ID = 4
        private const val TIMEOUT_NOTIFICATION_ID = 5
        private const val EVENT_WINDOW_MS = 2_500L
        const val ACTION_START = "com.slipstream.action.START"
        const val ACTION_STOP = "com.slipstream.action.STOP"
        const val ACTION_PAUSE_ALL = "com.slipstream.action.PAUSE_ALL"
        const val ACTION_RESUME_ALL = "com.slipstream.action.RESUME_ALL"
        const val ACTION_EXT_CONFIRM = "com.slipstream.action.EXT_CONFIRM"
        const val ACTION_EXT_DECLINE = "com.slipstream.action.EXT_DECLINE"
        const val EXTRA_PENDING_ID = "pending_id"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, EngineService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            // startService throws ISE from the background on API 26+; current
            // callers are foreground UI, but a background caller must not crash.
            runCatching {
                context.startService(Intent(context, EngineService::class.java).setAction(ACTION_STOP))
            }
        }
    }
}
