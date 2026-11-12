package com.example.core

import java.io.File

/**
 * Bookkeeping for the engine cores we exec (libxray, libaether, libtor and the transports they
 * start). Each child PID is written to a file, so cores orphaned by a crash, a swipe-away or a
 * killed process can be found and killed on the next start and at every teardown.
 *
 * Only processes that run under our own uid and whose executable is one of our bundled cores in
 * our native library dir are ever killed.
 */
object ChildProcesses {
    /** Executables we start (directly or through tor) from the native library dir. */
    val CORES = setOf("libxray.so", "libaether.so", "libtor.so", "liblyrebird.so")

    @Volatile private var file: File? = null
    private val live = LinkedHashMap<Int, String>()

    /** Points the registry at [pidFile] (call once, e.g. from the VPN service). */
    fun init(pidFile: File) {
        file = pidFile
    }

    /**
     * Starts a process and records it atomically with respect to [killLeftovers], so a sweep never
     * mistakes a core that is just starting in this process for an orphan.
     */
    @Synchronized
    fun spawn(name: String, start: () -> Process): Process {
        val p = start()
        NativeProcess.pidOf(p)?.let { live[it] = name; persist() }
        return p
    }

    @Synchronized
    fun add(pid: Int, name: String) {
        live[pid] = name
        persist()
    }

    @Synchronized
    fun remove(pid: Int) {
        if (live.remove(pid) != null) persist()
    }

    @Synchronized
    fun tracked(): Map<Int, String> = LinkedHashMap(live)

    private fun persist() {
        val f = file ?: return
        runCatching {
            // Keep earlier sessions' PIDs that were never cleaned up until a sweep handles them.
            val old = readFile(f).filterKeys { it !in live }
            f.writeText((old + live).entries.joinToString("\n") { "${it.key} ${it.value}" })
        }
    }

    internal fun readFile(f: File): Map<Int, String> = runCatching {
        f.readLines().mapNotNull { l ->
            val parts = l.trim().split(' ', limit = 2)
            parts.getOrNull(0)?.toIntOrNull()?.let { it to parts.getOrElse(1) { "" } }
        }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * Kills every leftover core: the PIDs in the file plus any process in [procRoot] owned by
     * [uid] whose executable is one of [CORES] inside [libDir]. Skips [selfPid] and PIDs started
     * by this process that are still tracked unless [includeTracked]. Returns the killed PIDs.
     */
    @Synchronized
    fun killLeftovers(
        libDir: String,
        uid: Int,
        selfPid: Int,
        procRoot: File = File("/proc"),
        includeTracked: Boolean = false,
        kill: (Int) -> Unit = { runCatching { android.os.Process.sendSignal(it, 9) } },
    ): List<Int> {
        val candidates = LinkedHashSet<Int>()
        file?.let { candidates += readFile(it).keys }
        procRoot.listFiles()?.forEach { d -> d.name.toIntOrNull()?.let { candidates += it } }
        val killed = ArrayList<Int>()
        for (pid in candidates) {
            if (pid == selfPid || pid <= 1) continue
            if (!includeTracked && pid in live) continue
            val dir = File(procRoot, pid.toString())
            // A transport started by one of our live cores (lyrebird under tor) is not an orphan.
            if (!includeTracked && parentOf(dir) in live) continue
            if (isOurCore(dir, libDir, uid)) {
                kill(pid)
                killed += pid
            }
        }
        if (includeTracked) live.clear() else live.keys.retainAll { it !in killed }
        // Whatever is still tracked is alive and ours; everything else is gone.
        file?.let { f -> runCatching { f.writeText(live.entries.joinToString("\n") { "${it.key} ${it.value}" }) } }
        if (killed.isNotEmpty()) AppLog.i("vpn", "killed ${killed.size} leftover core process(es): $killed")
        return killed
    }

    internal fun parentOf(procDir: File): Int? = runCatching {
        File(procDir, "status").readLines().firstOrNull { it.startsWith("PPid:") }?.substringAfter(':')?.trim()?.toIntOrNull()
    }.getOrNull()

    /** True when /proc/<pid> is a process of [uid] executing one of our cores from [libDir]. */
    internal fun isOurCore(procDir: File, libDir: String, uid: Int): Boolean {
        val cmd = runCatching { File(procDir, "cmdline").readBytes() }.getOrNull() ?: return false
        val argv0 = String(cmd).substringBefore('\u0000')
        val exe = File(argv0)
        if (exe.name !in CORES) return false
        if (exe.parent?.trimEnd('/') != libDir.trimEnd('/')) return false
        val status = runCatching { File(procDir, "status").readLines() }.getOrNull() ?: return false
        val realUid = status.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
        return realUid == uid
    }
}
