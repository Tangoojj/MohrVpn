package com.example.engine

import android.content.Context
import com.example.tor.TorEngine
import com.example.warp.WarpEngine
import com.example.xray.XrayEngine

/** Full build: Xray, Tor and WARP. */
object Engines {
    val available: List<EngineId> = listOf(EngineId.XRAY, EngineId.TOR, EngineId.WARP)

    fun create(context: Context, id: EngineId): TunnelEngine = when (id) {
        EngineId.XRAY -> XrayEngine(context)
        EngineId.TOR -> TorEngine(context)
        EngineId.WARP -> WarpEngine(context)
    }
}
