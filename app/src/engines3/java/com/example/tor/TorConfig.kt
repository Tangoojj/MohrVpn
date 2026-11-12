package com.example.tor

/** torrc generation and bridge selection (pure, testable). */
object TorConfig {
    fun loadBridges(text: String): List<String> =
        text.lineSequence().map(String::trim).filter { it.startsWith("webtunnel ") && it.contains("url=") }.distinct().toList()

    /**
     * [count] bridges: those whose fingerprint is in [preferred] first (they worked on this
     * network before), the rest at random.
     */
    fun pickBridges(all: List<String>, count: Int, preferred: List<String> = emptyList(), seed: Long): List<String> {
        val first = all.filter { b -> preferred.any { fp -> b.contains(" $fp ") } }
        val rest = (all - first.toSet()).shuffled(kotlin.random.Random(seed))
        return (first + rest).take(count)
    }

    /** Stepper index (bridges, transport, directory, circuits, tunnel) for a bootstrap percentage. */
    fun stepFor(percent: Int): Int = when {
        percent < 2 -> 0
        percent < 20 -> 1
        percent < 80 -> 2
        percent < 100 -> 3
        else -> 4
    }

    fun torrc(dataDir: String, socksPort: Int, lyrebird: String, bridges: List<String>, ownerPid: Int?): String = buildString {
        appendLine("DataDirectory $dataDir")
        appendLine("SocksPort 127.0.0.1:$socksPort")
        appendLine("ClientOnly 1")
        appendLine("AvoidDiskWrites 1")
        appendLine("Log notice stdout")
        // Default for clients; bridge lines with IPv6 placeholder addresses are still used (tor manual).
        appendLine("ClientUseIPv6 0")
        appendLine("UseBridges 1")
        appendLine("ClientTransportPlugin webtunnel exec $lyrebird")
        appendLine("CircuitBuildTimeout 30")
        appendLine("LearnCircuitBuildTimeout 0")
        appendLine("DormantCanceledByStartup 1")
        // Tor exits by itself if the app process dies.
        ownerPid?.let { appendLine("__OwningControllerProcess $it") }
        bridges.forEach { appendLine("Bridge $it") }
    }
}
