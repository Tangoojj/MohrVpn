package com.example.vpn

import com.example.core.AppLog
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Serializes the VPN session lifecycle (connect / disconnect / revoke / destroy) and makes the
 * teardown idempotent and bounded.
 *
 * - [begin] opens a session under the lifecycle lock (it waits for a teardown in flight).
 * - [teardown] runs its steps once per session, whoever calls it first; every later or
 *   concurrent call returns false at once. Each step runs on its own thread with a timeout, so a
 *   hung native call cannot block the rest. When the hard deadline passes the remaining steps are
 *   skipped, [force] runs (kill every core), and [finish] always runs last.
 */
class SessionLifecycle(
    private val stepTimeoutMs: Long = 3_000,
    private val hardTimeoutMs: Long = 8_000,
) {
    class Step(val name: String, val timeoutMs: Long? = null, val run: () -> Unit)

    private val lock = ReentrantLock()
    private val guard = Any()
    @Volatile var generation = 0L
        private set
    private var open = false

    /** True while a session is open and not yet torn down. */
    val isOpen: Boolean get() = synchronized(guard) { open }

    /**
     * Opens a new session and returns its generation, or null when the lifecycle lock could not be
     * taken within [waitMs] (a teardown is stuck beyond its own hard timeout).
     */
    fun begin(waitMs: Long = hardTimeoutMs + 2_000): Long? {
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return null
        try {
            synchronized(guard) {
                open = true
                generation++
                return generation
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * Tears the open session down. Returns true if this call did the work, false if there was
     * no open session (already torn down, or a teardown is in flight).
     */
    fun teardown(reason: String, steps: List<Step>, force: () -> Unit, finish: (forced: Boolean) -> Unit): Boolean {
        // Take the lifecycle lock first, so no new session can open between "closed" and the steps.
        if (!lock.tryLock(hardTimeoutMs + 2_000, TimeUnit.MILLISECONDS)) return false
        val wasOpen = synchronized(guard) { open.also { open = false } }
        if (!wasOpen) {
            lock.unlock()
            return false
        }
        val started = System.currentTimeMillis()
        val deadline = started + hardTimeoutMs
        var forced = false
        try {
            AppLog.i("vpn", "teardown ($reason)")
            for (s in steps) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) {
                    forced = true
                    AppLog.i("vpn", "teardown deadline hit before '${s.name}'")
                    break
                }
                val ok = runBounded(s.name, minOf(s.timeoutMs ?: stepTimeoutMs, left), s.run)
                if (!ok) AppLog.i("vpn", "teardown step '${s.name}' did not finish in time")
                if (!ok && System.currentTimeMillis() >= deadline) {
                    forced = true
                    break
                }
            }
            if (forced) {
                AppLog.i("vpn", "forced teardown after ${System.currentTimeMillis() - started} ms")
                runCatching(force)
            }
        } finally {
            try {
                runCatching { finish(forced) }.onFailure { AppLog.w("vpn", "teardown finish failed", it) }
            } finally {
                lock.unlock()
            }
        }
        AppLog.i("vpn", "teardown done in ${System.currentTimeMillis() - started} ms")
        return true
    }

    companion object {
        /** Runs [block] on its own daemon thread; returns false if it is still running after [timeoutMs]. */
        fun runBounded(name: String, timeoutMs: Long, block: () -> Unit): Boolean {
            val t = Thread({
                runCatching(block).onFailure { AppLog.w("vpn", "teardown step '$name' failed", it) }
            }, "teardown-$name")
            t.isDaemon = true
            t.start()
            t.join(timeoutMs.coerceAtLeast(1))
            return !t.isAlive
        }
    }
}
