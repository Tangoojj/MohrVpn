package com.example.xray

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
import com.example.net.NetworkSnapshot
import com.example.net.Operator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Engine 1: Xray auto.
 *
 * Connect = operator → DPI type (cached per network) → Patterniha list → ordered candidates
 * (ECH / fragment / clean-IP copies) → batch real-delay test → best link. When no Patterniha link
 * works (or that list cannot be loaded), a rotating sample of the barry-far list (vless/trojan,
 * TLS/Reality) is tested the same way. Working links are ranked per network (and per fragment
 * switch); the next connect on the same network starts from that ranking, and a link that stops
 * working is marked and replaced by the next one.
 *
 * Fragment switch ([XraySettings.fragment]) is read once per connect: every test and run config
 * of that session routes TLS/Reality TCP through a freedom fragment dialer. Toggling it is
 * locked in the UI while connected, so it takes effect on the next connect.
 */
class XrayEngine(context: Context) : TunnelEngine {
    private val app = context.applicationContext
    private val store = AutoStore(app)
    private val ech = EchKeys(store.echCache)
    private val xray = File(app.applicationInfo.nativeLibraryDir, "libxray.so")
    private val workDir = File(app.filesDir, "xray").apply { mkdirs() }
    private val tester = BatchTester(xray, workDir, ech)
    private val settings = XraySettings(app)

    override val id = EngineId.XRAY
    override val relaysUdp = true
    override val steps = listOf("اپراتور", "دریافت لیست", "IP تمیز", "تست", "اتصال")

    private var network: NetworkSnapshot? = null
    private var runCore: NativeProcess? = null
    private var current: String? = null
    private var socksPort = 0
    /** Fragment switch for this session (read at [start]). */
    private var fragment = false
    /** Links already given up on during this session. */
    private val burned = HashSet<String>()

    override suspend fun start(r: ConnectReporter): Int = withContext(Dispatchers.IO) {
        if (!xray.isFile) throw EngineException("هسته Xray برای این دستگاه موجود نیست")
        burned.clear()
        fragment = settings.fragment
        tester.fragment = fragment
        socksPort = NativeProcess.freePorts(1).first()
        r.step(0)
        r.status("تشخیص اپراتور")
        val net = NetworkInfo.snapshot(app)
        network = net
        r.event(true, "شبکه: ${net.label}", if (net.hasIpv6) "IPv6 دارد" else "")
        AppLog.i("xray", "network ${net.key} operator=${net.operator} ipv6=${net.hasIpv6} fragment=$fragment")
        if (fragment) r.event(true, "فرگمنت روشن است", "tlshello · 100-200 · 10-20 ms")
        connectBest(net, r)
    }

    override suspend fun recover(r: ConnectReporter): Int = withContext(Dispatchers.IO) {
        val net = network ?: NetworkInfo.snapshot(app).also { network = it }
        current?.let { uri ->
            store.updateRanking(rankKey(net)) { Ranking.fail(it, uri) }
            burned += uri
            r.event(false, "کانفیگ از کار افتاد", ProxyLink.parse(uri)?.name.orEmpty())
        }
        stopCore()
        connectBest(net, r)
    }

    override fun confirmWorking() {
        val net = network ?: return
        val uri = current ?: return
        store.updateRanking(rankKey(net)) { Ranking.succeed(it, uri) }
    }

    /** Ranking key for [net] with this session's fragment switch. */
    private fun rankKey(net: NetworkSnapshot) = XraySettings.rankKey(net.key, fragment)

    override fun stop() {
        stopCore()
        current = null
    }

    private fun stopCore() {
        runCore?.stop()
        runCore = null
    }

    /** Remembered links for this network first; then a fresh bootstrap (forcing a list refresh if needed). */
    private suspend fun connectBest(net: NetworkSnapshot, r: ConnectReporter): Int {
        val ranked = Ranking.healthy(store.ranking(rankKey(net)), System.currentTimeMillis()).filter { it.uri !in burned }
        if (ranked.isNotEmpty()) {
            r.event(true, "کانفیگ‌های ذخیره‌شده این شبکه", "${fa(ranked.size)} مورد")
            tryRanked(net, ranked.take(3), r)?.let { return it }
        }
        for (forceList in listOf(false, true)) {
            coroutineContext.ensureActive()
            val boot = bootstrap(net, forceList, r)
            if (boot == null && !forceList) continue
            val list = Ranking.healthy(store.ranking(rankKey(net)), System.currentTimeMillis()).filter { it.uri !in burned }
            tryRanked(net, list, r)?.let { return it }
            if (boot?.fromCache == false) break
        }
        throw EngineException("هیچ کانفیگ سالمی پیدا نشد")
    }

