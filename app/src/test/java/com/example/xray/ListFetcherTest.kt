package com.example.xray

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket

/** List fetch: fresh cache short-circuits, all mirrors down falls back to the stale copy. */
class ListFetcherTest {
    private lateinit var dir: File
    private val sample: String = javaClass.classLoader!!.getResource("patterniha-sample.txt")!!.readText()
    private var dead: ServerSocket? = null

    @Before fun setUp() { dir = kotlin.io.path.createTempDirectory("lf").toFile() }

    /** Points every HTTPS request at a closed local port, so all mirrors fail. */
    private fun offline() {
        val s = ServerSocket(0); val port = s.localPort; s.close()
        System.setProperty("https.proxyHost", "127.0.0.1"); System.setProperty("https.proxyPort", "$port")
        System.setProperty("http.proxyHost", "127.0.0.1"); System.setProperty("http.proxyPort", "$port")
    }

    @After fun tearDown() {
        listOf("https.proxyHost", "https.proxyPort", "http.proxyHost", "http.proxyPort").forEach(System::clearProperty)
        dir.deleteRecursively()
    }

    @Test fun freshCache_isUsedWithoutNetwork() {
        File(dir, "patterniha.txt").writeText(sample)
        File(dir, "patterniha.meta").writeText("\"etag\"\n${System.currentTimeMillis()}\n")
        offline()
        val r = ListFetcher(dir).load()!!
        assertEquals("cache", r.source); assertTrue(r.fromCache); assertEquals(sample, r.text)
    }

    @Test fun staleCache_isUsedWhenEveryMirrorFails() {
        File(dir, "patterniha.txt").writeText(sample)
        File(dir, "patterniha.meta").writeText("\"etag\"\n1\n")
        offline()
        val r = ListFetcher(dir).load()!!
        assertEquals("cache (stale)", r.source); assertTrue(r.fromCache); assertEquals(sample, r.text)
    }

    @Test fun noCache_andOffline_returnsNull() {
        offline()
        assertNull(ListFetcher(dir).load())
    }

    @Test fun mirrorsArePatternihaOnly() {
        assertTrue(ListFetcher.MIRRORS.all { it.contains("patterniha/Free-Configs") })
        assertEquals(3, ListFetcher.MIRRORS.size)
    }
}
