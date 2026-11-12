package com.example.tun

import java.io.File

/**
 * JNI binding for hev-socks5-tunnel (MIT), built from upstream 2.19.3 with
 * PKGNAME=com/example/tun and CLSNAME=HevTunnel so its natives register on this class.
 *
 * It reads IP packets from the VPN's TUN fd and relays TCP/UDP through a local SOCKS5 server.
 * DNS is answered by its mapdns module with fake addresses from [FAKE_NET]; connections to those
 * addresses go to the SOCKS server by host name, so names are resolved at the far end.
 */
object HevTunnel {
    const val TUN_IPV4 = "198.18.0.1"
    const val TUN_IPV6 = "fd6e:6f68:7276::1"
    const val MAP_DNS = "198.18.0.2"
    const val FAKE_NET = "100.64.0.0"
    const val FAKE_MASK = "255.192.0.0"
    const val MTU = 1500

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }

    @JvmStatic external fun TProxyStartService(configPath: String, fd: Int): Boolean
    @JvmStatic external fun TProxyStopService(): Boolean
    @JvmStatic external fun TProxyIsRunning(): Boolean
    /** [tx packets, tx bytes, rx packets, rx bytes] as seen on the TUN side. */
    @JvmStatic external fun TProxyGetStats(): LongArray?

    fun config(socksPort: Int, udpRelay: Boolean): String = """
        tunnel:
          mtu: $MTU
          ipv4: '$TUN_IPV4'
          ipv6: '$TUN_IPV6'
          icmp: 'off'
        socks5:
          port: $socksPort
          address: '127.0.0.1'
          udp: 'udp'
        mapdns:
          address: '$MAP_DNS'
          port: 53
          network: '$FAKE_NET'
          netmask: '$FAKE_MASK'
          cache-size: 10000
        misc:
          task-stack-size: 86016
          tcp-buffer-size: 65536
          connect-timeout: 15000
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: ${if (udpRelay) 60000 else 2000}
          log-level: warn
    """.trimIndent() + "\n"

    /** Writes the config and starts relaying [fd] to the SOCKS server on [socksPort]. */
    fun start(dir: File, fd: Int, socksPort: Int, udpRelay: Boolean): Boolean {
        val file = File(dir, "hev.yml").apply { writeText(config(socksPort, udpRelay)) }
        return TProxyStartService(file.absolutePath, fd)
    }

    fun stop() {
        runCatching { TProxyStopService() }
    }

    fun stats(): LongArray = runCatching { TProxyGetStats() }.getOrNull()?.takeIf { it.size >= 4 } ?: LongArray(4)
}
