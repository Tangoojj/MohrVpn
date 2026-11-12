package com.example.xray

import com.example.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Pure connect logic: link parsing, ordering, ranking, config building (no Android needed). */
class ConnectLogicTest {
    private val sample: String = javaClass.classLoader!!.getResource("patterniha-sample.txt")!!.readText()

    @Test fun parse_sampleList_skipsCommentsAndClassifiesVariants() {
        val all = AutoList.parse(sample)
        assertTrue("expected links from sample, got ${all.size}", all.size >= 30)
        val byVariant = all.groupBy { it.variant }
        assertTrue(byVariant[Variant.FRAGMENT].orEmpty().isNotEmpty())
        assertTrue(byVariant[Variant.IPV6].orEmpty().isNotEmpty())
        assertTrue(all.any { it.link.isLoopback })
        assertTrue(all.none { it.uri.startsWith("#") })
    }

    @Test fun proxyLink_parsesIpv6AndWithAddress() {
        val l = ProxyLink.parse("vless://id-1@[2a06:98c1::7]:8443?security=tls&SNI=a.example&type=ws#n%20x")!!
        assertEquals("2a06:98c1::7", l.address); assertEquals(8443, l.port)
        assertEquals("a.example", l.sni); assertEquals("ws", l.network); assertEquals("n x", l.name)
        assertTrue(l.isIpv6)
        val moved = ProxyLink.parse(ProxyLink.withAddress(l.raw, "1.2.3.4"))!!
        assertEquals("1.2.3.4", moved.address); assertEquals(8443, moved.port); assertEquals("a.example", moved.sni)
        assertNull(ProxyLink.parse("vmess://abc"))
        assertNull(ProxyLink.parse("vless://@host:443"))
    }

    @Test fun order_dropsLoopbackAndIpv6WhenNoIpv6_andLeadsWithCleanIpCopiesOnIpDpi() {
        val all = AutoList.parse(sample)
        val clean = listOf("104.16.132.229", "172.67.70.1")
        val ordered = AutoList.order(all, DpiType.IP, hasIpv6 = false, cleanIps = clean, limit = 50)
        assertTrue(ordered.isNotEmpty())
        assertTrue(ordered.none { it.link.isLoopback })
        assertTrue(ordered.none { it.variant == Variant.IPV6 })
        assertEquals(ordered.size, ordered.map { it.uri }.distinct().size)
        assertTrue("IP DPI should start with a clean-IP fragment copy", ordered.first().cleanIpCopy && ordered.first().variant == Variant.FRAGMENT)
        assertTrue(ordered.size <= 50)
        val v6 = AutoList.order(all, DpiType.SNI, hasIpv6 = true, cleanIps = clean, limit = 500)
        assertTrue(v6.any { it.variant == Variant.IPV6 })
    }

    @Test fun diversify_roundRobinsServers() {
        fun c(sni: String, i: Int) = Candidate(ProxyLink.parse("vless://u$i@1.1.1.$i:443?sni=$sni")!!, Variant.FRAGMENT)
        val out = AutoList.diversify(listOf(c("a", 1), c("a", 2), c("a", 3), c("b", 4), c("b", 5)))
        assertEquals(listOf("a", "b", "a", "b", "a"), out.map { it.link.sni })
        assertEquals(listOf(1, 9, 2, 3), AutoList.interleave(listOf(1, 2, 3), listOf(9)))
    }

    @Test fun ranking_mergeHealthyFailAndRoundTrip() {
        val now = 10L * Ranking.MAX_AGE_MS
        val old = listOf(
            RankedEntry("a", 300, now - 1000), RankedEntry("b", 100, now - 1000, failures = 2),
            RankedEntry("c", 50, now - Ranking.MAX_AGE_MS - 1), RankedEntry("d", 200, now - 5),
        )
        assertEquals(listOf("d", "a"), Ranking.healthy(old, now).map { it.uri })
        val merged = Ranking.merge(old, listOf(RankedEntry("e", 400, now), RankedEntry("a", 90, now)), now)
        assertEquals(listOf("a", "e", "d"), merged.map { it.uri })
        val failed = Ranking.fail(Ranking.fail(merged, "a"), "a")
        assertEquals(listOf("d", "e"), Ranking.healthy(failed, now).map { it.uri })
        assertEquals(0, Ranking.succeed(failed, "a").first { it.uri == "a" }.failures)
        assertEquals(failed, Ranking.decode(Ranking.encode(failed)))
        assertTrue(Ranking.decode("garbage\n1\t2").isEmpty())
        assertTrue(Ranking.merge(emptyList(), (1..20).map { RankedEntry("x$it", it.toLong(), now) }, now).size == Ranking.KEEP)
    }

