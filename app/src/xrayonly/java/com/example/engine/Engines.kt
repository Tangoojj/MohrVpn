package com.example.engine

import android.content.Context
import com.example.xray.XrayEngine

/** Xray-only build (ENGINES_XRAY_ONLY): Tor/WARP code and native cores are not packaged. */
object Engines {
    val available: List<EngineId> = listOf(EngineId.XRAY)

    fun create(context: Context, id: EngineId): TunnelEngine = XrayEngine(context)
}