    private suspend fun tryRanked(net: NetworkSnapshot, entries: List<RankedEntry>, r: ConnectReporter): Int? {
        entries.forEachIndexed { i, e ->
            coroutineContext.ensureActive()
            val link = ProxyLink.parse(e.uri) ?: return@forEachIndexed
            r.step(4)
            r.status("اتصال با مسیر ${fa(i + 1)}/${fa(entries.size)}")
            r.detail(link.name.ifBlank { link.address })
            if (runLink(link, r)) {
                store.updateRanking(rankKey(net)) { Ranking.succeed(it, e.uri) }
                return socksPort
            }
            store.updateRanking(rankKey(net)) { Ranking.fail(it, e.uri) }
            burned += e.uri
        }
        return null
    }

    /** Starts the live core with [link] and checks it carries traffic. */
    private suspend fun runLink(link: ProxyLink, r: ConnectReporter): Boolean {
        stopCore()
        val key = if (link.ech.isNotBlank()) ech.resolve(link.ech) else null
        val config = runCatching { XrayConfig.runConfig(link, socksPort, key, fragment) }.getOrElse {
            r.event(false, "کانفیگ نامعتبر", link.name)
            return false
        }
        val file = File(workDir, "xray-run.json").apply { writeText(config) }
        val core = NativeProcess(xray, listOf("run", "-c", file.absolutePath), workDir, "xray")
        core.start()
        runCore = core
        if (!core.awaitPorts(listOf(socksPort), 6_000)) {
            r.event(false, "هسته Xray اجرا نشد", link.name)
            stopCore()
            return false
        }
        var delay = SocksProbe.generate204(socksPort, 7_000)
        if (delay < 0) delay = SocksProbe.generate204(socksPort, 7_000)
        if (delay < 0) {
            r.event(false, "رد شد: ترافیک عبور نکرد", link.name)
            stopCore()
            return false
        }
        current = link.raw
        r.event(true, "وصل شد (${fa(delay)} ms)", link.name)
        r.selection("Auto · " + shortName(link))
        AppLog.i("xray", "connected via ${link.name} (${delay} ms)")
        return true
    }

    private class Bootstrapped(val fromCache: Boolean)

    /** Full auto flow; saves the fresh ranking. Returns null when the list could not be loaded. */
    private suspend fun bootstrap(net: NetworkSnapshot, forceList: Boolean, r: ConnectReporter): Bootstrapped? {
        // 1. DPI type (remembered per network)
        r.step(0)
        var dpi = store.dpi(net.key)
        var edges = store.cleanIps(net.key)
        if (dpi == null || edges.isEmpty()) {
            r.status("تشخیص نوع فیلترینگ")
            r.detail("TLS probe · cloudflare-ech.com")
            val counts = DpiProbe().run()
            val hint = when (net.operator) {
                Operator.MCI -> DpiType.SNI
                Operator.IRANCELL, Operator.RIGHTEL -> DpiType.IP
                Operator.UNKNOWN -> DpiType.UNKNOWN
            }
            dpi = DpiDecision.decide(counts.reachable, counts.fragmentedOk, if (net.isWifi) DpiType.UNKNOWN else hint)
            AppLog.i("xray", "DPI reachable=${counts.reachable} split-ok=${counts.fragmentedOk} → $dpi")
            r.event(counts.reachable > 0, "نوع فیلترینگ: ${dpiLabel(dpi)}", "دسترس‌پذیر ${fa(counts.reachable)} · عبور با تکه‌تکه ${fa(counts.fragmentedOk)}")
            if (dpi != DpiType.UNKNOWN) store.saveDpi(net.key, dpi)
            if (counts.edges.isNotEmpty()) {
                edges = counts.edges
                store.saveCleanIps(net.key, edges)
            }
        } else {
            r.event(true, "نوع فیلترینگ: ${dpiLabel(dpi)}", "از حافظه این شبکه")
        }
        coroutineContext.ensureActive()

        // 2. List
        r.step(1)
        r.status("دریافت لیست کانفیگ")
        r.detail("GitHub → jsDelivr → API")
        val list = ListFetcher(workDir).load(force = forceList)
        if (list == null) r.event(false, "لیست کانفیگ دریافت نشد", "GitHub / jsDelivr / API")
        val all = list?.let { AutoList.parse(it.text) }.orEmpty()
        if (list != null) r.event(all.isNotEmpty(), "لیست: ${fa(all.size)} کانفیگ", list.source)
        if (all.isEmpty()) {
            // Patterniha unavailable: go straight to the second source.
            val barry = barryStage(forceList, r) ?: return null
            return Bootstrapped(barry.fromCache)
        }

        // 3. Clean IPs + order
        r.step(2)
        r.status("آماده‌سازی IP تمیز")
        val cleanIps = (edges + AutoList.CLOUDFLARE_EDGES).distinct()
        val ordered = AutoList.order(all, dpi, net.hasIpv6, cleanIps, MAX_CANDIDATES).filter { it.uri !in burned }
        val copies = ordered.count { it.cleanIpCopy }
        r.detail(cleanIps.take(3).joinToString(" · "))
        r.event(true, "IP تمیز: ${fa(edges.size)} پاسخ‌گو", "${fa(copies)} نسخه روی IP تمیز")

        // 4. Batch test
        r.step(3)
        val found = batchTest(net, ordered, MAX_BATCHES, r)
        // 5. Second source: barry-far, only when no Patterniha link worked.
        if (found == 0) {
            r.event(false, "پترنیها: کانفیگ سالمی پیدا نشد", "منبع دوم: barry-far")
            val barry = barryStage(forceList, r)
            return Bootstrapped(list!!.fromCache && (barry?.fromCache ?: true))
        }
        return Bootstrapped(list!!.fromCache)
    }

