package com.example.tor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorConfigTest {
    @Test fun torrc_usesWebtunnelBridgesViaLyrebird() {
        val bridges = listOf("webtunnel [2001:db8::1]:443 ABCDEF url=https://a.example/x ver=0.0.1")
        val rc = TorConfig.torrc("/data/tor", 9050, "/lib/liblyrebird.so", bridges, 1234)
        assertTrue(rc.contains("UseBridges 1"))
        assertTrue(rc.contains("ClientTransportPlugin webtunnel exec /lib/liblyrebird.so"))
        assertTrue(rc.contains("Bridge webtunnel"))
        assertTrue(rc.contains("9050"))
        assertTrue(rc.contains("ClientUseIPv6 0"))
    }

    @Test fun pickBridges_isBoundedAndPrefersRemembered() {
        val all = (1..20).map { "webtunnel 1.1.1.$it:443 FP$it url=https://b$it.example/p" }
        val picked = TorConfig.pickBridges(all, 5, preferred = listOf("FP18"), seed = 7)
        assertEquals(5, picked.size)
        assertEquals(all[17], picked.first())
        assertEquals(picked.size, picked.distinct().size)
    }
}
