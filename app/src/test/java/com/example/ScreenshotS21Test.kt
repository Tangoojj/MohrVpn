package com.example

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import com.example.engine.ConnectStore
import com.example.engine.EngineId
import com.example.engine.Engines
import com.example.engine.Phase
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the home screen at Galaxy S21 size (1080x2400 px, 420 dpi = 411x914 dp) and checks that
 * the engine switcher sits right above the connect button and the whole screen fits without
 * scrolling, leaving room for the status bar and a 3-button navigation bar (worst case).
 * PNGs go to $MOHR_SHOT_DIR (default build/shots).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h914dp-port-420dpi")
class ScreenshotS21Test {
    companion object {
        /** Robolectric native graphics has no linux-aarch64 build; run this class on an x86_64 JVM (-PtestJava). */
        @JvmStatic @org.junit.BeforeClass fun needsNativeGraphicsHost() {
            val unsupported = System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch") == "aarch64"
            org.junit.Assume.assumeFalse("Robolectric native graphics unsupported on linux-aarch64", unsupported)
        }
    }

    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val outDir = File(System.getenv("MOHR_SHOT_DIR") ?: "build/shots").apply { mkdirs() }

    private fun shot(name: String) {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        // Software-draw the window (captureToImage waits for a hardware frame, too slow here).
        rule.runOnUiThread {
            val v = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(android.graphics.Canvas(bmp))
            File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    /** Like [shot], but draws every window (activity + bottom sheet / dialog windows) at its screen position. */
    private fun shotAllWindows(name: String) {
        rule.mainClock.advanceTimeBy(1_500)
        rule.waitForIdle()
        rule.runOnUiThread {
            val root = rule.activity.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val inst = wmg.getMethod("getInstance").invoke(null)
            val f = wmg.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = (f.get(inst) as List<android.view.View>).toList()
            println("windows: ${views.size}")
            for (v in views) {
                if (v.width == 0 || v.height == 0) continue
                val loc = IntArray(2).also { v.getLocationOnScreen(it) }
                canvas.save(); canvas.translate(loc[0].toFloat(), loc[1].toFloat()); v.draw(canvas); canvas.restore()
            }
            File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun bottomDp(tag: String): Float {
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return b.bottom / rule.density.density
    }

    private fun topDp(tag: String): Float {
        val b = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return b.top / rule.density.density
    }

    @Test fun idle_fitsOneScreen_switcherAboveConnect() {
        rule.mainClock.advanceTimeBy(1_000)
        // Robolectric has no system bars; on the phone the content is shifted down by the status
        // bar (~24-32 dp) and must end above the navigation bar (gesture ~16 dp, buttons 48 dp).
        val usable = 914f - 32f - 48f
        val cardBottom = bottomDp("bottomCard")
        println("S21 layout: bottomCard bottom=$cardBottom dp (usable $usable dp)")
        assertTrue("bottom card ends at $cardBottom dp, beyond $usable dp", cardBottom <= usable)
        if (Engines.available.size > 1) {
            val swBottom = bottomDp("engineSwitcher")
            val powerTop = topDp("powerButton")
            println("S21 layout: switcher bottom=$swBottom dp, power top=$powerTop dp")
            assertTrue("switcher not directly above connect", swBottom <= powerTop && powerTop - swBottom < 16f)
        }
        shot("s21_idle_xray")
    }

    @Test fun torSelected_andConnectedStates() {
        if (Engines.available.size < 2) return
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("Tor", useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(500)
        assertTrue(ConnectStore.state.value.engine == EngineId.TOR)
        assertTrue(rule.activity.getSharedPreferences("ui", 0).getString("engine", null) == "TOR")
        shot("s21_idle_tor")
        ConnectStore.update { it.copy(phase = Phase.CONNECTING, engine = EngineId.TOR, steps = listOf("پل‌ها", "انتقال", "دایرکتوری", "مدارها", "تونل"), step = 2, status = "اتصال Tor ۴۵٪", detail = "Loading relay descriptors", startedAt = System.currentTimeMillis()) }
        shot("s21_connecting_tor")
        ConnectStore.update { it.copy(phase = Phase.CONNECTED, status = "اتصال شما امنه", pingMs = 312, countryCode = "DE", selection = "WebTunnel · ۳ پل") }
        rule.mainClock.advanceTimeBy(500)
        val usable = 914f - 32f - 48f
        assertTrue(bottomDp("bottomCard") <= usable)
        shot("s21_connected_tor")
        // Switching is locked while connected: the selection does not change.
        rule.onNodeWithText("WARP", useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(300)
        assertTrue(ConnectStore.state.value.engine == EngineId.TOR)
        ConnectStore.update { com.example.engine.ConnectState() }
        rule.activity.getSharedPreferences("ui", 0).edit().remove("engine").commit()
    }

    /** Menu with the «فرگمنت (Fragment)» switch: off by default, persisted, locked while connected. */
    @Test fun menu_fragmentToggle() {
        val prefs = rule.activity.getSharedPreferences(com.example.xray.XraySettings.PREFS, 0)
        prefs.edit().clear().commit()
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithContentDescription("منو").performClick()
        rule.mainClock.advanceTimeBy(1_200)
        rule.onNodeWithText("فرگمنت (Fragment)", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fragmentSwitch", useUnmergedTree = true).assertIsOff()
        shotAllWindows("s21_menu_fragment_off")
        rule.onNodeWithTag("fragmentToggle", useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("fragmentSwitch", useUnmergedTree = true).assertIsOn()
        assertTrue(prefs.getBoolean(com.example.xray.XraySettings.KEY_FRAGMENT, false))
        shotAllWindows("s21_menu_fragment_on")
        // Toggle edges stay inside the screen (no clipping): 411 dp wide.
        val b = rule.onNodeWithTag("fragmentToggle", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(b.left >= 0f && b.right / rule.density.density <= 411f)
        // While connected the switch is locked and shows the hint.
        ConnectStore.update { it.copy(phase = Phase.CONNECTED, status = "اتصال شما امنه", pingMs = 210, countryCode = "DE") }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithText("برای تغییر، اول قطع کنید", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("fragmentToggle", useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(300)
        assertTrue(prefs.getBoolean(com.example.xray.XraySettings.KEY_FRAGMENT, false))
        shotAllWindows("s21_menu_fragment_locked")
        ConnectStore.update { com.example.engine.ConnectState() }
        prefs.edit().clear().commit()
    }

    /** Creator line: «Hosseinmbat» at the top of home (gray idle, green connected), «سازنده: Hosseinmbat» at the bottom of About. */
    @Test fun creatorLine_homeAndAbout() {
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Hosseinmbat", useUnmergedTree = true).assertExists()
        // Inside the top row, clear of the menu button and above the brand title.
        val tag = rule.onNodeWithTag("creatorTag", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val menu = rule.onNodeWithContentDescription("منو").fetchSemanticsNode().boundsInRoot
        assertTrue("creator overlaps menu", tag.left > menu.right)
        assertTrue("creator not at top: ${tag.top / rule.density.density} dp", tag.top / rule.density.density < 60f)
        shot("s21_home_creator_idle")
        ConnectStore.update { it.copy(phase = Phase.CONNECTED, status = "اتصال شما امنه", pingMs = 210, countryCode = "DE", selection = "Auto · vless/tls") }
        rule.mainClock.advanceTimeBy(1_000)
        shot("s21_home_creator_connected")
        ConnectStore.update { com.example.engine.ConnectState() }
        rule.mainClock.advanceTimeBy(1_000)
        shot("s21_home_creator_after_disconnect")
        rule.onNodeWithContentDescription("منو").performClick()
        rule.mainClock.advanceTimeBy(1_000)
        rule.onNodeWithText("درباره", useUnmergedTree = true).performClick()
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithText("سازنده: Hosseinmbat", useUnmergedTree = true).assertExists()
        shotAllWindows("s21_about_creator")
    }
}
