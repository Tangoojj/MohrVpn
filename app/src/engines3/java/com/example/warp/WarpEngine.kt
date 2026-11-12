package com.example.warp

import android.content.Context
import com.example.core.AppLog
import com.example.core.NativeProcess
import com.example.core.SocksProbe
import com.example.engine.ConnectReporter
import com.example.engine.EngineException
import com.example.engine.EngineId
import com.example.engine.TunnelEngine
import com.example.engine.fa
import com.example.net.NetworkInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Engine 3: WARP outer layer + Psiphon inner layer.
 *
 * Outer: the Aether core (AGPL-3.0) brings up Cloudflare WARP — MASQUE over HTTP/2 with a split
 * ClientHello first, then MASQUE over HTTP/3, then WireGuard-in-WireGuard — and exposes SOCKS5.
 * Inner: Psiphon dials its servers through that SOCKS port. If the inner layer cannot connect, the
 * WARP layer alone is used; if WARP cannot come up, Psiphon dials directly (fronted meek).
 *
 * Per network the rung that worked is remembered (and Aether keeps its own per-network endpoint
 * cache and last-good gateway), so the next connect on the same network starts there.
 */
class WarpEngine(context: Context) : TunnelEngine {
    private val app = context.applicationContext
    private val aether = File(app.applicationInfo.nativeLibraryDir, "libaether.so")
    private val baseDir = File(app.filesDir, "warp").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("warp", Context.MODE_PRIVATE)
    private val psiphon = PsiphonClient(app)

    override val id = EngineId.WARP
    override val relaysUdp = false
    override val steps = listOf("شروع", "لایه بیرونی", "لایه درونی", "پل")

    private var outer: NativeProcess? = null
    private var networkKey = ""
    private var rungUsed: Rung? = null
    @Volatile private var lastLine = ""
    @Volatile private var socksUp = false

    /** One way of bringing WARP up. */
    enum class Rung(val label: String, val args: List<String>) {
        MASQUE_H2("MASQUE · HTTP/2", listOf("--masque", "--h2", "--fragment")),
        MASQUE_H3("MASQUE · HTTP/3", listOf("--masque")),
        WOW("WARP-in-WARP", listOf("--gool")),
    }

    override suspend fun start(r: ConnectReporter): Int = withContext(Dispatchers.IO) {
        r.step(0)
        r.status("آماده‌سازی WARP")
        val net = NetworkInfo.snapshot(app)
        networkKey = net.key
        r.event(true, "راه‌اندازی WARP", "شبکه: ${net.label}")
        connect(r)
    }

    override suspend fun recover(r: ConnectReporter): Int = withContext(Dispatchers.IO) {
        stop()
        r.event(false, "اتصال WARP قطع شد", "اتصال دوباره")
        connect(r)
    }

    override fun confirmWorking() {
        rungUsed?.let { prefs.edit().putString("rung:$networkKey", it.name).apply() }
        // Keep the identity in the shared slot in case the core re-registered.
        val netCfg = File(netDir(), "aether.toml")
        if (netCfg.isFile) runCatching { netCfg.copyTo(File(baseDir, "aether.toml"), overwrite = true) }
    }

    private suspend fun connect(r: ConnectReporter): Int {
        val socksPort = NativeProcess.freePorts(1).first()
        val warpPort = bringUpOuter(r)
        r.step(2)
        r.status(if (warpPort != null) "Psiphon روی WARP" else "Psiphon مستقیم")
        r.detail(if (warpPort != null) "Psiphon via WARP SOCKS" else "Psiphon fronted meek")
        psiphon.onNotice = { n -> noticeToDetail(n)?.let(r::detail) }
        val ok = try {
            psiphon.start(socksPort, warpPort, if (warpPort != null) 30 else 60)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            AppLog.w("psiphon", "start threw", t)
            false
        }
        if (ok) {
            r.event(true, "لایه درونی Psiphon وصل شد", psiphon.region.ifBlank { "" })
            r.step(3)
            r.status("بررسی پل")
            val d = SocksProbe.generate204(socksPort, 12_000)
            if (d >= 0) {
                r.event(true, "پل آماده است (${fa(d)} ms)", if (warpPort != null) "WARP + Psiphon" else "Psiphon")
                r.selection(if (warpPort != null) "${rungUsed?.label} + Psiphon" else "Psiphon")
                return socksPort
            }
            r.event(false, "رد شد: Psiphon ترافیک عبور نداد", "")
        } else {
            r.event(false, "لایه درونی Psiphon وصل نشد", psiphon.lastNotice.take(100))
        }
        psiphon.stop()
        if (warpPort != null) {
            // WARP alone still gives a working (Cloudflare) exit.
            r.step(3)
            r.status("استفاده از WARP بدون لایه درونی")
            val d = SocksProbe.generate204(warpPort, 10_000)
            if (d >= 0) {
                r.event(true, "پل WARP آماده است (${fa(d)} ms)", rungUsed?.label.orEmpty())
                r.selection(rungUsed?.label.orEmpty())
                return warpPort
            }
        }
        stop()
        throw EngineException("WARP و Psiphon وصل نشدند")
    }

