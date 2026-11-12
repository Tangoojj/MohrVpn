package com.example.xray

import com.example.core.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** barry-far list (parse, filter, dedupe, sample, fetch fallback) and the fragment switch in generated configs. */
class BarryAndFragmentTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()
    private val barry = res("barry-sample.txt")
    private val patterniha = res("patterniha-sample.txt")

    // ---- barry-far ----

    @Test fun barry_keepsOnlyVlessTrojanWithTlsOrReality() {
        val all = BarryList.parse(barry)
        assertTrue("expected links, got ${all.size}", all.size >= 30)
        assertTrue(all.all { it.link.protocol == "vless" || it.link.protocol == "trojan" })
        assertTrue(all.all { it.link.security == "tls" || it.link.security == "reality" })
        assertTrue(all.any { it.link.security == "reality" })
        assertTrue(all.any { it.link.protocol == "trojan" })
        assertTrue(all.none { it.link.isLoopback })
        // Every kept link builds an Xray outbound.
        all.forEach { XrayConfig.outbound(it.link, "x") }
    }

    @Test fun barry_rejectsMalformedWithoutCrashing() {
        val bad = listOf(
            "vless://", "vless://@1.2.3.4:443?security=tls", "trojan://pw@:443?security=tls",
            "vless://id@host:99999?security=tls", "vless://id@[::1:443?security=tls",
            "vless://id@1.2.3.4:443?security=reality&sni=a.com#no-pbk", "trojan://pw@1.2.3.4:443?security=xtls",
            "garbage", "vless://id@1.2.3.4:443?security=tls&type=kcp", "ss://abc@1.2.3.4:443",
            "vmess://eyJhZGQiOiIxLjIuMy40In0=", "vless://id@1.2.3.4:443?security=none", "vless://id@1.2.3.4:443",
            "\u0000\uFFFF vless://%%%@@@:::?&&&==#%", "vless://id@1.2.3.4:443?security=tls&type=xhttp&extra=%7Bbroken",
        ).joinToString("\n")
        assertTrue(BarryList.parse(bad).isEmpty())
        assertTrue(BarryList.parse("").isEmpty())
        // A good line among garbage survives.
        val good = "vless://11111111-1111-1111-1111-111111111111@1.2.3.4:443?security=tls&sni=a.example&type=ws#ok"
        assertEquals(1, BarryList.parse(bad + "\n" + good).size)
    }

    @Test fun barry_dedupesByHostPortAndId() {
        val a = "vless://id-1@Host.example:443?security=tls&sni=a.example#one"
        val b = "vless://id-1@host.example:443?security=tls&sni=b.example&type=ws#two"
        val c = "vless://id-2@host.example:443?security=tls&sni=a.example#other-id"
        val d = "vless://id-1@host.example:8443?security=tls&sni=a.example#other-port"
        val all = BarryList.parse(listOf(a, b, c, d, a).joinToString("\n"))
        assertEquals(listOf("one", "other-id", "other-port"), all.map { it.link.name })
        // The sample file has an exact duplicate and a same-server copy: both dropped.
        val parsed = BarryList.parse(barry)
        assertEquals(parsed.size, parsed.map { BarryList.dedupeKey(it.link) }.distinct().size)
    }

    @Test fun barry_sampleIsCappedDistinctAndRotates() {
        val big = (1..1000).joinToString("\n") { "trojan://pw$it@10.0.${it / 250}.${it % 250 + 1}:443?security=tls&sni=s$it.example#n$it" }
        val all = BarryList.parse(big)
        assertEquals(1000, all.size)
        val s1 = BarryList.sample(all, random = Random(1))
        val s2 = BarryList.sample(all, random = Random(2))
        assertEquals(BarryList.SAMPLE, s1.size)
        assertTrue(BarryList.SAMPLE in 150..300)
        assertEquals(s1.size, s1.map { it.uri }.distinct().size)
        assertNotEquals(s1.map { it.uri }, s2.map { it.uri })
        assertEquals(s1, BarryList.sample(all, random = Random(1)))
        assertEquals(5, BarryList.sample(all.take(5)).size)
        // Batches the engine runs: 24 per Xray process, 10 batches max = the sample cap.
        assertEquals(BarryList.SAMPLE, XrayEngine.BATCH_SIZE * XrayEngine.BARRY_MAX_BATCHES)
    }

    @Test fun barry_parsesFullSizeListQuickly() {
        // ~9000 lines like the real list.
        val lines = barry.lines().filter { it.isNotBlank() }
        val text = (0 until 9000).joinToString("\n") { lines[it % lines.size].replace("@", "@x$it.") }
        val t0 = System.nanoTime()
        val all = BarryList.parse(text)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("parse took $ms ms", ms < 5_000)
        assertTrue(all.size > 1000)
    }

    @Test fun barryFetcher_mirrorsAndStaleFallback() {
        assertEquals(listOf(ListFetcher.BARRY_RAW_URL, ListFetcher.BARRY_JSDELIVR_URL), ListFetcher.Source.BARRY.mirrors)
        assertEquals("https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt", ListFetcher.BARRY_RAW_URL)
        assertEquals("https://cdn.jsdelivr.net/gh/barry-far/V2ray-Config@main/All_Configs_Sub.txt", ListFetcher.BARRY_JSDELIVR_URL)
        // Patterniha keeps its own three mirrors, first in order.
        assertEquals(ListFetcher.MIRRORS, ListFetcher.Source.PATTERNIHA.mirrors)
        val dir = kotlin.io.path.createTempDirectory("bf").toFile()
        try {
            File(dir, "barry.txt").writeText(barry)
            File(dir, "barry.meta").writeText("\"e\"\n${System.currentTimeMillis()}\n")
            offline()
            assertEquals("cache", ListFetcher(dir, source = ListFetcher.Source.BARRY).load()!!.source)
            File(dir, "barry.meta").writeText("\"e\"\n1\n")
            val stale = ListFetcher(dir, source = ListFetcher.Source.BARRY).load()!!
            assertEquals("cache (stale)", stale.source); assertEquals(barry, stale.text)
            // Separate cache files: no Patterniha copy exists, so Patterniha has nothing offline.
            assertNull(ListFetcher(dir).load())
        } finally { dir.deleteRecursively() }
    }

    private fun offline() {
        val s = ServerSocket(0); val port = s.localPort; s.close()
        listOf("https", "http").forEach { System.setProperty("$it.proxyHost", "127.0.0.1"); System.setProperty("$it.proxyPort", "$port") }
    }

    @After fun clearProxy() = listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach(System::clearProperty)

    // ---- fragment ----

    /** Same generation as the v1.2 golden file (made with the v1.2 XrayConfig, before the fragment switch). */
    private fun generate(fragment: Boolean?): String {
        val out = StringBuilder()
        for (text in listOf(patterniha, barry)) {
            val links = text.lines().mapNotNull { runCatching { ProxyLink.parse(it) }.getOrNull() }
                .filter { runCatching { XrayConfig.outbound(it, "x") }.isSuccess }
            links.forEachIndexed { i, l ->
                out.append(if (fragment == null) XrayConfig.runConfig(l, 10808 + i) else XrayConfig.runConfig(l, 10808 + i, null, fragment)).append('\n')
            }
            val entries = links.mapIndexed { i, l -> XrayConfig.BatchEntry("c$i", 20000 + i, l) }
            out.append(if (fragment == null) XrayConfig.testConfig(entries) else XrayConfig.testConfig(entries, fragment)).append('\n')
        }
        return out.toString()
    }

    @Test fun fragmentOff_isByteIdenticalToV12() {
        val golden = res("xray-configs-v1.2-golden.txt")
        assertEquals(golden, generate(false))
        assertEquals(golden, generate(null))
        assertFalse(golden.contains("dialerProxy"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun outbounds(json: String) = ((Json.parse(json) as Map<String, Any?>)["outbounds"] as List<Map<String, Any?>>)

    @Suppress("UNCHECKED_CAST")
    private fun dialer(o: Map<String, Any?>) = ((o["streamSettings"] as Map<String, Any?>)["sockopt"] as Map<String, Any?>)["dialerProxy"]

    @Test fun fragmentOn_runConfigRoutesProxyThroughFreedomFragment() {
        val (links, own) = BarryList.parse(barry).map { it.link }.partition { XrayConfig.fragmentApplies(it) }
        assertTrue(links.size >= 30)
        // Links that ship their own finalmask fragment keep it and are not fragmented twice.
        for (l in own) assertEquals(XrayConfig.runConfig(l, 10808), XrayConfig.runConfig(l, 10808, null, true))
        for (l in links) {
            val outs = outbounds(XrayConfig.runConfig(l, 10808, null, true))
            assertEquals(listOf("proxy", "direct", "block", "fragment"), outs.map { it["tag"] })
            assertEquals("fragment", dialer(outs[0]))
            val f = outs[3]
            assertEquals("freedom", f["protocol"])
            assertEquals(
                mapOf("packets" to "tlshello", "length" to "100-200", "interval" to "10-20"),
                (f["settings"] as Map<*, *>)["fragment"],
            )
            assertNull(outs[1]["streamSettings"])
        }
        assertTrue(links.any { it.security == "reality" })
    }

    @Test fun fragmentOn_skipsNonTlsOwnFinalmaskAndH3() {
        val none = ProxyLink.parse("vless://id@1.2.3.4:443?security=none&type=ws#n")!!
        val fm = AutoList.parse(patterniha).first { it.link.param("fm").isNotBlank() }.link
        val h3 = ProxyLink.parse("vless://id@1.2.3.4:443?security=tls&type=xhttp&alpn=h3&sni=a.example#h3")!!
        for (l in listOf(none, fm, h3)) {
            assertFalse(XrayConfig.fragmentApplies(l))
            assertEquals(XrayConfig.runConfig(l, 10808), XrayConfig.runConfig(l, 10808, null, true))
        }
        for (t in listOf("tcp", "ws", "grpc", "xhttp", "httpupgrade")) {
            assertTrue(t, XrayConfig.fragmentApplies(ProxyLink.parse("trojan://pw@1.2.3.4:443?security=tls&type=$t&sni=a.example")!!))
        }
    }

    @Test fun fragmentOn_testConfigHasOneSharedFragmentOutbound() {
        val links = BarryList.parse(barry).map { it.link }
        val entries = links.mapIndexed { i, l -> XrayConfig.BatchEntry("c$i", 20000 + i, l) }
        val outs = outbounds(XrayConfig.testConfig(entries, true))
        assertEquals(entries.size + 1, outs.size)
        assertEquals(1, outs.count { it["tag"] == "fragment" })
        outs.filter { it["tag"] != "fragment" }.zip(links).forEach { (o, l) ->
            assertEquals(if (XrayConfig.fragmentApplies(l)) "fragment" else null, dialer(o))
        }
        assertTrue(links.count { XrayConfig.fragmentApplies(it) } >= 30)
        // A batch where nothing qualifies gets no fragment outbound.
        val plain = listOf(XrayConfig.BatchEntry("c0", 20000, ProxyLink.parse("vless://id@1.2.3.4:443?security=none")!!))
        assertEquals(XrayConfig.testConfig(plain), XrayConfig.testConfig(plain, true))
    }

    @Test fun rankKey_separatesFragmentOnAndOff() {
        assertEquals("cell:43235", XraySettings.rankKey("cell:43235", false))
        assertEquals("cell:43235|frag", XraySettings.rankKey("cell:43235", true))
    }

    /** Real Xray (XRAY_BIN or the sandbox build) accepts fragment ON configs, Reality included. */
    @Test fun fragmentOnConfigs_passXrayTest_whenBinaryAvailable() {
        val xray = File(System.getenv("XRAY_BIN") ?: "/workspace/work/v2/xray/linux-arm64-v8a/xray")
        assumeTrue(xray.canExecute())
        val dir = kotlin.io.path.createTempDirectory("fx").toFile()
        val links = BarryList.parse(barry).map { it.link }
        val run = File(dir, "run.json").apply { writeText(XrayConfig.runConfig(links.first { it.security == "reality" }, 10808, null, true)) }
        assertEquals(0, xrayTest(xray, run))
        val batch = File(dir, "batch.json").apply {
            writeText(XrayConfig.testConfig(links.mapIndexed { i, l -> XrayConfig.BatchEntry("c$i", 21000 + i, l) }, true))
        }
        assertEquals(0, xrayTest(xray, batch))
        dir.deleteRecursively()
    }

    private fun xrayTest(xray: File, cfg: File): Int {
        val p = ProcessBuilder(xray.path, "run", "-test", "-c", cfg.path).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); p.waitFor(20, TimeUnit.SECONDS)
        if (p.exitValue() != 0) println(out)
        return p.exitValue()
    }
}