    @Test fun dpiDecision() {
        assertEquals(DpiType.IP, DpiDecision.decide(reachable = 3, fragmentedOk = 1, operatorHint = DpiType.SNI))
        assertEquals(DpiType.SNI, DpiDecision.decide(reachable = 4, fragmentedOk = 0, operatorHint = DpiType.IP))
        assertEquals(DpiType.IP, DpiDecision.decide(reachable = 1, fragmentedOk = 1, operatorHint = DpiType.IP))
    }

    private fun batch(): List<XrayConfig.BatchEntry> {
        val ordered = AutoList.order(AutoList.parse(sample), DpiType.UNKNOWN, false, AutoList.CLOUDFLARE_EDGES.take(2), 13)
        return ordered.mapIndexedNotNull { i, c ->
            runCatching { XrayConfig.outbound(c.link, "probe") }.getOrNull() ?: return@mapIndexedNotNull null
            XrayConfig.BatchEntry("c$i", 20000 + i, c.link)
        }
    }

    @Test fun testConfig_oneInboundOutboundAndRulePerCandidate() {
        val entries = batch()
        assertTrue(entries.size >= 5)
        @Suppress("UNCHECKED_CAST")
        val root = Json.parse(XrayConfig.testConfig(entries)) as Map<String, Any?>
        val ins = root["inbounds"] as List<Map<String, Any?>>
        val outs = root["outbounds"] as List<Map<String, Any?>>
        val rules = (root["routing"] as Map<String, Any?>)["rules"] as List<Map<String, Any?>>
        assertEquals(entries.size, ins.size); assertEquals(entries.size, outs.size); assertEquals(entries.size, rules.size)
        assertEquals(entries.map { it.port.toDouble() }, ins.map { (it["port"] as Number).toDouble() })
        assertTrue(ins.all { it["listen"] == "127.0.0.1" })
        assertEquals(entries.map { "out-${it.tag}" }, outs.map { it["tag"] })
    }

    @Test fun runConfig_blocksQuicAndRoutesPrivateDirect() {
        val link = batch().first().link
        val text = XrayConfig.runConfig(link, 10808)
        @Suppress("UNCHECKED_CAST")
        val root = Json.parse(text) as Map<String, Any?>
        val outs = (root["outbounds"] as List<Map<String, Any?>>).map { it["tag"] }
        assertEquals(listOf("proxy", "direct", "block"), outs)
        assertTrue(text.contains("\"udp\"") && text.contains("\"443\""))
        assertTrue(text.contains("10.0.0.0/8"))
    }

    /** Runs the real Xray binary on the generated configs when it is present in the sandbox. */
    @Test fun generatedConfigs_passXrayTest_whenBinaryAvailable() {
        val xray = File(System.getenv("XRAY_BIN") ?: "/workspace/work/v2/xray/linux-arm64-v8a/xray")
        assumeTrue(xray.canExecute())
        val dir = createTempDirectory()
        val run = File(dir, "run.json").apply { writeText(XrayConfig.runConfig(batch().first().link, 10808)) }
        assertEquals(0, xrayTest(xray, run))
        // Batch: drop tags Xray rejects (same loop as BatchTester.validated), the rest must load.
        var list = batch()
        repeat(list.size) {
            val f = File(dir, "batch.json").apply { writeText(XrayConfig.testConfig(list)) }
            val p = ProcessBuilder(xray.path, "run", "-test", "-c", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText(); p.waitFor(20, TimeUnit.SECONDS)
            if (p.exitValue() == 0) { assertTrue(list.size >= 5); return }
            val bad = Regex("tag out-([A-Za-z0-9]+)").find(out)?.groupValues?.get(1)
            assertNotNull("xray rejected config without naming a tag: $out", bad)
            list = list.filter { it.tag != bad }
        }
        throw AssertionError("no batch config accepted")
    }

    private fun xrayTest(xray: File, cfg: File): Int {
        val p = ProcessBuilder(xray.path, "run", "-test", "-c", cfg.path).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().readText(); p.waitFor(20, TimeUnit.SECONDS); return p.exitValue()
    }

    private fun createTempDirectory(): File = kotlin.io.path.createTempDirectory("mohr").toFile()
}
