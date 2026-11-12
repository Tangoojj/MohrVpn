package com.example.xray

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** Fragment switch: off by default, persisted, and its ranking kept apart from the off ranking. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class XraySettingsTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test fun fragment_defaultOff_persists() {
        assertFalse(XraySettings(ctx).fragment)
        XraySettings(ctx).fragment = true
        assertTrue(XraySettings(ctx).fragment)
        assertTrue(ctx.getSharedPreferences(XraySettings.PREFS, 0).getBoolean(XraySettings.KEY_FRAGMENT, false))
        XraySettings(ctx).fragment = false
        assertFalse(XraySettings(ctx).fragment)
    }

    @Test fun ranking_keptApartPerFragment() {
        val s = AutoStore(ctx)
        val now = System.currentTimeMillis()
        s.saveRanking(XraySettings.rankKey("wifi:x", false), listOf(RankedEntry("vless://off@1.1.1.1:443", 100, now)))
        s.saveRanking(XraySettings.rankKey("wifi:x", true), listOf(RankedEntry("vless://on@1.1.1.1:443", 100, now)))
        assertEquals("vless://off@1.1.1.1:443", s.ranking("wifi:x").single().uri)
        assertEquals("vless://on@1.1.1.1:443", s.ranking("wifi:x|frag").single().uri)
    }
}
