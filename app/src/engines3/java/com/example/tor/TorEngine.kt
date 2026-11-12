package com.example.tor

import android.content.Context
import android.os.Build
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
 * Engine 2: Tor over WebTunnel bridges.
 *
 * Runs the Tor Project's Android `tor` with `lyrebird` as the WebTunnel transport. Bridges that
 * delivered a descriptor on this network are remembered and offered first next time; the rest
 * of the slots are filled at random from the bundled public WebTunnel list.
 */
class TorEngine(context: Context) : TunnelEngine {
    private val app = context.applicationContext
    private val libDir = File(app.applicationInfo.nativeLibraryDir)
    private val tor = File(libDir, "libtor.so")
    private val lyrebird = File(libDir, "liblyrebird.so")
    private val workDir = File(app.filesDir, "tor").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("tor_bridges", Context.MODE_PRIVATE)

    override val id = EngineId.TOR
    override val relaysUdp = false
    override val steps = listOf("پل‌ها", "انتقال", "دایرکتوری", "مدارها", "تونل")

    private var process: NativeProcess? = null
    @Volatile private var progress = 0
    @Volatile private var phaseText = ""
    @Volatile private var lastWarn = ""
    /** Every tor output line is logged until the first "Bootstrapped" line, so start-up failures are visible. */
    @Volatile private var bootstrapSeen = false
    private val goodFingerprints = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var port = 0
    private var attempt = 0
    private var networkKey = ""

