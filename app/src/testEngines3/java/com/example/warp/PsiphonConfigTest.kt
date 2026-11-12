package com.example.warp

import com.example.core.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PsiphonConfigTest {
    @Test fun psiphonConfig_chainsThroughWarpOrDialsDirect() {
        @Suppress("UNCHECKED_CAST")
        val chained = Json.parse(PsiphonConfig.build("/d", 1081, 40000, 30)) as Map<String, Any?>
        assertEquals("socks5://127.0.0.1:40000", chained["UpstreamProxyURL"])
        assertEquals(PsiphonConfig.CHAINABLE, chained["LimitTunnelProtocols"])
        @Suppress("UNCHECKED_CAST")
        val direct = Json.parse(PsiphonConfig.build("/d", 1081, null, 60)) as Map<String, Any?>
        assertFalse(direct.containsKey("UpstreamProxyURL"))
        assertEquals(PsiphonConfig.DIRECT, direct["LimitTunnelProtocols"])
        assertEquals(1081.0, (direct["LocalSocksProxyPort"] as Number).toDouble(), 0.0)
    }
}
