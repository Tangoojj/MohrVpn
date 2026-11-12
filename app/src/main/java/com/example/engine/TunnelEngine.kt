package com.example.engine

/** The three connection engines. Only one runs at a time. */
enum class EngineId(val title: String, val chip: String) {
    XRAY("Xray", "Xray"),
    TOR("Tor", "Tor"),
    WARP("WARP", "WARP");

    companion object {
        /** Engines packaged in this build; a saved choice that is not packaged falls back to Xray. */
        fun from(name: String?): EngineId = Engines.available.firstOrNull { it.name == name } ?: XRAY
    }
}

/**
 * An engine brings up an upstream tunnel and exposes it as a local SOCKS5 port, which the
 * VPN service feeds from the TUN device. Engines run their cores outside the VPN (the app is
 * excluded from its own tunnel), so their own traffic never loops back.
 */
interface TunnelEngine {
    val id: EngineId

    /** Labels of the connect stepper, in order. */
    val steps: List<String>

    /** Whether the SOCKS port relays UDP (otherwise only DNS is handled, by the TUN bridge). */
    val relaysUdp: Boolean

    /**
     * Brings the tunnel up and returns the SOCKS port. Progress goes to [r]. Throws
     * [EngineException] with a user-facing Persian message on failure.
     */
    suspend fun start(r: ConnectReporter): Int

    /**
     * Called when the running tunnel stopped working. Returns the (possibly new) SOCKS port once
     * traffic flows again, or throws when nothing works any more.
     */
    suspend fun recover(r: ConnectReporter): Int

    /** Called once the tunnel has carried real traffic, so the engine can remember what worked. */
    fun confirmWorking() {}

    /** Stops every process the engine started. Safe to call more than once. */
    fun stop()
}

class EngineException(message: String) : Exception(message)

/** Persian digits for UI text. */
fun fa(n: Number): String = n.toString().map { if (it.isDigit()) '۰' + (it - '0') else it }.joinToString("")
