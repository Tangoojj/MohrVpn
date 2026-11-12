package com.example.xray

import com.example.core.AppLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a config list ([Source.PATTERNIHA] by default, or [Source.BARRY]).
 *
 * Patterniha mirrors in order: GitHub raw → jsDelivr → GitHub contents API; barry-far: GitHub raw
 * → jsDelivr. The last good copy is kept on disk with its ETag; a copy younger than [maxAgeMs] is
 * used without touching the network, and when every mirror fails the stale copy is used.
 */
class ListFetcher(
    private val dir: File,
    private val maxAgeMs: Long = MAX_AGE_MS,
    private val source: Source = Source.PATTERNIHA,
) {
    data class Result(val text: String, val source: String, val fromCache: Boolean)

    /** One list: its cache file name, mirrors in order, and the check that a download is usable. */
    class Source(val file: String, val mirrors: List<String>, val usable: (String) -> Boolean) {
        companion object {
            val PATTERNIHA = Source("patterniha", listOf(RAW_URL, JSDELIVR_URL, API_URL)) { AutoList.parse(it).isNotEmpty() }
            val BARRY = Source("barry", listOf(BARRY_RAW_URL, BARRY_JSDELIVR_URL)) { BarryList.parse(it).isNotEmpty() }
        }
    }

    private val body get() = File(dir, "${source.file}.txt")
    private val meta get() = File(dir, "${source.file}.meta")

    fun load(force: Boolean = false): Result? {
        dir.mkdirs()
        val cached = body.takeIf { it.isFile && it.length() > 0 }?.readText()
        val (etag, fetchedAt) = readMeta()
        if (!force && cached != null && System.currentTimeMillis() - fetchedAt in 0..maxAgeMs) {
            return Result(cached, "cache", true)
        }
        for (url in source.mirrors) {
            val r = runCatching { fetch(url, if (cached != null) etag else "") }
                .onFailure { AppLog.w("list", "mirror failed ${host(url)}", it) }
                .getOrNull() ?: continue
            if (r.notModified && cached != null) {
                writeMeta(etag, System.currentTimeMillis())
                return Result(cached, host(url) + " (304)", true)
            }
            val text = r.text ?: continue
            if (!runCatching { source.usable(text) }.getOrDefault(false)) {
                AppLog.i("list", "mirror ${host(url)} returned no usable links")
                continue
            }
            body.writeText(text)
            writeMeta(r.etag, System.currentTimeMillis())
            return Result(text, host(url), false)
        }
        return cached?.let { Result(it, "cache (stale)", true) }
    }

    private class Fetched(val text: String?, val etag: String, val notModified: Boolean)

    private fun fetch(url: String, etag: String): Fetched {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 8_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "MohrVpn/2")
            if (url.startsWith(API_URL)) c.setRequestProperty("Accept", "application/vnd.github.raw")
            if (etag.isNotBlank()) c.setRequestProperty("If-None-Match", etag)
            return when (val code = c.responseCode) {
                304 -> Fetched(null, etag, true)
                200 -> Fetched(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }, c.getHeaderField("ETag").orEmpty(), false)
                else -> throw IllegalStateException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun readMeta(): Pair<String, Long> = runCatching {
        val lines = meta.readLines()
        lines.getOrElse(0) { "" } to (lines.getOrNull(1)?.toLongOrNull() ?: 0L)
    }.getOrDefault("" to 0L)

    private fun writeMeta(etag: String, at: Long) = runCatching { meta.writeText("$etag\n$at\n") }

    private fun host(url: String) = runCatching { URL(url).host }.getOrDefault(url)

    companion object {
        const val RAW_URL = "https://raw.githubusercontent.com/patterniha/Free-Configs/main/configs.txt"
        const val JSDELIVR_URL = "https://cdn.jsdelivr.net/gh/patterniha/Free-Configs@main/configs.txt"
        const val API_URL = "https://api.github.com/repos/patterniha/Free-Configs/contents/configs.txt"
        val MIRRORS = listOf(RAW_URL, JSDELIVR_URL, API_URL)
        const val BARRY_RAW_URL = "https://raw.githubusercontent.com/barry-far/V2ray-Config/main/All_Configs_Sub.txt"
        const val BARRY_JSDELIVR_URL = "https://cdn.jsdelivr.net/gh/barry-far/V2ray-Config@main/All_Configs_Sub.txt"
        val BARRY_MIRRORS = listOf(BARRY_RAW_URL, BARRY_JSDELIVR_URL)
        const val MAX_AGE_MS = 24L * 60 * 60 * 1000
    }
}
