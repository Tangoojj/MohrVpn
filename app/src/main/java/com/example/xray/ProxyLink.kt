package com.example.xray

import java.net.URLDecoder

/**
 * One `vless://` or `trojan://` share link, split into its parts.
 *
 * Query keys are lower-cased; values are URL-decoded. The original text is kept in [raw] so a
 * link can be stored and compared exactly as it appeared in the list.
 */
data class ProxyLink(
    val raw: String,
    val protocol: String,
    val credential: String,
    val address: String,
    val port: Int,
    val params: Map<String, String>,
    val name: String,
) {
    fun param(key: String): String = params[key].orEmpty()

    val security: String get() = param("security").ifBlank { "none" }.lowercase()
    val network: String get() = param("type").ifBlank { "tcp" }.lowercase()
    val sni: String get() = param("sni").ifBlank { param("peer") }
    val host: String get() = param("host")
    val isIpv6: Boolean get() = address.contains(':')
    val isLoopback: Boolean
        get() = address == "127.0.0.1" || address.equals("localhost", true) || address == "::1"

    /** Value of the ECH parameter, under any of the names links use for it. */
    val ech: String get() = (params["ech"] ?: params["echconfiglist"] ?: params["echcl"]).orEmpty().trim()

    /** Same server (protocol, credential, TLS name, path): copies of one node share this. */
    val nodeKey: String
        get() = listOf(protocol, credential, sni.ifBlank { host }.lowercase(), param("path")).joinToString("|")

    companion object {
        val SUPPORTED = setOf("vless", "trojan")

        fun parse(text: String): ProxyLink? {
            val line = text.trim()
            val scheme = line.substringBefore("://", "").lowercase()
            if (scheme !in SUPPORTED) return null
            val rest = line.substringAfter("://")
            val name = decode(rest.substringAfter('#', ""))
            val noFragment = rest.substringBefore('#')
            val query = noFragment.substringAfter('?', "")
            val authority = noFragment.substringBefore('?').substringBefore('/')
            val at = authority.lastIndexOf('@')
            if (at <= 0) return null
            val credential = decode(authority.substring(0, at))
            val hostPort = authority.substring(at + 1)
            val (host, portText) = if (hostPort.startsWith("[")) {
                hostPort.substringAfter('[').substringBefore(']') to hostPort.substringAfter("]:", "")
            } else {
                hostPort.substringBeforeLast(':') to hostPort.substringAfterLast(':', "")
            }
            val port = portText.toIntOrNull() ?: 443
            if (host.isBlank() || credential.isBlank() || port !in 1..65535) return null
            return ProxyLink(line, scheme, credential, host, port, parseQuery(query), name.trim())
        }

        fun parseQuery(query: String): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            if (query.isBlank()) return out
            for (part in query.split('&')) {
                if (part.isBlank()) continue
                val key = decode(part.substringBefore('=')).trim().lowercase()
                val value = decode(part.substringAfter('=', ""))
                if (key.isNotEmpty() && out[key].isNullOrEmpty()) out[key] = value
            }
            return out
        }

        /** Link text with its server address replaced (used for clean-IP copies). */
        fun withAddress(raw: String, address: String): String {
            val scheme = raw.substringBefore("://")
            val rest = raw.substringAfter("://")
            val end = rest.indexOfFirst { it == '?' || it == '#' || it == '/' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            val at = authority.lastIndexOf('@')
            val user = authority.substring(0, at)
            val hostPort = authority.substring(at + 1)
            val port = if (hostPort.startsWith("[")) hostPort.substringAfter("]:", "443") else hostPort.substringAfterLast(':', "443")
            val host = if (address.contains(':')) "[$address]" else address
            return "$scheme://$user@$host:$port${rest.substring(end)}"
        }

        private fun decode(s: String): String =
            runCatching { URLDecoder.decode(s.replace("+", "%2B"), "UTF-8") }.getOrDefault(s)
    }
}
