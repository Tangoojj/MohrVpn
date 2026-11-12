package com.example.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class SessionLifecycleTest {
    @Test fun teardown_runsOncePerSession_evenWhenCalledConcurrently() {
        val life = SessionLifecycle(stepTimeoutMs = 1_000, hardTimeoutMs = 4_000)
        assertNotNull(life.begin())
        val stepRuns = AtomicInteger()
        val finishes = AtomicInteger()
        val did = AtomicInteger()
        val go = CountDownLatch(1)
        // disconnect, onRevoke, onDestroy, connect-failure... all at once.
        val threads = (1..8).map {
            Thread {
                go.await()
                val steps = listOf(SessionLifecycle.Step("a") { stepRuns.incrementAndGet(); Thread.sleep(50) })
                if (life.teardown("t$it", steps, force = {}, finish = { finishes.incrementAndGet() })) did.incrementAndGet()
            }.apply { start() }
        }
        go.countDown()
        threads.forEach { it.join(5_000) }
        assertEquals(1, did.get())
        assertEquals(1, stepRuns.get())
        assertEquals(1, finishes.get())
        assertFalse(life.isOpen)
        // A second call after it finished is a no-op too.
        assertFalse(life.teardown("again", emptyList(), {}, { finishes.incrementAndGet() }))
        assertEquals(1, finishes.get())
    }

    @Test fun teardown_withoutOpenSession_isNoOp() {
        val life = SessionLifecycle()
        var finished = false
        assertFalse(life.teardown("none", emptyList(), {}, { finished = true }))
        assertFalse(finished)
    }

    @Test fun hungStep_isForcedAfterHardTimeout_andFinishStillRuns() {
        val life = SessionLifecycle(stepTimeoutMs = 2_000, hardTimeoutMs = 1_200)
        life.begin()
        val order = mutableListOf<String>()
        var forcedSeen = false
        val t0 = System.currentTimeMillis()
        val steps = listOf(
            SessionLifecycle.Step("tun-down") { synchronized(order) { order += "tun" } },
            SessionLifecycle.Step("hev-stop") { Thread.sleep(60_000) }, // a hung native stop
            SessionLifecycle.Step("engine-stop") { synchronized(order) { order += "engine" } },
        )
        assertTrue(life.teardown("hang", steps, force = { synchronized(order) { order += "force" } }, finish = { forcedSeen = it; synchronized(order) { order += "finish" } }))
        val took = System.currentTimeMillis() - t0
        assertTrue("took $took ms", took < 2_500)
        assertTrue(forcedSeen)
        assertEquals(listOf("tun", "force", "finish"), synchronized(order) { order.toList() })
    }

    @Test fun begin_waitsForTeardownInFlight() {
        val life = SessionLifecycle(stepTimeoutMs = 2_000, hardTimeoutMs = 3_000)
        life.begin()
        val inStep = CountDownLatch(1)
        val tearer = Thread {
            life.teardown("slow", listOf(SessionLifecycle.Step("slow") { inStep.countDown(); Thread.sleep(400) }), {}, {})
        }.apply { start() }
        inStep.await()
        val t0 = System.currentTimeMillis()
        val gen = life.begin()
        val waited = System.currentTimeMillis() - t0
        tearer.join()
        assertEquals(2L, gen)
        assertTrue("begin did not wait ($waited ms)", waited >= 250)
        assertTrue(life.isOpen)
    }

    @Test fun failingStep_doesNotStopTheRest() {
        val life = SessionLifecycle()
        life.begin()
        val ran = mutableListOf<String>()
        life.teardown(
            "err",
            listOf(
                SessionLifecycle.Step("boom") { throw IllegalStateException("x") },
                SessionLifecycle.Step("next") { synchronized(ran) { ran += "next" } },
            ),
            {}, { synchronized(ran) { ran += "finish" } },
        )
        assertEquals(listOf("next", "finish"), ran)
    }
}