    /** Tries the rungs (remembered one first) and returns the WARP SOCKS port, or null. */
    private suspend fun bringUpOuter(r: ConnectReporter): Int? {
        if (!aether.isFile) {
            r.event(false, "هسته WARP برای این دستگاه موجود نیست", "")
            return null
        }
        r.step(1)
        val remembered = prefs.getString("rung:$networkKey", null)?.let { n -> Rung.entries.firstOrNull { it.name == n } }
        val order = listOfNotNull(remembered) + Rung.entries.filter { it != remembered }
        if (remembered != null) r.event(true, "مسیر ذخیره‌شده این شبکه", remembered.label)
        for (rung in order) {
            coroutineContext.ensureActive()
            val port = NativeProcess.freePorts(1).first()
            r.status("لایه بیرونی: ${rung.label}")
            r.detail("Connecting ${rung.label}")
            if (runOuter(rung, port, r)) {
                rungUsed = rung
                return port
            }
            outer?.stop()
            outer = null
        }
        return null
    }

    private fun netDir(): File = File(baseDir, "net-" + networkKey.replace(Regex("[^A-Za-z0-9]"), "_")).apply { mkdirs() }

    private suspend fun runOuter(rung: Rung, port: Int, r: ConnectReporter): Boolean {
        val dir = netDir()
        val cfg = File(dir, "aether.toml")
        val shared = File(baseDir, "aether.toml")
        if (!cfg.isFile && shared.isFile) runCatching { shared.copyTo(cfg) }
        socksUp = false
        val args = listOf(
            "--bind", "127.0.0.1:$port",
            "--config", cfg.absolutePath,
            "--quick-reconnect",
            "--scan", "turbo",
            "--startup-secs", "35",
            "--log-level", "info",
        ) + rung.args
        val env = mapOf(
            "AETHER_MASQUE_CACHE" to File(dir, "masque-cache.json").absolutePath,
            "HOME" to dir.absolutePath,
            "RUST_LOG" to "info",
        )
        val p = NativeProcess(aether, args, dir, "warp", env) { line -> onOuterLine(line, r) }
        p.start()
        outer = p
        val deadline = System.currentTimeMillis() + OUTER_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            if (!p.isAlive) {
                r.event(false, "رد شد: ${rung.label}", lastLine.take(100))
                return false
            }
            if (socksUp && NativeProcess.portOpen(port)) {
                val d = SocksProbe.generate204(port, 10_000)
                if (d >= 0) {
                    r.event(true, "لایه بیرونی ${rung.label} (${fa(d)} ms)", "")
                    return true
                }
                r.event(false, "رد شد: ${rung.label} ترافیک عبور نداد", "")
                return false
            }
            delay(300)
        }
        r.event(false, "رد شد: مهلت ${rung.label} تمام شد", lastLine.take(100))
        return false
    }

    private fun onOuterLine(raw: String, r: ConnectReporter) {
        // Strip env_logger prefixes ("[2026-..Z INFO aether] ").
        val line = raw.replace(Regex("^\\[[^\\]]*\\]\\s*"), "").trim()
        if (line.isEmpty()) return
        // The core's own name is not shown in the app (trademark); it is "WARP core" here.
        val shown = line.replace(Regex("aether", RegexOption.IGNORE_CASE), "warp-core")
        lastLine = shown
        AppLog.i("warp", shown.take(200))
        if (line.contains("socks5 server listening")) socksUp = true
        when {
            shown.startsWith("[+]") -> r.detail(shown.removePrefix("[+]").trim().take(60))
            shown.startsWith("[*]") -> r.detail(shown.removePrefix("[*]").trim().take(60))
            shown.startsWith("[-]") || shown.startsWith("[!]") ->
                r.event(false, "رد شد", shown.drop(3).trim().take(100))
        }
    }

    private fun noticeToDetail(notice: String): String? {
        val type = Regex("\"noticeType\":\"([A-Za-z]+)\"").find(notice)?.groupValues?.get(1) ?: return null
        return when (type) {
            "ConnectingServer" -> "Psiphon: connecting server"
            "ConnectedServer" -> "Psiphon: connected"
            "CandidateServers" -> "Psiphon: candidate servers"
            "ActiveTunnel" -> "Psiphon: tunnel active"
            else -> null
        }
    }

    override fun stop() {
        psiphon.stop()
        outer?.stop()
        outer = null
    }

    companion object {
        const val OUTER_TIMEOUT_MS = 45_000L
    }
}
