package com.example.xray

import android.content.Context

/** Per-network memory for the Xray auto engine: DPI verdict, config ranking, ECH keys. */
class AutoStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("xray_auto", Context.MODE_PRIVATE)

    fun dpi(networkKey: String): DpiType? {
        val raw = prefs.getString("dpi:$networkKey", null) ?: return null
        val type = runCatching { DpiType.valueOf(raw.substringBefore('|')) }.getOrNull() ?: return null
        val at = raw.substringAfter('|', "0").toLongOrNull() ?: 0L
        return type.takeIf { System.currentTimeMillis() - at in 0..DPI_TTL_MS }
    }

    fun saveDpi(networkKey: String, type: DpiType) =
        prefs.edit().putString("dpi:$networkKey", "${type.name}|${System.currentTimeMillis()}").apply()

    fun ranking(networkKey: String): List<RankedEntry> = Ranking.decode(prefs.getString("rank:$networkKey", null))

    fun saveRanking(networkKey: String, entries: List<RankedEntry>) =
        prefs.edit().putString("rank:$networkKey", Ranking.encode(entries)).apply()

    @Synchronized
    fun updateRanking(networkKey: String, change: (List<RankedEntry>) -> List<RankedEntry>) =
        saveRanking(networkKey, change(ranking(networkKey)))

    fun cleanIps(networkKey: String): List<String> =
        prefs.getString("edges:$networkKey", null).orEmpty().split(',').filter { it.isNotBlank() }

    fun saveCleanIps(networkKey: String, ips: List<String>) =
        prefs.edit().putString("edges:$networkKey", ips.joinToString(",")).apply()

    /** ECH key cache backed by preferences. */
    val echCache = object : EchKeys.Cache {
        override fun get(domain: String): String? {
            val raw = prefs.getString("ech:$domain", null) ?: return null
            val at = raw.substringAfter('|', "0").toLongOrNull() ?: 0L
            return raw.substringBefore('|').takeIf { System.currentTimeMillis() - at in 0..EchKeys.TTL_MS }
        }

        override fun put(domain: String, key: String) {
            prefs.edit().putString("ech:$domain", "$key|${System.currentTimeMillis()}").apply()
        }
    }

    companion object {
        const val DPI_TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
