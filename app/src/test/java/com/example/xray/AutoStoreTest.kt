package com.example.xray

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Per-network memory: DPI verdict, ranking and clean IPs are kept separately per network key. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class AutoStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun perNetwork_valuesDoNotLeakAcrossNetworks() {
        val s = AutoStore(ctx)
        s.saveDpi("cell:43235", DpiType.IP)
        s.saveDpi("wifi:abc", DpiType.SNI)
        assertEquals(DpiType.IP, s.dpi("cell:43235"))
        assertEquals(DpiType.SNI, s.dpi("wifi:abc"))
        assertNull(s.dpi("cell:43211"))

        val now = System.currentTimeMillis()
        s.saveRanking("cell:43235", listOf(RankedEntry("vless://a@1.1.1.1:443", 120, now)))
        s.updateRanking("cell:43235") { Ranking.fail(it, "vless://a@1.1.1.1:443") }
        assertEquals(1, s.ranking("cell:43235").single().failures)
        assertTrue(s.ranking("wifi:abc").isEmpty())

        s.saveCleanIps("wifi:abc", listOf("104.16.132.229", "172.67.70.1"))
        assertEquals(listOf("104.16.132.229", "172.67.70.1"), AutoStore(ctx).cleanIps("wifi:abc"))
        assertTrue(s.cleanIps("cell:43235").isEmpty())
    }

    @Test fun staleDpiVerdict_isIgnored() {
        ctx.getSharedPreferences("xray_auto", Context.MODE_PRIVATE).edit()
            .putString("dpi:old", "IP|${System.currentTimeMillis() - AutoStore.DPI_TTL_MS - 1000}").commit()
        assertNull(AutoStore(ctx).dpi("old"))
    }

    @Test fun echCache_roundTrip() {
        val s = AutoStore(ctx)
        s.echCache.put("cloudflare-ech.com", "AEX+DQBB")
        assertEquals("AEX+DQBB", s.echCache.get("cloudflare-ech.com"))
        assertNull(s.echCache.get("other.example"))
    }
}
