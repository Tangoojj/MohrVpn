package com.example.warp

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class PsiphonClientTest {
    /** v2 bug: getContext() called itself forever (StackOverflowError "stack size …KB"). */
    @Test fun hostGetContext_returnsAppContext_withoutRecursion() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val client = PsiphonClient(app)
        assertSame(app.applicationContext, client.host.context)
        assertSame(app.applicationContext, client.host.getContext())
    }

    /** A library Error (no native Go lib here) must be caught so WARP can fall back, not crash the engine. */
    @Test fun start_returnsFalseOnErrors_insteadOfThrowing() = runBlocking {
        val client = PsiphonClient(ApplicationProvider.getApplicationContext())
        assertFalse(client.start(socksPort = 1, upstreamPort = null, timeoutSeconds = 1))
        client.stop()
    }
}
