package com.example.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Phase { IDLE, CONNECTING, CONNECTED, DISCONNECTING, ERROR }

/** One line of the "مراحل اتصال" sheet. */
data class ConnectEvent(val ok: Boolean, val title: String, val detail: String = "", val at: Long = System.currentTimeMillis())

data class TrafficStats(
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val rxPerSec: Long = 0,
    val txPerSec: Long = 0,
)

data class ConnectState(
    val phase: Phase = Phase.IDLE,
    val engine: EngineId = EngineId.XRAY,
    /** Stepper labels of the running engine. */
    val steps: List<String> = emptyList(),
    val step: Int = 0,
    /** Detail line in the progress card (config name, probe text). */
    val detail: String = "",
    /** Sub-line under the status title. */
    val status: String = "",
    val startedAt: Long = 0,
    val events: List<ConnectEvent> = emptyList(),
    val traffic: TrafficStats = TrafficStats(),
    val pingMs: Long = -1,
    val countryCode: String = "",
    val exitIp: String = "",
    /** What the engine is using (shown in the selection row). */
    val selection: String = "",
    val error: String = "",
)

/**
 * Process-wide connection state shared by the VPN service and the UI. The service is the only
 * writer while it runs; the UI only reads (and asks the service to act).
 */
object ConnectStore {
    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state

    fun update(change: (ConnectState) -> ConnectState) = _state.update(change)

    fun selectEngine(id: EngineId) = _state.update { if (it.phase == Phase.IDLE || it.phase == Phase.ERROR) it.copy(engine = id, error = "") else it }

    /** Reporter handed to an engine for one connect attempt. */
    fun reporter(): ConnectReporter = object : ConnectReporter {
        override fun step(index: Int) = _state.update { it.copy(step = index.coerceIn(0, (it.steps.size - 1).coerceAtLeast(0))) }
        override fun detail(text: String) = _state.update { it.copy(detail = text) }
        override fun status(text: String) = _state.update { it.copy(status = text) }
        override fun event(ok: Boolean, title: String, detail: String) =
            _state.update { it.copy(events = (it.events + ConnectEvent(ok, title, detail)).takeLast(MAX_EVENTS)) }
        override fun selection(text: String) = _state.update { it.copy(selection = text) }
    }

    const val MAX_EVENTS = 120
}

/** How an engine tells the UI where a connect stands. */
interface ConnectReporter {
    /** Index into the engine's [TunnelEngine.steps]. */
    fun step(index: Int)
    fun detail(text: String)
    fun status(text: String)
    /** Adds a line to the connect-stages sheet: a green check ([ok]) or a red cross. */
    fun event(ok: Boolean, title: String, detail: String = "")
    fun selection(text: String)
}
