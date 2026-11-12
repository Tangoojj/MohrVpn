package com.example.xray

import com.example.core.AppLog
import com.example.core.NativeProcess
import com.example.core.SocksProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Measures a batch of candidates with one Xray process: one SOCKS port per candidate, each port
 * probed with gstatic's generate_204 in parallel. A candidate whose HTTPS check then fails keeps
 * its result but with a penalty, so links that only pass plain HTTP rank below full ones.
 */
class BatchTester(
    private val xray: File,
    private val workDir: File,
    private val ech: EchKeys,
) {
    /** Fragment switch for the configs this tester writes (set per connect). */
    @Volatile var fragment: Boolean = false

    data class Hit(val candidate: Candidate, val delayMs: Long)

    /** Links Xray rejects at config load (e.g. an unknown finalmask type) are dropped first. */
    fun validated(entries: List<XrayConfig.BatchEntry>): List<XrayConfig.BatchEntry> {
        var list = entries
        repeat(entries.size) {
            if (list.isEmpty()) return list
            val file = File(workDir, "xray-check.json")
            file.writeText(XrayConfig.testConfig(list, fragment))
            val (code, out) = NativeProcess(xray, listOf("run", "-test", "-c", file.absolutePath), workDir, "xray").runToEnd(10_000)
            if (code == 0) return list
            val bad = Regex("tag out-([A-Za-z0-9]+)").find(out)?.groupValues?.get(1)
            if (bad == null) {
                AppLog.i("xray", "config check failed: ${out.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(200)}")
                return emptyList()
            }
            AppLog.i("xray", "skipping $bad: " + out.substringAfter("tag out-$bad", "").take(120).replace('\n', ' '))
            list = list.filter { it.tag != bad }
        }
        return list
    }

    /** Builds batch entries (fresh ports, resolved ECH keys) for [candidates]. */
    fun entries(candidates: List<Candidate>): List<XrayConfig.BatchEntry> {
        val ports = NativeProcess.freePorts(candidates.size)
        return candidates.mapIndexedNotNull { i, c ->
            val key = if (c.variant == Variant.ECH) ech.resolve(c.link.ech) else null
            runCatching { XrayConfig.outbound(c.link, "probe", key) }.getOrNull() ?: return@mapIndexedNotNull null
            XrayConfig.BatchEntry("c$i", ports[i], c.link, key)
        }
    }

    suspend fun test(
        candidates: List<Candidate>,
        enough: Int,
        onProgress: (done: Int, ok: Int) -> Unit,
    ): List<Hit> = withContext(Dispatchers.IO) {
        val entries = validated(entries(candidates))
        if (entries.isEmpty()) return@withContext emptyList()
        val byTag = entries.associateBy { it.tag }
        val candidateByUri = candidates.associateBy { it.uri }
        val file = File(workDir, "xray-batch.json").apply { writeText(XrayConfig.testConfig(entries, fragment)) }
        val proc = NativeProcess(xray, listOf("run", "-c", file.absolutePath), workDir, "xray")
        proc.start()
        try {
            if (!proc.awaitPorts(entries.map { it.port }, 6_000)) {
                AppLog.i("xray", "batch core did not start")
                return@withContext emptyList()
            }
            val done = AtomicInteger()
            val ok = AtomicInteger()
            val hits = java.util.concurrent.ConcurrentLinkedQueue<Hit>()
            coroutineScope {
                val probes = entries.map { e ->
                    async(Dispatchers.IO) {
                        val d = SocksProbe.generate204(e.port, PROBE_TIMEOUT_MS)
                        if (d >= 0) {
                            val full = SocksProbe.httpsAnswers(e.port, timeoutMs = PAGE_TIMEOUT_MS)
                            val c = candidateByUri[byTag.getValue(e.tag).link.raw]
                            if (c != null) hits += Hit(c, if (full) d else d + PAGE_PENALTY_MS)
                            ok.incrementAndGet()
                        }
                        onProgress(done.incrementAndGet(), ok.get())
                    }
                }
                // Enough working links found: give the rest a short grace, then end the batch
                // (stopping the core makes the remaining probes fail at once).
                launch {
                    while (done.get() < entries.size) {
                        if (ok.get() >= enough) {
                            delay(GRACE_MS)
                            proc.stop()
                            break
                        }
                        delay(100)
                    }
                }
                probes.awaitAll()
            }
            hits.sortedBy { it.delayMs }
        } finally {
            proc.stop()
        }
    }

    companion object {
        const val PROBE_TIMEOUT_MS = 7_000
        const val PAGE_TIMEOUT_MS = 2_500
        const val PAGE_PENALTY_MS = 1_500L
        const val GRACE_MS = 900L
    }
}
