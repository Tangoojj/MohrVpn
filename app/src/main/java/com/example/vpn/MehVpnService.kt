package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import android.system.Os
import android.system.OsConstants
import com.example.core.AppLog
import com.example.core.ChildProcesses
import com.example.core.SocksProbe
import com.example.engine.ConnectStore
import com.example.engine.EngineException
import com.example.engine.EngineId
import com.example.engine.Phase
import com.example.engine.TrafficStats
import com.example.engine.TunnelEngine
import com.example.tun.HevTunnel
import com.example.engine.Engines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The VPN: one engine provides a local SOCKS port, hev-socks5-tunnel relays the TUN device into it.
 * The app itself is excluded from the tunnel, so engine cores and probes use the real network.
 */
class MehVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var connectJob: Job? = null
    @Volatile private var monitorJob: Job? = null
    @Volatile private var engine: TunnelEngine? = null
    @Volatile private var tun: ParcelFileDescriptor? = null
    @Volatile private var socksPort = 0
    private val lifecycle = SessionLifecycle(stepTimeoutMs = 3_000, hardTimeoutMs = TEARDOWN_HARD_TIMEOUT_MS)

    override fun onCreate() {
        super.onCreate()
        ChildProcesses.init(File(filesDir, PID_FILE))
        // A new service instance has no session: any core still running is an orphan of a crash or kill.
        Thread({ killLeftoverCores(includeTracked = false) }, "mohr-sweep").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                // startForegroundService() requires startForeground() promptly, whatever happens next.
                startForegroundCompat(getString(R.string.status_connecting))
                connect(EngineId.from(intent.getStringExtra(EXTRA_ENGINE)))
            }
            ACTION_DISCONNECT -> disconnect("user")
            else -> if (ConnectStore.state.value.phase == Phase.IDLE) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        AppLog.i("vpn", "revoked by system")
        disconnect("revoked")
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps a running VPN (it is a foreground service); an idle or failed
        // service has nothing to keep and goes away cleanly.
        val phase = ConnectStore.state.value.phase
        AppLog.i("vpn", "task removed (phase=$phase)")
        if (phase == Phase.IDLE || phase == Phase.ERROR) {
            disconnect("task removed")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Never block the main thread: the bounded teardown runs on its own thread.
        requestTeardown("destroy", Phase.IDLE, "")
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    private fun connect(id: EngineId) {
        val phase = ConnectStore.state.value.phase
        // Busy or still tearing the last session down: ignore (the UI does not offer connect then).
        if (phase == Phase.CONNECTING || phase == Phase.CONNECTED || phase == Phase.DISCONNECTING) return
        val e = Engines.create(this, id)
        ConnectStore.update {
            it.copy(
                phase = Phase.CONNECTING, engine = id, steps = e.steps, step = 0, detail = "", status = "",
                startedAt = System.currentTimeMillis(), events = emptyList(), traffic = TrafficStats(),
                pingMs = -1, countryCode = "", exitIp = "", selection = "", error = "",
            )
        }
        AppLog.i("vpn", "connect with ${id.title}")
        connectJob = scope.launch {
            val r = ConnectStore.reporter()
            // Waits for a teardown still in flight (bounded), then opens the session.
            val gen = lifecycle.begin()
            if (gen == null) {
                val msg = "قطع اتصال قبلی هنوز تمام نشده؛ دوباره تلاش کنید"
                ConnectStore.update { it.copy(phase = Phase.ERROR, error = msg, status = msg) }
                return@launch
            }
            if (!isActive) {
                // Disconnected while waiting for the lock: close the session we just opened.
                requestTeardown("cancelled", Phase.IDLE, "")
                return@launch
            }
            engine = e
            killLeftoverCores()
            try {
                val port = e.start(r)
                socksPort = port
                startTun(port, e.relaysUdp, gen)
                r.event(true, "تونل VPN برقرار شد", "")
                ConnectStore.update { it.copy(phase = Phase.CONNECTED, step = it.steps.size - 1, status = "اتصال شما امنه") }
                updateNotification(getString(R.string.status_connected) + " · " + id.title)
                e.confirmWorking()
                startMonitor(e)
                lookupExit(port)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                val msg = (t as? EngineException)?.message ?: "خطا: ${t.message ?: t.javaClass.simpleName}"
                AppLog.w("vpn", "connect failed", t)
                r.event(false, msg, "")
                requestTeardown("connect failed", Phase.ERROR, msg)
            }
        }
    }

    /** Brings the TUN up, unless the session [gen] was torn down meanwhile (then it never appears). */
    private fun startTun(port: Int, udpRelay: Boolean, gen: Long) = synchronized(tunLock) {
        if (!lifecycle.isOpen || lifecycle.generation != gen || connectJob?.isCancelled == true) {
            throw CancellationException("session closed before the TUN came up")
        }
        val builder = Builder()
            .setSession("MohrVpn")
            .setMtu(HevTunnel.MTU)
            .addAddress(HevTunnel.TUN_IPV4, 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer(HevTunnel.MAP_DNS)
        runCatching {
            builder.addAddress(HevTunnel.TUN_IPV6, 126)
            builder.addRoute("::", 0)
        }
        runCatching { builder.addDisallowedApplication(packageName) }
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        builder.setConfigureIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        val pfd = builder.establish() ?: throw EngineException("اجازه VPN داده نشد")
        tun = pfd
        if (!synchronized(hevLock) { HevTunnel.start(filesDir, pfd.fd, port, udpRelay) }) throw EngineException("پل TUN اجرا نشد")
        AppLog.i("vpn", "TUN up, relaying to SOCKS $port (udp=$udpRelay)")
    }

    /** Traffic counters every second; a real-traffic health check every 20 s with failover. */
    private fun startMonitor(e: TunnelEngine) {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            launch {
                var last = HevTunnel.stats()
                while (isActive) {
                    delay(1000)
                    val s = HevTunnel.stats()
                    // TUN side: tx = towards apps (download), rx = from apps (upload).
                    ConnectStore.update {
                        it.copy(traffic = TrafficStats(rxBytes = s[1], txBytes = s[3], rxPerSec = (s[1] - last[1]).coerceAtLeast(0), txPerSec = (s[3] - last[3]).coerceAtLeast(0)))
                    }
                    last = s
                }
            }
            var misses = 0
            while (isActive) {
                val d = SocksProbe.generate204(socksPort, 6_000)
                if (d >= 0) {
                    misses = 0
                    ConnectStore.update { it.copy(pingMs = d) }
                    delay(HEALTH_INTERVAL_MS)
                    continue
                }
                misses++
                AppLog.i("vpn", "health check missed ($misses)")
                if (misses < 3) {
                    delay(4_000)
                    continue
                }
                misses = 0
                recover(e)
            }
        }
    }

    private suspend fun recover(e: TunnelEngine) {
        val r = ConnectStore.reporter()
        ConnectStore.update { it.copy(status = "در حال تعویض مسیر…") }
        try {
            val port = e.recover(r)
            if (port != socksPort) {
                val fd = tun?.fd ?: throw EngineException("تونل بسته شد")
                socksPort = port
                val ok = synchronized(hevLock) {
                    HevTunnel.stop()
                    HevTunnel.start(filesDir, fd, port, e.relaysUdp)
                }
                if (!ok) throw EngineException("پل TUN اجرا نشد")
            }
            e.confirmWorking()
            ConnectStore.update { it.copy(status = "اتصال شما امنه") }
            lookupExit(port)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            val msg = (t as? EngineException)?.message ?: "اتصال قطع شد"
            AppLog.w("vpn", "recover failed", t)
            requestTeardown("recover failed", Phase.ERROR, msg)
        }
    }

    private fun lookupExit(port: Int) {
        scope.launch {
            val info = SocksProbe.exitInfo(port) ?: SocksProbe.exitInfo(port)
            if (info != null) {
                AppLog.i("vpn", "exit ${info.first}")
                ConnectStore.update { it.copy(countryCode = info.first, exitIp = info.second) }
            }
        }
    }

    private fun disconnect(reason: String) {
        if (!lifecycle.isOpen && ConnectStore.state.value.phase.let { it == Phase.IDLE || it == Phase.ERROR }) {
            stopForegroundCompat()
            stopSelf()
            return
        }
        ConnectStore.update { it.copy(phase = Phase.DISCONNECTING, status = "در حال قطع اتصال") }
        requestTeardown(reason, Phase.IDLE, "")
    }

    /** Starts the shared, idempotent teardown on its own thread (never on the main thread). */
    private fun requestTeardown(reason: String, endPhase: Phase, error: String) {
        Thread({ teardownBlocking(reason, endPhase, error) }, "mohr-teardown").start()
    }

    /**
     * The one teardown, shared by disconnect / revoke / destroy / failures. Order: stop the jobs,
     * take the TUN interface down first (so the system and other VPN apps get the network back at
     * once), stop the hev bridge under its lock with a timeout, stop the engine cores, kill any
     * leftover core process, then leave the foreground and stop the service. Bounded by
     * [TEARDOWN_HARD_TIMEOUT_MS]; past that the rest is forced.
     */
    private fun teardownBlocking(reason: String, endPhase: Phase, error: String) {
        var hevStopped = false
        var tunToClose: ParcelFileDescriptor? = null
        val steps = listOf(
            SessionLifecycle.Step("jobs", 500) {
                monitorJob?.cancel()
                monitorJob = null
                connectJob?.cancel()
            },
            SessionLifecycle.Step("tun-down", 1_000) {
                synchronized(tunLock) {
                    tunToClose = tun
                    tun = null
                }
                tunToClose?.let { detachTun(it) }
            },
            SessionLifecycle.Step("hev-stop", 3_000) {
                synchronized(hevLock) { HevTunnel.stop() }
                hevStopped = true
            },
            SessionLifecycle.Step("connect-join", 2_000) {
                val job = connectJob
                if (job != null) runBlocking { job.join() }
                connectJob = null
            },
            SessionLifecycle.Step("engine-stop", 3_000) {
                engine?.stop()
                engine = null
            },
            SessionLifecycle.Step("fd-close", 500) {
                // Only once hev let go of the descriptor number; otherwise it stays parked on /dev/null.
                if (hevStopped) runCatching { tunToClose?.close() }
            },
            SessionLifecycle.Step("sweep", 1_500) { killLeftoverCores() },
        )
        val did = lifecycle.teardown(
            reason, steps,
            force = { killLeftoverCores() },
            finish = { forced ->
                if (forced) AppLog.i("vpn", "teardown was forced")
                ConnectStore.update {
                    if (endPhase == Phase.ERROR) it.copy(phase = Phase.ERROR, error = error, status = error, pingMs = -1)
                    else it.copy(phase = Phase.IDLE, status = "", detail = "", pingMs = -1, traffic = TrafficStats())
                }
                stopForegroundCompat()
                stopSelf()
            },
        )
        if (!did) {
            // Nothing open (already torn down, in flight, or the connect is still waiting for the lock).
            connectJob?.cancel()
            ConnectStore.update {
                if (it.phase == Phase.DISCONNECTING && !lifecycle.isOpen) it.copy(phase = Phase.IDLE, status = "", detail = "", pingMs = -1, traffic = TrafficStats())
                else if (it.phase == Phase.CONNECTING && endPhase == Phase.ERROR) it.copy(phase = Phase.ERROR, error = error, status = error)
                else it
            }
        }
    }

    /**
     * Takes the TUN interface down now: the descriptor number is pointed at /dev/null, which drops
     * our reference to the tun device (the VPN network goes away), while hev, which may still be
     * reading that number, sees EOF instead of some unrelated file reusing it.
     */
    private fun detachTun(pfd: ParcelFileDescriptor) {
        val ok = runCatching {
            val nul = Os.open("/dev/null", OsConstants.O_RDWR, 0)
            try {
                // Same descriptor number, now /dev/null; the tun device's last reference is gone.
                Os.dup2(nul, pfd.fd)
            } finally {
                Os.close(nul)
            }
        }.isSuccess
        if (!ok) {
            // Fallback: close it outright.
            runCatching { pfd.close() }
        }
        AppLog.i("vpn", "TUN down" + if (ok) "" else " (closed directly)")
    }

    /** [includeTracked]: also kill cores this process started (at session start and teardown). */
    private fun killLeftoverCores(includeTracked: Boolean = true) {
        runCatching {
            ChildProcesses.killLeftovers(
                libDir = applicationInfo.nativeLibraryDir,
                uid = android.os.Process.myUid(),
                selfPid = android.os.Process.myPid(),
                includeTracked = includeTracked,
            )
        }.onFailure { AppLog.w("vpn", "leftover sweep failed", it) }
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    private fun stopForegroundCompat() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.vpn_notification_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MehVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_power_rounded)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.vpn_notification_disconnect), stop)
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "com.example.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.example.vpn.DISCONNECT"
        const val EXTRA_ENGINE = "engine"
        private const val CHANNEL = "mohr_vpn"
        private const val NOTIFICATION_ID = 7
        private const val HEALTH_INTERVAL_MS = 20_000L
        const val TEARDOWN_HARD_TIMEOUT_MS = 8_000L
        const val PID_FILE = "child_pids"
        private val hevLock = Any()
        private val tunLock = Any()

        fun connect(context: Context, id: EngineId) {
            val i = Intent(context, MehVpnService::class.java).setAction(ACTION_CONNECT).putExtra(EXTRA_ENGINE, id.name)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, MehVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
