package com.example.xray

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.SocketFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult

/**
 * Finds out how the network filters TLS, using a few Cloudflare edge IPs.
 *
 * Step 1: a normal ClientHello naming an unfiltered host. Edges that answer with a ServerHello are
 * "reachable". Step 2: on those edges, a ClientHello naming a filtered host, written in tiny TCP
 * segments. If most still answer, splitting the hello beats the filter (IP-type DPI, fragment links
 * work); if they die, the filter reads the name (SNI-type DPI, ECH links are needed).
 *
 * Only the first server flight is read; no full handshake or HTTP is needed for the verdict.
 */
class DpiProbe(private val socketFactory: SocketFactory = SocketFactory.getDefault()) {
    /** [edges] are the IPs that answered the plain probe, fastest first (reused as clean IPs). */
    data class Counts(val reachable: Int, val fragmentedOk: Int, val edges: List<String>)

    suspend fun run(ips: List<String> = AutoList.CLOUDFLARE_EDGES): Counts = coroutineScope {
        val plain = ips.map { ip ->
            async(Dispatchers.IO) {
                val t0 = System.nanoTime()
                Triple(ip, serverAnswers(ip, OPEN_SNI, 0), System.nanoTime() - t0)
            }
        }.awaitAll()
        val edges = plain.filter { it.second }.sortedBy { it.third }.map { it.first }
        val split = edges.map { ip -> async(Dispatchers.IO) { serverAnswers(ip, FILTERED_SNI, SEGMENT) } }.awaitAll()
        Counts(edges.size, split.count { it }, edges)
    }

    /** Edge IPs that answer a plain hello, fastest first. */
    suspend fun reachableEdges(ips: List<String>): List<String> = coroutineScope {
        ips.map { ip ->
            async(Dispatchers.IO) {
                val t0 = System.nanoTime()
                if (serverAnswers(ip, OPEN_SNI, 0)) ip to (System.nanoTime() - t0) else null
            }
        }.awaitAll().filterNotNull().sortedBy { it.second }.map { it.first }
    }

    /** True when the server replies to our ClientHello with a TLS handshake record. Never throws. */
    fun serverAnswers(ip: String, sni: String, segment: Int, timeoutMs: Int = TIMEOUT_MS): Boolean = runCatching {
        socketFactory.createSocket().use { s ->
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(ip, 443), timeoutMs)
            s.soTimeout = timeoutMs
            val hello = clientHello(sni)
            writeHello(s.getOutputStream(), hello, segment)
            readsHandshake(s.getInputStream())
        }
    }.getOrDefault(false)

    private fun clientHello(sni: String): ByteArray {
        val engine = SSLContext.getDefault().createSSLEngine(sni, 443)
        engine.useClientMode = true
        engine.sslParameters = engine.sslParameters.apply { serverNames = listOf(SNIHostName(sni)) }
        engine.beginHandshake()
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        val result = engine.wrap(ByteBuffer.allocate(0), out)
        check(result.status == SSLEngineResult.Status.OK)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    private fun writeHello(out: OutputStream, hello: ByteArray, segment: Int) {
        if (segment <= 0) {
            out.write(hello)
            out.flush()
            return
        }
        var i = 0
        while (i < hello.size) {
            val end = minOf(i + segment, hello.size)
            out.write(hello, i, end - i)
            out.flush()
            // A short pause keeps the kernel from merging the segments.
            Thread.sleep(2)
            i = end
        }
    }

    private fun readsHandshake(input: InputStream): Boolean {
        val first = input.read()
        // 0x16 = handshake (ServerHello). An alert (0x15) or a reset means the hello was refused.
        return first == 0x16
    }

    companion object {
        const val OPEN_SNI = "cloudflare-ech.com"
        const val FILTERED_SNI = "chatgpt.com"
        const val SEGMENT = 8
        const val TIMEOUT_MS = 3_000
    }
}
