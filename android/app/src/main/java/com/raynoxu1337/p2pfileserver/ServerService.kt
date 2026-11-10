package com.raynoxu1337.p2pfileserver

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.Inet6Address
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Foreground service that runs libp2pnode.so, which in turn runs filebrowser
 * as its child (see go/supervise.go). Closing the node's stdin stops both.
 */
class ServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile private var proc: Process? = null
    @Volatile private var stopping = false
    @Volatile private var quickRestart = false
    private var supervisor: Thread? = null
    @Volatile private var dnsServers: List<String> = emptyList()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.wantRunning = false
                shutdown()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                startForegroundCompat()
                ServerState.log("app", "restarting with new settings")
                quickRestart = true
                proc?.destroy() // supervisor loop starts it again with fresh config
                return START_STICKY
            }
        }
        startForegroundCompat()
        if (supervisor == null) start()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(ServerState.status.value), type)
    }

    @SuppressLint("WakelockTimeout")
    private fun start() {
        stopping = false
        prefs.wantRunning = true
        ServerState.update { it.copy(phase = Phase.STARTING, error = null) }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "p2pfileserver:server").apply { acquire() }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "p2pfileserver:wifi").apply {
            setReferenceCounted(false); acquire()
        }

        watchNetwork()
        scope.launch {
            ServerState.status.map { Triple(it.phase, it.online, it.sessions) to it.backendUp }
                .distinctUntilChanged()
                .collect { updateNotification() }
        }
        supervisor = thread(name = "p2pnode-supervisor") { superviseLoop() }
    }

    private fun superviseLoop() {
        var backoff = 1000L
        while (!stopping) {
            val startedAt = System.currentTimeMillis()
            try {
                val pb = buildCommand()
                ServerState.log("app", "starting node")
                val p = pb.start()
                proc = p
                ServerState.update { it.copy(phase = Phase.STARTING, error = null) }
                val err = thread(name = "p2pnode-stderr") {
                    p.errorStream.bufferedReader().forEachLine { ServerState.log("node", it) }
                }
                p.inputStream.bufferedReader().forEachLine { handleEvent(it) }
                val code = p.waitFor()
                err.join(1000)
                proc = null
                if (stopping) break
                ServerState.log("app", "node exited ($code), restarting")
            } catch (e: Exception) {
                if (stopping) break
                ServerState.log("app", "could not start node: ${e.message}")
                ServerState.update { it.copy(error = "Could not start: ${e.message}") }
            }
            ServerState.update { it.copy(online = false, backendUp = false, sessions = 0) }
            if (quickRestart || System.currentTimeMillis() - startedAt > 60_000) backoff = 1000L
            quickRestart = false
            try { Thread.sleep(backoff) } catch (_: InterruptedException) { }
            backoff = (backoff * 2).coerceAtMost(30_000)
        }
    }

    private fun buildCommand(): ProcessBuilder {
        val libDir = applicationInfo.nativeLibraryDir
        val node = File(libDir, "libp2pnode.so")
        val fb = File(libDir, "libfilebrowser.so")
        if (!node.exists() || !fb.exists()) {
            throw IllegalStateException("native binaries missing from APK (run scripts/build-native.sh before building)")
        }
        // First run (or the key was removed): create the identity before the
        // node starts; the node itself never creates keys implicitly.
        Identity.ensure(this)
        val fbDir = File(filesDir, "filebrowser").apply { mkdirs() }
        val cfg = FilebrowserConfig.write(this, prefs, fbDir)
        ServerState.update { it.copy(runningConfig = configFingerprint(prefs)) }

        val args = mutableListOf(
            node.path,
            "-key", Identity.keyFile(this).path,
            // Visitors' login cookies, so they stay logged in across restarts.
            "-cookies", File(filesDir, "p2p/cookies.json").path,
            "-target", "http://127.0.0.1:${FilebrowserConfig.PORT}",
            "-base", prefs.webBasePath,
            "-port", prefs.p2pPort.toString(),
            "-max-peers", prefs.maxPeers.toString(),
            "-name", prefs.serverName,
            "-stdin-watchdog",
        )
        if (!prefs.peerOnly) args += "-dht-server"
        if (dnsServers.isNotEmpty()) args += listOf("-dns", dnsServers.joinToString(","))
        if (prefs.turnUrl.isNotBlank()) {
            args += listOf("-turn", prefs.turnUrl, "-turn-user", prefs.turnUser, "-turn-pass", prefs.turnPass)
        }
        args += listOf("--", fb.path, "-c", cfg.path)

        return ProcessBuilder(args).directory(fbDir).also {
            val env = it.environment()
            env["HOME"] = filesDir.path
            env["TMPDIR"] = cacheDir.path
            env["FILEBROWSER_CONFIG"] = cfg.path
        }
    }

    private fun handleEvent(line: String) {
        val j = runCatching { JSONObject(line) }.getOrNull()
        if (j == null) {
            ServerState.log("node", line); return
        }
        when (j.optString("event")) {
            "ready" -> {
                val id = j.optString("peerId")
                if (id.isNotEmpty()) prefs.peerId = id
                ServerState.update { it.copy(phase = Phase.RUNNING, peerId = id) }
                ServerState.log("node", "online identity $id")
            }
            "status" -> ServerState.update {
                it.copy(
                    phase = Phase.RUNNING,
                    online = j.optBoolean("online"),
                    relayAddrs = j.optInt("relayAddrs"),
                    peers = j.optInt("peers"),
                    sessions = j.optInt("sessions"),
                )
            }
            "session" -> ServerState.update { it.copy(sessions = j.optInt("active")) }
            "traffic" -> ServerState.update {
                it.copy(
                    traffic = Traffic(
                        fileRx = j.optLong("fileRx"), fileTx = j.optLong("fileTx"),
                        fileRxRate = j.optLong("fileRxRate"), fileTxRate = j.optLong("fileTxRate"),
                        netRx = j.optLong("netRx"), netTx = j.optLong("netTx"),
                        netRxRate = j.optLong("netRxRate"), netTxRate = j.optLong("netTxRate"),
                    ),
                )
            }
            "backend" -> {
                val st = j.optString("state")
                ServerState.update { it.copy(backendUp = st == "up") }
                if (st == "crashed") ServerState.log("node", "filebrowser crashed: ${j.optString("error")}")
            }
            "fatal" -> {
                ServerState.update { it.copy(error = j.optString("error")) }
                ServerState.log("node", "fatal: ${j.optString("error")}")
            }
        }
    }

    /**
     * Restart the node when the phone switches networks (Wi-Fi ⇄ mobile):
     * relay reservations and listen addresses are tied to the old network and
     * a fresh start re-announces much faster than waiting for timeouts.
     */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        var current: Network? = null
        val restart = Runnable {
            if (!stopping && proc != null) {
                ServerState.log("app", "network changed, restarting node")
                quickRestart = true
                proc?.destroy()
            }
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (current != null && current != network) {
                        main.removeCallbacks(restart); main.postDelayed(restart, 3000)
                    }
                    current = network
                }
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                dnsServers = lp.dnsServers.map {
                    if (it is Inet6Address) "[${it.hostAddress?.substringBefore('%')}]:53" else "${it.hostAddress}:53"
                }
            }

            override fun onLost(network: Network) {
                main.post { if (current == network) ServerState.update { it.copy(online = false) } }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb
    }

    private fun shutdown() {
        stopping = true
        ServerState.update { it.copy(phase = Phase.STOPPING) }
        thread(name = "p2pnode-stop") {
            proc?.let { p ->
                runCatching { p.outputStream.close() } // stdin watchdog → graceful exit
                if (!p.waitFor(4, TimeUnit.SECONDS)) {
                    p.destroy()
                    if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
            supervisor?.interrupt()
            main.post {
                releaseLocks()
                ServerState.update { Status(phase = Phase.STOPPED, peerId = it.peerId) }
                ServerState.log("app", "stopped")
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun releaseLocks() {
        netCallback?.let {
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
        }
        netCallback = null
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
    }

    override fun onDestroy() {
        stopping = true
        runCatching { proc?.outputStream?.close() }
        proc?.destroy()
        releaseLocks()
        scope.cancel()
        if (ServerState.status.value.phase != Phase.STOPPED) {
            ServerState.update { Status(phase = Phase.STOPPED, peerId = it.peerId) }
        }
        super.onDestroy()
    }

    // --- notification ------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, "File server", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the file server is running"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun updateNotification() {
        if (stopping) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(ServerState.status.value))
    }

    private fun buildNotification(s: Status): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ServerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val text = when {
            s.phase == Phase.STARTING -> "Starting…"
            !s.backendUp -> "Starting file server…"
            !s.online -> "Looking for a relay (no internet?)"
            s.sessions == 1 -> "Online · 1 visitor connected"
            s.sessions > 1 -> "Online · ${s.sessions} visitors connected"
            else -> "Online · waiting for visitors"
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_server)
            .setContentTitle("File server running")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val ACTION_RESTART = "restart"
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java))

        fun restart(ctx: Context) =
            ContextCompat.startForegroundService(ctx, Intent(ctx, ServerService::class.java).setAction(ACTION_RESTART))

        fun stop(ctx: Context) =
            ctx.startService(Intent(ctx, ServerService::class.java).setAction(ACTION_STOP))

        /** Settings that require a restart when changed. */
        fun configFingerprint(p: Prefs) = listOf(
            p.sharedFolder, p.webBasePath, p.serverName, p.adminUser, p.adminPassword, p.readOnly,
            p.p2pPort, p.maxPeers, p.peerOnly, p.turnUrl, p.turnUser, p.turnPass,
        ).joinToString("\u0000")
    }
}
