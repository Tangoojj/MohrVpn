package com.example.xray

import kotlin.random.Random

/**
 * The barry-far V2ray-Config "All_Configs_Sub" list (plain text, one share link per line, mixed
 * protocols). Only vless:// and trojan:// links with TLS or Reality are kept, deduplicated by
 * server (host:port + id/password), and a rotating random sample is tested per connect so a
 * ~9000-line list stays light on memory and time. Pure logic, no I/O.
 */
object BarryList {
    /** Links tested per connect at most (in batches, stopping once enough work). */
    const val SAMPLE = 240

    fun parse(text: String): List<Candidate> {
        val seen = HashSet<String>()
        val out = ArrayList<Candidate>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val scheme = line.substringBefore("://", "").lowercase()
            if (scheme != "vless" && scheme != "trojan") continue
            val link = runCatching { ProxyLink.parse(line) }.getOrNull() ?: continue
            if (link.security != "tls" && link.security != "reality") continue
            if (link.isLoopback || !wellFormedHost(link)) continue
            // Reality needs a public key; links Xray cannot build are dropped here, not at test time.
            if (runCatching { XrayConfig.outbound(link, "probe") }.isFailure) continue
            if (!seen.add(dedupeKey(link))) continue
            out += Candidate(link, AutoList.classify(link))
        }
        return out
    }

    private val HOSTNAME = Regex("^[A-Za-z0-9._-]+$")
    private val IPV6 = Regex("^[0-9A-Fa-f:.]+$")

    /** Host is a plain name/IPv4, or a bracketed IPv6 literal (rejects e.g. an unclosed "[::1:443"). */
    fun wellFormedHost(link: ProxyLink): Boolean = when {
        link.address.contains(':') -> IPV6.matches(link.address) && link.raw.contains("[${link.address}]")
        else -> HOSTNAME.matches(link.address)
    }

    fun dedupeKey(link: ProxyLink): String =
        "${link.address.lowercase()}:${link.port}|${link.credential}"

    /** A random sample of at most [size], different on each call unless [random] is fixed. */
    fun sample(all: List<Candidate>, size: Int = SAMPLE, random: Random = Random.Default): List<Candidate> =
        if (all.size <= size) all.shuffled(random) else all.shuffled(random).take(size)
}
