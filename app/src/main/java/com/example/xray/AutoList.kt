package com.example.xray

/** How the current network filters TLS. */
enum class DpiType {
    /** Blocks by server name: ECH links (hidden SNI) are the best bet. */
    SNI,
    /** Blocks by address; a split ClientHello gets through: fragment (fm) links on clean IPs first. */
    IP,
    UNKNOWN,
}

/** The shapes a Patterniha node comes in. */
enum class Variant { ECH, FRAGMENT, IPV6, OTHER }

data class Candidate(
    val link: ProxyLink,
    val variant: Variant,
    /** True for a copy of a Cloudflare-fronted link redirected to another clean edge IP. */
    val cleanIpCopy: Boolean = false,
) {
    val uri: String get() = link.raw
}

/**
 * Turns the raw Patterniha list into an ordered list of candidates for this network.
 * Pure logic, no I/O.
 */
object AutoList {
    /** Cloudflare edge IPs tried as alternates for fragment links (also used by the DPI probe). */
    val CLOUDFLARE_EDGES = listOf(
        "188.114.98.0", "188.114.99.0", "104.16.132.229", "104.18.8.83", "104.18.9.83",
        "104.26.13.100", "104.26.14.40", "172.67.70.1", "162.159.36.1",
    )

    fun parse(text: String): List<Candidate> = text.lineSequence()
        .map(String::trim)
        .filter { !it.startsWith("#") && it.contains("://") }
        .distinct()
        .mapNotNull { ProxyLink.parse(it) }
        .map { Candidate(it, classify(it)) }
        .toList()

    fun classify(link: ProxyLink): Variant = when {
        link.isIpv6 -> Variant.IPV6
        link.ech.isNotBlank() -> Variant.ECH
        link.param("fm").isNotBlank() || link.param("cs").isNotBlank() -> Variant.FRAGMENT
        else -> Variant.OTHER
    }

    /**
     * Builds the test order.
     *  - Loopback links (meant for a local SNI spoofer) are dropped, except ECH ones, which are
     *    re-pointed at clean Cloudflare IPs because ECH hides the real name anyway.
     *  - Each fragment link also gets copies on [cleanIps] (up to [copiesPerLink]).
     *  - SNI-type DPI: ECH first, interleaved with fragment. IP-type: fragment first (clean-IP
     *    copies leading), interleaved with ECH. IPv6 links only when the network has IPv6.
     *  - Within a variant, servers are taken round-robin so one node cannot fill a batch.
     */
    fun order(
        all: List<Candidate>,
        dpi: DpiType,
        hasIpv6: Boolean,
        cleanIps: List<String>,
        limit: Int,
        copiesPerLink: Int = 2,
        linksWithCopies: Int = 6,
    ): List<Candidate> {
        val pool = ArrayList<Candidate>()
        for (c in all) {
            if (c.link.isLoopback) {
                if (c.variant == Variant.ECH) {
                    cleanIps.take(3).forEach { ip ->
                        pool += c.copy(link = c.link.relocated(ip), cleanIpCopy = true)
                    }
                }
                continue
            }
            if (c.variant == Variant.IPV6 && !hasIpv6) continue
            pool += c
        }
        val fragments = pool.filter { it.variant == Variant.FRAGMENT }
        val copies = diversify(fragments).take(linksWithCopies).flatMap { c ->
            cleanIps.filter { it != c.link.address }.take(copiesPerLink).map { ip ->
                c.copy(link = c.link.relocated(ip), cleanIpCopy = true)
            }
        }
        val byVariant = (pool + copies).distinctBy { it.uri }.groupBy { it.variant }
        fun group(v: Variant) = diversify(byVariant[v].orEmpty())
        val fm = if (dpi == DpiType.IP) {
            // Clean-IP copies lead, originals follow, both round-robin across servers.
            interleave(group(Variant.FRAGMENT).filter { it.cleanIpCopy }, group(Variant.FRAGMENT).filter { !it.cleanIpCopy })
        } else {
            group(Variant.FRAGMENT)
        }
        val ech = group(Variant.ECH)
        val head = when (dpi) {
            DpiType.IP -> interleave(fm, ech)
            DpiType.SNI, DpiType.UNKNOWN -> interleave(ech, fm)
        }
        return (head + group(Variant.OTHER) + group(Variant.IPV6)).distinctBy { it.uri }.take(limit)
    }

    /** Round-robin across servers (by TLS name). */
    fun diversify(list: List<Candidate>): List<Candidate> {
        val queues = list.groupBy { it.link.sni.ifBlank { it.link.host }.lowercase() }.values.map { ArrayDeque(it) }
        val out = ArrayList<Candidate>(list.size)
        while (out.size < list.size) queues.forEach { q -> q.removeFirstOrNull()?.let(out::add) }
        return out
    }

    fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let(::add)
            b.getOrNull(i)?.let(::add)
        }
    }

    private fun ProxyLink.relocated(ip: String): ProxyLink =
        ProxyLink.parse(ProxyLink.withAddress(raw, ip)) ?: this
}

/** One tested link remembered for a network. */
data class RankedEntry(val uri: String, val delayMs: Long, val testedAt: Long, val failures: Int = 0)

/** Per-network ranking rules: healthy entries, merge of fresh results, failure marks. */
object Ranking {
    const val KEEP = 10
    const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    const val MAX_FAILURES = 2

    fun healthy(entries: List<RankedEntry>, now: Long): List<RankedEntry> =
        entries.filter { now - it.testedAt in 0..MAX_AGE_MS && it.failures < MAX_FAILURES }
            .sortedWith(compareBy<RankedEntry> { it.failures }.thenBy { it.delayMs })

    fun merge(old: List<RankedEntry>, fresh: List<RankedEntry>, now: Long): List<RankedEntry> {
        val freshSorted = fresh.sortedBy { it.delayMs }
        val seen = freshSorted.map { it.uri }.toHashSet()
        return (freshSorted + healthy(old, now).filter { it.uri !in seen }).take(KEEP)
    }

    fun fail(entries: List<RankedEntry>, uri: String) =
        entries.map { if (it.uri == uri) it.copy(failures = it.failures + 1) else it }

    fun succeed(entries: List<RankedEntry>, uri: String) =
        entries.map { if (it.uri == uri) it.copy(failures = 0) else it }

    fun encode(entries: List<RankedEntry>): String =
        entries.joinToString("\n") { "${it.delayMs}\t${it.testedAt}\t${it.failures}\t${it.uri}" }

    fun decode(text: String?): List<RankedEntry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val p = line.split('\t', limit = 4)
        if (p.size != 4 || p[3].isBlank()) return@mapNotNull null
        RankedEntry(p[3], p[0].toLongOrNull() ?: return@mapNotNull null, p[1].toLongOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: 0)
    }.toList()
}

/** DPI decision from probe counts, with the operator as tie-breaker when the probe is inconclusive. */
object DpiDecision {
    fun decide(reachable: Int, fragmentedOk: Int, operatorHint: DpiType): DpiType {
        if (reachable >= 2) {
            return if (fragmentedOk * 3 >= reachable) DpiType.IP else DpiType.SNI
        }
        return operatorHint
    }
}
