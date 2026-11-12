package com.example.core

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Reachability checks made through a local SOCKS5 port (own minimal SOCKS5 client). */
object SocksProbe {
    /** Opens a TCP stream to host:port through the SOCKS5 server on 127.0.0.1:[socksPort]. */
    fun connect(socksPort: Int, host: String, port: Int, timeoutMs: Int): Socket {
        val s = Socket()
        try {
            s.tcpNoDelay = true
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            val out = s.getOutputStream()
            val input = s.getInputStream()
            out.write(byteArrayOf(5, 1, 0))
            out.flush()
            val greet = readN(input, 2)
            require(greet[0].toInt() == 5 && greet[1].toInt() == 0) { "SOCKS auth refused" }
            val name = host.toByteArray(Charsets.US_ASCII)
            val req = ByteArray(7 + name.size)
            req[0] = 5; req[1] = 1; req[2] = 0; req[3] = 3; req[4] = name.size.toByte()
            System.arraycopy(name, 0, req, 5, name.size)
            req[5 + name.size] = (port shr 8).toByte()
            req[6 + name.size] = port.toByte()
            out.write(req)
            out.flush()
            val head = readN(input, 4)
            require(head[1].toInt() == 0) { "SOCKS connect failed (${head[1]})" }
            when (head[3].toInt()) {
                1 -> readN(input, 4 + 2)
                4 -> readN(input, 16 + 2)
                3 -> readN(input, (input.read() and 0xff) + 2)
            }
            return s
        } catch (t: Throwable) {
            runCatching { s.close() }
            throw t
        }
    }

    /** Time to a 204 from gstatic through the proxy, in ms, or -1. */
    fun generate204(socksPort: Int, timeoutMs: Int = 7_000): Long = runCatching {
        val t0 = System.nanoTime()
        connect(socksPort, "www.gstatic.com", 80, timeoutMs).use { s ->
            s.getOutputStream().apply {
                write("GET /generate_204 HTTP/1.1\r\nHost: www.gstatic.com\r\nUser-Agent: MohrVpn\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val status = readLine(s.getInputStream())
            if (status.contains(" 204")) (System.nanoTime() - t0) / 1_000_000 else -1L
        }
    }.getOrDefault(-1L)

    /** True when an HTTPS request to [host] gets any HTTP answer through the proxy. */
    fun httpsAnswers(socksPort: Int, host: String = "www.google.com", path: String = "/generate_204", timeoutMs: Int = 4_000): Boolean =
        runCatching { httpsGet(socksPort, host, path, timeoutMs).startsWith("HTTP/") }.getOrDefault(false)

    /** Small HTTPS GET; returns the raw response text (headers + body, capped). */
    fun httpsGet(socksPort: Int, host: String, path: String, timeoutMs: Int = 6_000, cap: Int = 16_384): String {
        val raw = connect(socksPort, host, 443, timeoutMs)
        val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, 443, true) as SSLSocket
        tls.use { s ->
            s.soTimeout = timeoutMs
            s.startHandshake()
            s.outputStream.apply {
                write("GET $path HTTP/1.1\r\nHost: $host\r\nUser-Agent: MohrVpn\r\nAccept: */*\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (buf.size() < cap) {
                val n = runCatching { s.inputStream.read(chunk) }.getOrDefault(-1)
                if (n <= 0) break
                buf.write(chunk, 0, n)
            }
            return buf.toString("UTF-8")
        }
    }

    /** Exit country (ISO code) and IP via Cloudflare's trace page, through the proxy. */
    fun exitInfo(socksPort: Int): Pair<String, String>? = runCatching {
        val text = httpsGet(socksPort, "www.cloudflare.com", "/cdn-cgi/trace")
        val loc = Regex("(?m)^loc=([A-Z]{2})").find(text)?.groupValues?.get(1).orEmpty()
        val ip = Regex("(?m)^ip=([0-9a-fA-F:.]+)").find(text)?.groupValues?.get(1).orEmpty()
        if (loc.isEmpty()) null else loc to ip
    }.getOrNull()

    private fun readN(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(b, off, n - off)
            require(r > 0) { "SOCKS stream closed" }
            off += r
        }
        return b
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 512) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }
}
