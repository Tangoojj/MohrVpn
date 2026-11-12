package com.example.warp

import android.content.Context
import ca.psiphon.PsiphonTunnel
import com.example.core.AppLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Thin wrapper around Psiphon's Android library (GPL-3.0), used as a local SOCKS provider.
 * One instance per process; [stop] must be called before starting again.
 */
class PsiphonClient(context: Context) {
    /**
     * Kept under its own name: inside the HostService object below, a bare `context` would bind
     * to the object's own getContext() and recurse forever (StackOverflowError "stack size …KB").
     */
    private val appContext: Context = context.applicationContext ?: context

    @Volatile var lastNotice: String = ""
        private set
    @Volatile var region: String = ""
        private set

    private var tunnel: PsiphonTunnel? = null
    private var connected = CompletableDeferred<Boolean>()
    @Volatile private var config: String = ""
    var onNotice: (String) -> Unit = {}

    /** Visible for tests: the host Psiphon calls back into. */
    internal val host = object : PsiphonTunnel.HostService {
        override fun getContext(): Context = appContext
        override fun getPsiphonConfig(): String = config
        override fun onDiagnosticMessage(message: String) {
            val m = message.take(200)
            lastNotice = m
            onNotice(m)
        }
        override fun onConnecting() = AppLog.i("psiphon", "connecting")
        override fun onConnected() {
            AppLog.i("psiphon", "connected")
            connected.complete(true)
        }
        override fun onConnectedServerRegion(region: String) {
            this@PsiphonClient.region = region
        }
        override fun onExiting() {
            connected.complete(false)
        }
    }

    /**
     * Starts Psiphon on [socksPort], optionally through the SOCKS proxy on [upstreamPort], and
     * waits until it has a tunnel. Returns false on timeout or failure.
     */
    suspend fun start(socksPort: Int, upstreamPort: Int?, timeoutSeconds: Int): Boolean {
        stop()
        val dir = File(appContext.filesDir, "psiphon").apply { mkdirs() }
        config = PsiphonConfig.build(dir.absolutePath, socksPort, upstreamPort, timeoutSeconds)
        connected = CompletableDeferred()
        val entries = runCatching { appContext.assets.open("psiphon/server_entries.txt").bufferedReader().readText() }.getOrDefault("")
        return try {
            val t = PsiphonTunnel.newPsiphonTunnel(host)
            tunnel = t
            t.startTunneling(entries)
            withTimeoutOrNull(timeoutSeconds * 1000L + 5_000) { connected.await() } == true
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            // Errors too (StackOverflowError, LinkageError from the Go library): the caller then
            // falls back to WARP alone instead of failing the whole engine.
            AppLog.w("psiphon", "start failed", t)
            lastNotice = "${t.javaClass.simpleName}: ${t.message.orEmpty()}".take(200)
            stop()
            false
        }
    }

    fun stop() {
        val t = tunnel ?: return
        tunnel = null
        runCatching { t.stop() }
    }
}
