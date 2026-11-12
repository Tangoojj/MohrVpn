package com.example.xray

import com.example.core.Json

/**
 * Builds Xray-core JSON configs from [ProxyLink]s.
 *
 * Two shapes are produced:
 *  - a test config: one SOCKS inbound per candidate, each routed to its own outbound, so a whole
 *    batch of candidates is measured with a single Xray process;
 *  - a run config: one SOCKS inbound (TCP + UDP) that the TUN bridge feeds, routed to the chosen
 *    outbound, with QUIC refused so apps fall back to TCP at once.
 */
object XrayConfig {
    const val LISTEN = "127.0.0.1"

    /** Private / local ranges that never go through the proxy. */
    val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128",
    )

    class UnsupportedLink(message: String) : IllegalArgumentException(message)

    /** Tag of the freedom outbound that splits the TLS ClientHello (fragment switch ON). */
    const val FRAGMENT_TAG = "fragment"

    /** The fragment dialer: proxy outbounds dial their TCP through it (streamSettings.sockopt.dialerProxy). */
    fun fragmentOutbound(): Map<String, Any?> = linkedMapOf(
        "tag" to FRAGMENT_TAG,
        "protocol" to "freedom",
        "settings" to linkedMapOf(
            "fragment" to linkedMapOf("packets" to "tlshello", "length" to "100-200", "interval" to "10-20"),
        ),
    )

    /**
     * Whether the fragment dialer applies to [link]: TCP-based transports (raw/ws/grpc/xhttp/
     * httpupgrade) with TLS or Reality. Skipped: no TLS (no ClientHello to split), links that ship
     * their own finalmask (already fragmented by the link author), and xhttp forced to HTTP/3 (QUIC, UDP).
     */
    fun fragmentApplies(link: ProxyLink): Boolean {
        if (link.security != "tls" && link.security != "reality") return false
        if (link.network !in FRAGMENT_NETWORKS) return false
        if (link.param("fm").isNotBlank() || link.param("finalmask").isNotBlank()) return false
        val alpn = link.param("alpn").split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (alpn.isNotEmpty() && alpn.all { it == "h3" }) return false
        return true
    }

    private val FRAGMENT_NETWORKS = setOf("tcp", "raw", "ws", "websocket", "grpc", "gun", "httpupgrade", "xhttp", "splithttp")

    /** Outbound object for [link]. [echConfigList] replaces the link's own ECH value when given. */
    fun outbound(link: ProxyLink, tag: String, echConfigList: String? = null, fragment: Boolean = false): Map<String, Any?> {
        val settings: Map<String, Any?> = when (link.protocol) {
            "vless" -> {
                val user = linkedMapOf<String, Any?>(
                    "id" to link.credential,
                    "encryption" to link.param("encryption").ifBlank { "none" },
                )
                link.param("flow").takeIf { it.isNotBlank() }?.let { user["flow"] = it }
                val server = linkedMapOf<String, Any?>(
                    "address" to link.address, "port" to link.port, "users" to listOf(user),
                )
                linkedMapOf("vnext" to listOf(server))
            }
            "trojan" -> linkedMapOf(
                "servers" to listOf(linkedMapOf("address" to link.address, "port" to link.port, "password" to link.credential)),
            )
            else -> throw UnsupportedLink("protocol ${link.protocol}")
        }
        val out = linkedMapOf<String, Any?>(
            "tag" to tag,
            "protocol" to link.protocol,
            "settings" to settings,
            "streamSettings" to streamSettings(link, echConfigList, fragment && fragmentApplies(link)),
        )
        if (link.protocol == "vless" && link.param("packetencoding").isNotBlank()) {
            @Suppress("UNCHECKED_CAST")
            (settings as MutableMap<String, Any?>)["packetEncoding"] = link.param("packetencoding")
        }
        return out
    }

    private fun streamSettings(link: ProxyLink, echConfigList: String?, viaFragment: Boolean = false): Map<String, Any?> {
        val network = when (val n = link.network) {
            "tcp", "raw" -> "raw"
            "ws", "websocket" -> "ws"
            "grpc", "gun" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            else -> throw UnsupportedLink("transport $n")
        }
        val stream = linkedMapOf<String, Any?>("network" to network)
        val security = link.security
        stream["security"] = when (security) {
            "tls", "reality", "none" -> security
            "" -> "none"
            else -> throw UnsupportedLink("security $security")
        }
        when (security) {
            "tls" -> stream["tlsSettings"] = tlsSettings(link, echConfigList)
            "reality" -> stream["realitySettings"] = realitySettings(link)
        }
        when (network) {
            "raw" -> if (link.param("headertype") == "http") {
                stream["rawSettings"] = mapOf(
                    "header" to mapOf(
                        "type" to "http",
                        "request" to mapOf(
                            "path" to listOf(link.param("path").ifBlank { "/" }),
                            "headers" to mapOf("Host" to listOf(link.host.ifBlank { link.sni })),
                        ),
                    ),
                )
            }
            "ws" -> stream["wsSettings"] = linkedMapOf("path" to link.param("path").ifBlank { "/" }, "host" to link.host)
            "httpupgrade" -> stream["httpupgradeSettings"] = linkedMapOf("path" to link.param("path").ifBlank { "/" }, "host" to link.host)
            "grpc" -> {
                val grpc = linkedMapOf<String, Any?>("serviceName" to link.param("servicename"))
                link.param("authority").takeIf { it.isNotBlank() }?.let { grpc["authority"] = it }
                if (link.param("mode") == "multi") grpc["multiMode"] = true
                stream["grpcSettings"] = grpc
            }
            "xhttp" -> {
                val x = linkedMapOf<String, Any?>(
                    "path" to link.param("path").ifBlank { "/" },
                    "host" to link.host,
                    "mode" to link.param("mode").ifBlank { "auto" },
                )
                link.param("extra").takeIf { it.isNotBlank() }?.let { text ->
                    val extra = Json.parseOrNull(text) as? Map<*, *> ?: throw UnsupportedLink("bad xhttp extra")
                    x["extra"] = extra
                }
                stream["xhttpSettings"] = x
            }
        }
        // A link that ships its own finalmask (fragment / merge / noise) knows its path best.
        link.param("fm").ifBlank { link.param("finalmask") }.takeIf { it.isNotBlank() }?.let { text ->
            val fm = Json.parseOrNull(text) as? Map<*, *> ?: throw UnsupportedLink("bad finalmask")
            stream["finalmask"] = fm
        }
        val sockopt = linkedMapOf<String, Any?>("tcpNoDelay" to true)
        if (viaFragment) sockopt["dialerProxy"] = FRAGMENT_TAG
        stream["sockopt"] = sockopt
        return stream
    }

    private fun tlsSettings(link: ProxyLink, echConfigList: String?): Map<String, Any?> {
        val tls = linkedMapOf<String, Any?>()
        val serverName = link.sni.ifBlank { link.host }
        if (serverName.isNotBlank()) tls["serverName"] = serverName
        link.param("alpn").split(',').map(String::trim).filter(String::isNotEmpty)
            .takeIf { it.isNotEmpty() }?.let { tls["alpn"] = it }
        link.param("fp").takeIf { it.isNotBlank() }?.let { tls["fingerprint"] = it }
        link.param("cs").ifBlank { link.param("ciphersuites") }.takeIf { it.isNotBlank() }?.let { tls["cipherSuites"] = it }
        val ech = echConfigList ?: link.ech
        if (ech.isNotBlank()) tls["echConfigList"] = ech
        return tls
    }

    private fun realitySettings(link: ProxyLink): Map<String, Any?> {
        val pbk = link.param("pbk")
        if (pbk.isBlank()) throw UnsupportedLink("reality without public key")
        val r = linkedMapOf<String, Any?>(
            "serverName" to link.sni,
            "fingerprint" to link.param("fp").ifBlank { "chrome" },
            "publicKey" to pbk,
            "shortId" to link.param("sid"),
        )
        link.param("spx").takeIf { it.isNotBlank() }?.let { r["spiderX"] = it }
        link.param("pqv").takeIf { it.isNotBlank() }?.let { r["mldsa65Verify"] = it }
        return r
    }

    data class BatchEntry(val tag: String, val port: Int, val link: ProxyLink, val ech: String? = null)

    /** One Xray process, one SOCKS port per candidate. */
    fun testConfig(entries: List<BatchEntry>, fragment: Boolean = false): String {
        require(entries.isNotEmpty())
        val inbounds = entries.map { e ->
            linkedMapOf(
                "tag" to "in-${e.tag}", "listen" to LISTEN, "port" to e.port, "protocol" to "socks",
                "settings" to linkedMapOf("auth" to "noauth", "udp" to false),
            )
        }
        val outbounds = entries.map { e -> outbound(e.link, "out-${e.tag}", e.ech, fragment) } +
            listOfNotNull(fragmentOutbound().takeIf { fragment && entries.any { fragmentApplies(it.link) } })
        val rules = entries.map { e ->
            linkedMapOf("type" to "field", "inboundTag" to listOf("in-${e.tag}"), "outboundTag" to "out-${e.tag}")
        }
        return Json.write(
            linkedMapOf(
                "log" to linkedMapOf("loglevel" to "warning", "access" to "none"),
                "inbounds" to inbounds,
                "outbounds" to outbounds,
                "routing" to linkedMapOf("domainStrategy" to "AsIs", "rules" to rules),
            ),
        )
    }

    /** Config for the live tunnel: SOCKS on [socksPort] (TCP + UDP) → the chosen link. */
    fun runConfig(link: ProxyLink, socksPort: Int, ech: String? = null, fragment: Boolean = false): String {
        val inbound = linkedMapOf(
            "tag" to "socks-in", "listen" to LISTEN, "port" to socksPort, "protocol" to "socks",
            "settings" to linkedMapOf("auth" to "noauth", "udp" to true, "ip" to LISTEN),
            "sniffing" to linkedMapOf("enabled" to true, "destOverride" to listOf("http", "tls"), "routeOnly" to true),
        )
        val outbounds = listOf(
            outbound(link, "proxy", ech, fragment),
            linkedMapOf("tag" to "direct", "protocol" to "freedom", "settings" to emptyMap<String, Any?>()),
            linkedMapOf("tag" to "block", "protocol" to "blackhole", "settings" to emptyMap<String, Any?>()),
        ) + listOfNotNull(fragmentOutbound().takeIf { fragment && fragmentApplies(link) })
        val rules = listOf(
            // QUIC over a TCP proxy is slow and stalls; refusing it makes apps use TCP at once.
            linkedMapOf("type" to "field", "network" to "udp", "port" to "443", "outboundTag" to "block"),
            linkedMapOf("type" to "field", "ip" to PRIVATE_CIDRS, "outboundTag" to "direct"),
            linkedMapOf("type" to "field", "network" to "tcp,udp", "outboundTag" to "proxy"),
        )
        return Json.write(
            linkedMapOf(
                "log" to linkedMapOf("loglevel" to "warning", "access" to "none"),
                "policy" to linkedMapOf(
                    "levels" to linkedMapOf("0" to linkedMapOf("handshake" to 8, "connIdle" to 300, "uplinkOnly" to 2, "downlinkOnly" to 5)),
                ),
                "inbounds" to listOf(inbound),
                "outbounds" to outbounds,
                "routing" to linkedMapOf("domainStrategy" to "AsIs", "rules" to rules),
            ),
        )
    }
}
