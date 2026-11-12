package com.example.xray

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import com.example.core.B64
import kotlin.random.Random

/**
 * Resolves the ECH config list a link needs.
 *
 * A link's `ech` value is either the base64 list itself or a lookup form
 * (`domain`, or `domain+https://resolver/dns-query`, `domain+udp://1.1.1.1`). For the lookup form
 * the HTTPS (type 65) DNS record of the domain is fetched over plain UDP first and DNS-over-HTTPS
 * by bare IP second, and the `ech` SvcParam is returned in base64. Results are cached.
 */
class EchKeys(private val cache: Cache = MemoryCache()) {
    interface Cache {
        fun get(domain: String): String?
        fun put(domain: String, key: String)
    }

    class MemoryCache : Cache {
        private val map = HashMap<String, Pair<String, Long>>()
        override fun get(domain: String): String? =
            map[domain]?.takeIf { System.currentTimeMillis() - it.second < TTL_MS }?.first
        override fun put(domain: String, key: String) { map[domain] = key to System.currentTimeMillis() }
    }

    /** Base64 ECHConfigList for [value], or null when it cannot be resolved now. */
    fun resolve(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        if (isConfigList(v)) return v
        val domain = v.substringBefore('+').substringAfter("://").substringBefore('/').trim().lowercase()
        if (domain.isEmpty() || !domain.contains('.')) return null
        cache.get(domain)?.let { return it }
        val explicit = v.substringAfter('+', "").takeIf { it.isNotBlank() }
        val key = lookup(domain, explicit) ?: return null
        cache.put(domain, key)
        return key
    }

    private fun lookup(domain: String, explicit: String?): String? {
        val query = buildQuery(domain)
        val tries = buildList<() -> ByteArray?> {
            explicit?.let { server ->
                when {
                    server.startsWith("udp://") -> add { udp(query, server.removePrefix("udp://").substringBefore(':')) }
                    server.startsWith("https://") -> add { doh(query, server) }
                }
            }
            UDP_SERVERS.forEach { ip -> add { udp(query, ip) } }
            DOH_SERVERS.forEach { url -> add { doh(query, url) } }
        }
        for (attempt in tries) {
            val answer = runCatching { attempt() }.getOrNull() ?: continue
            parseEch(answer)?.let { return B64.encode(it) }
        }
        return null
    }

    private fun udp(query: ByteArray, ip: String): ByteArray? = DatagramSocket().use { s ->
        s.soTimeout = 2_000
        s.send(DatagramPacket(query, query.size, InetAddress.getByName(ip), 53))
        val buf = ByteArray(4096)
        val p = DatagramPacket(buf, buf.size)
        s.receive(p)
        buf.copyOf(p.length)
    }

    private fun doh(query: ByteArray, url: String): ByteArray? {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 3_000
            c.readTimeout = 3_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/dns-message")
            c.setRequestProperty("Accept", "application/dns-message")
            c.outputStream.use { it.write(query) }
            if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val TYPE_HTTPS = 65
        const val TTL_MS = 6L * 60 * 60 * 1000
        val UDP_SERVERS = listOf("1.1.1.1", "8.8.8.8")
        val DOH_SERVERS = listOf("https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query")

        /** A base64 text whose decoded form starts with a plausible ECHConfigList length prefix. */
        fun isConfigList(v: String): Boolean {
            if (v.contains('.') || v.length < 40) return false
            val bytes = runCatching { B64.decode(v) }.getOrNull() ?: return false
            if (bytes.size < 4) return false
            val len = ((bytes[0].toInt() and 0xff) shl 8) or (bytes[1].toInt() and 0xff)
            return len == bytes.size - 2
        }

        fun buildQuery(domain: String): ByteArray {
            val out = ByteArrayOutputStream()
            val id = Random.nextInt(0, 0xffff)
            fun u16(v: Int) { out.write(v shr 8 and 0xff); out.write(v and 0xff) }
            u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
            domain.trimEnd('.').split('.').forEach { label ->
                val b = label.toByteArray(Charsets.US_ASCII)
                out.write(b.size); out.write(b)
            }
            out.write(0)
            u16(TYPE_HTTPS); u16(1)
            return out.toByteArray()
        }

        /** Extracts the `ech` SvcParam (key 5) from the first HTTPS answer that carries one. */
        fun parseEch(msg: ByteArray): ByteArray? {
            fun u16(i: Int) = ((msg[i].toInt() and 0xff) shl 8) or (msg[i + 1].toInt() and 0xff)
            fun skipName(start: Int): Int {
                var i = start
                while (true) {
                    val len = msg[i].toInt() and 0xff
                    if (len == 0) return i + 1
                    if (len and 0xc0 == 0xc0) return i + 2
                    i += len + 1
                }
            }
            if (msg.size < 12) return null
            val qd = u16(4)
            val an = u16(6)
            var i = 12
            repeat(qd) { i = skipName(i) + 4 }
            repeat(an) {
                i = skipName(i)
                val type = u16(i)
                val rdLen = u16(i + 8)
                val rd = i + 10
                if (type == TYPE_HTTPS) {
                    var p = skipName(rd + 2) // priority, target name
                    val end = rd + rdLen
                    while (p + 4 <= end) {
                        val key = u16(p)
                        val len = u16(p + 2)
                        if (key == 5) return msg.copyOfRange(p + 4, p + 4 + len)
                        p += 4 + len
                    }
                }
                i = rd + rdLen
            }
            return null
        }
    }
}
