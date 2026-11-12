package com.example

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The launcher activity starts and composes its idle screen without crashing.
 * The Compose test rule pauses infinite animations (spinner, wave), which otherwise keep
 * Robolectric's main looper busy forever.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class StartupTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Config(sdk = [34])
    @Test fun mainActivity_launchesAndShowsBranding() {
        rule.mainClock.advanceTimeBy(1_000)
        assertFalse(rule.activity.isFinishing)
        assertTrue(rule.onAllNodesWithText(BuildConfig.VERSION_LABEL_FA, substring = true).fetchSemanticsNodes().isNotEmpty())
        if (BuildConfig.ENGINES_XRAY_ONLY) {
            // Xray-only build: no engine rail, Tor/WARP nowhere on screen.
            assertTrue("Xray selection missing", present("Auto"))
            assertFalse("Tor visible in Xray-only build", present("Tor"))
            assertFalse("WARP visible in Xray-only build", present("WARP"))
            assertTrue(com.example.engine.Engines.available == listOf(com.example.engine.EngineId.XRAY))
            assertTrue(com.example.engine.EngineId.from("TOR") == com.example.engine.EngineId.XRAY)
        } else {
            assertTrue("engine Xray missing", present("Xray"))
            assertTrue("engine Tor missing", present("Tor"))
            assertTrue("engine WARP missing", present("WARP"))
        }
    }

    private fun present(name: String) =
        rule.onAllNodesWithText(name, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            rule.onAllNodesWithContentDescription(name, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Config(sdk = [26])
    @Test fun mainActivity_launchesOnOreo() {
        rule.mainClock.advanceTimeBy(1_000)
        assertFalse(rule.activity.isFinishing)
    }
}