    override suspend fun start(r: ConnectReporter): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26) throw EngineException("Tor به اندروید ۸ یا بالاتر نیاز دارد")
        if (!tor.isFile || !lyrebird.isFile) throw EngineException("Tor برای این دستگاه موجود نیست")
        attempt = 0
        port = NativeProcess.freePorts(1).first()
        networkKey = NetworkInfo.snapshot(app).key
        bootstrapWithRetries(r)
    }

    override suspend fun recover(r: ConnectReporter): Int = withContext(Dispatchers.IO) {
        stop()
        r.event(false, "اتصال Tor قطع شد", "راه‌اندازی دوباره با پل‌های دیگر")
        bootstrapWithRetries(r)
    }

    override fun confirmWorking() {
        val fps = goodFingerprints.toList()
        if (fps.isNotEmpty()) prefs.edit().putString("good:$networkKey", fps.take(12).joinToString(",")).apply()
    }

    private suspend fun bootstrapWithRetries(r: ConnectReporter): Int {
        val bridges = TorConfig.loadBridges(app.assets.open("tor/bridges-webtunnel.txt").bufferedReader().readText())
        if (bridges.isEmpty()) throw EngineException("لیست پل‌های Tor خالی است")
        val remembered = prefs.getString("good:$networkKey", null).orEmpty().split(',').filter { it.isNotBlank() }
        repeat(MAX_ATTEMPTS) {
            coroutineContext.ensureActive()
            r.step(0)
            r.status("در حال بررسی بریج‌های WebTunnel")
            val subset = TorConfig.pickBridges(bridges, BRIDGES_PER_TRY, preferred = if (attempt == 0) remembered else emptyList(), seed = System.nanoTime() + attempt)
            attempt++
            val known = subset.count { b -> remembered.any { b.contains(it) } }
            r.event(true, "انتخاب ${fa(subset.size)} پل WebTunnel", if (known > 0) "${fa(known)} پل از حافظه این شبکه" else "انتخاب تصادفی")
            if (runTor(subset, r)) return port
            stop()
        }
        throw EngineException("Tor وصل نشد")
    }

    private suspend fun runTor(bridges: List<String>, r: ConnectReporter): Boolean {
        progress = 0
        goodFingerprints.clear()
        val torrc = File(workDir, "torrc")
        torrc.writeText(
            TorConfig.torrc(
                dataDir = File(workDir, "data").apply { mkdirs() }.absolutePath,
                socksPort = port,
                lyrebird = lyrebird.absolutePath,
                bridges = bridges,
                ownerPid = android.os.Process.myPid(),
            ),
        )
        if (!verifyConfig(torrc, r)) return false
        bootstrapSeen = false
        lastWarn = ""
        val p = NativeProcess(tor, listOf("-f", torrc.absolutePath), workDir, "tor") { line -> onLine(line) }
        p.start()
        process = p
        AppLog.i("tor", "started tor (pid ${p.pid ?: "?"})")
        r.detail("TLS reachability probe")
        val deadline = System.currentTimeMillis() + BOOTSTRAP_TIMEOUT_MS
        var lastChange = System.currentTimeMillis()
        var lastSeen = -1
        var lastStep = -1
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()
            if (!p.isAlive) {
                reportDeath(p, r)
                return false
            }
            val now = progress
            if (now != lastSeen) {
                lastSeen = now
                lastChange = System.currentTimeMillis()
                val step = TorConfig.stepFor(now)
                if (step != lastStep) {
                    if (lastStep >= 0) r.event(true, steps[lastStep], "")
                    lastStep = step
                }
                r.step(step)
                r.status("اتصال Tor ${fa(now)}٪")
                r.detail(phaseText.ifBlank { "Bootstrapping" })
            }
            if (now >= 100) break
            if (System.currentTimeMillis() - lastChange > STALL_MS) {
                r.event(false, "رد شد: پیشرفتی نبود (${fa(now)}٪)", lastWarn)
                return false
            }
            delay(300)
        }
        if (progress < 100) {
            r.event(false, "مهلت راه‌اندازی Tor تمام شد", lastWarn)
            return false
        }
        r.step(4)
        r.status("بررسی تونل Tor")
        r.detail("generate_204 via Tor")
        val d = SocksProbe.generate204(port, 20_000)
        if (d < 0) {
            r.event(false, "رد شد: تونل ترافیک عبور نداد", "")
            return false
        }
        r.event(true, "تونل Tor آماده است (${fa(d)} ms)", "${fa(goodFingerprints.size)} پل پاسخ داد")
        r.selection("WebTunnel · ${fa(goodFingerprints.size.coerceAtLeast(1))} پل")
        AppLog.i("tor", "ready on SOCKS $port (${d} ms)")
        return true
    }

    /**
     * `tor --verify-config` first: a torrc error, or a binary that cannot even link on this device,
     * shows up here with its real output instead of as a silent exit.
     */
    private fun verifyConfig(torrc: File, r: ConnectReporter): Boolean {
        val (code, out) = try {
            NativeProcess(tor, listOf("--verify-config", "-f", torrc.absolutePath), workDir, "tor").runToEnd(VERIFY_TIMEOUT_MS)
        } catch (t: Throwable) {
            AppLog.w("tor", "verify-config could not run", t)
            r.event(false, "Tor اجرا نشد", "${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(160))
            return false
        }
        val lines = out.lines().map(String::trim).filter(String::isNotEmpty)
        if (code == 0) {
            AppLog.i("tor", "verify-config ok")
            return true
        }
        if (code == -1) {
            // Timed out: not a verdict on the config; go on and let the real run report.
            AppLog.i("tor", "verify-config timed out, continuing")
            return true
        }
        AppLog.i("tor", "verify-config failed, exit=$code")
        lines.takeLast(NativeProcess.TAIL_LINES).forEach { AppLog.i("tor", "  $it") }
        val reason = lines.firstOrNull { it.contains("[err]") || it.contains("[warn]") || it.contains("CANNOT LINK") }
            ?: lines.lastOrNull().orEmpty()
        r.event(false, "تنظیمات Tor رد شد (کد ${fa(code)})", reason.substringAfter("] ").take(160))
        return false
    }

    /** The tor process died: put its exit code and last output lines in the in-app log. */
    private fun reportDeath(p: NativeProcess, r: ConnectReporter) {
        // Give the reader a moment to drain the last lines.
        Thread.sleep(150)
        val code = p.exitCode()
        val tail = p.tail()
        AppLog.i("tor", "tor exited, exit=${code ?: "?"}; last ${tail.size} line(s):")
        tail.forEach { AppLog.i("tor", "  $it") }
        val reason = lastWarn.ifBlank { tail.lastOrNull { it.isNotBlank() }.orEmpty() }
        r.event(false, "Tor متوقف شد (کد ${code?.let { fa(it) } ?: "؟"})", reason.take(160))
    }

    private fun onLine(line: String) {
        if (!bootstrapSeen) {
            if (line.contains("Bootstrapped")) bootstrapSeen = true else AppLog.i("tor", line.take(200))
        }
        Regex("Bootstrapped (\\d+)% \\(([a-z_]+)\\): (.*)").find(line)?.let { m ->
            progress = m.groupValues[1].toIntOrNull() ?: progress
            phaseText = m.groupValues[3].take(60)
            AppLog.i("tor", "${m.groupValues[1]}% ${m.groupValues[3]}")
        }
        Regex("new bridge descriptor .*\\$([0-9A-F]{40})~").find(line)?.let { goodFingerprints += it.groupValues[1] }
        if (line.contains("[warn]") || line.contains("[err]")) {
            lastWarn = line.substringAfter("] ").take(160)
            if (bootstrapSeen) AppLog.i("tor", lastWarn)
        } else if (bootstrapSeen && (line.contains("CANNOT LINK") || line.contains("error", ignoreCase = true))) {
            AppLog.i("tor", line.take(200))
        }
    }

    override fun stop() {
        process?.stop()
        process = null
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        const val BRIDGES_PER_TRY = 16
        const val BOOTSTRAP_TIMEOUT_MS = 150_000L
        const val STALL_MS = 60_000L
        const val VERIFY_TIMEOUT_MS = 20_000L
    }
}