    /** Tests [ordered] in batches (at most [maxBatches]); saves the hits to the ranking. Returns the number of hits. */
    private suspend fun batchTest(net: NetworkSnapshot, ordered: List<Candidate>, maxBatches: Int, r: ConnectReporter): Int {
        val hits = ArrayList<BatchTester.Hit>()
        var tested = 0
        for (batch in ordered.chunked(BATCH_SIZE).take(maxBatches)) {
            coroutineContext.ensureActive()
            val base = tested
            r.status("تست کانفیگ‌ها ${fa(base)}/${fa(ordered.size)}")
            r.detail("gstatic 204 · ${fa(batch.size)} موازی")
            hits += tester.test(batch, ENOUGH - hits.size) { done, ok ->
                r.status("تست کانفیگ‌ها ${fa(base + done)}/${fa(ordered.size)} · ${fa(hits.size + ok)} سالم")
            }
            tested += batch.size
            if (hits.size >= ENOUGH) break
        }
        AppLog.i("xray", "batch: ${hits.size} working of $tested")
        r.event(hits.isNotEmpty(), "تست: ${fa(hits.size)} سالم از ${fa(tested)}", hits.firstOrNull()?.let { "بهترین ${fa(it.delayMs)} ms" }.orEmpty())
        if (hits.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val fresh = hits.map { RankedEntry(it.candidate.uri, it.delayMs, now) }
            store.updateRanking(rankKey(net)) { Ranking.merge(it, fresh, now) }
        }
        return hits.size
    }

    /**
     * barry-far stage: load the list (GitHub raw → jsDelivr, cache + stale fallback), keep
     * vless/trojan TLS/Reality, take a random sample of [BarryList.SAMPLE] and batch-test it.
     * Returns null when the list could not be loaded.
     */
    private suspend fun barryStage(forceList: Boolean, r: ConnectReporter): Bootstrapped? {
        coroutineContext.ensureActive()
        val net = network ?: return null
        r.status("دریافت لیست دوم")
        r.detail("barry-far · GitHub → jsDelivr")
        val list = ListFetcher(workDir, source = ListFetcher.Source.BARRY).load(force = forceList) ?: run {
            r.event(false, "لیست دوم دریافت نشد", "barry-far")
            return null
        }
        val all = BarryList.parse(list.text)
        r.event(all.isNotEmpty(), "لیست دوم: ${fa(all.size)} کانفیگ", "barry-far · " + list.source)
        if (all.isEmpty()) return Bootstrapped(list.fromCache)
        val sample = BarryList.sample(all.filter { it.uri !in burned })
        r.step(3)
        batchTest(net, sample, BARRY_MAX_BATCHES, r)
        return Bootstrapped(list.fromCache)
    }

    private fun dpiLabel(d: DpiType) = when (d) {
        DpiType.SNI -> "SNI (نیاز به ECH)"
        DpiType.IP -> "IP (فرگمنت)"
        DpiType.UNKNOWN -> "نامشخص"
    }

    private fun shortName(link: ProxyLink): String {
        val flag = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}").find(link.name)?.value
        return (flag?.let { "$it " }.orEmpty() + link.network + "/" + link.security).take(40)
    }

    companion object {
        const val BATCH_SIZE = 24
        const val MAX_BATCHES = 2
        const val MAX_CANDIDATES = 48
        const val ENOUGH = 3
        /** barry-far sample tested in batches of [BATCH_SIZE]: 10 × 24 = 240 at most. */
        const val BARRY_MAX_BATCHES = 10
    }
}
