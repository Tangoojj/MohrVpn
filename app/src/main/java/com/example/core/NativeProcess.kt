package com.example.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A bundled executable (shipped as lib*.so so Android extracts it to the native library dir)
 * run as a child process. Output lines go to [onLine]; [stop] terminates it.
 */
class NativeProcess(
    private val binary: File,
    private val args: List<String>,
    private val workDir: File,
    private val tag: String,
    private val env: Map<String, String> = emptyMap(),
    private val onLine: (String) -> Unit = { AppLog.i(tag, it) },
) {
    private var process: Process? = null
    /** The last process started, kept after [stop] so its exit code and output can be reported. */
    @Volatile private var last: Process? = null
    @Volatile var pid: Int? = null
        private set
    private val tailLines = ArrayDeque<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isAlive: Boolean get() = process?.let { p -> runCatching { p.exitValue(); false }.getOrDefault(true) } == true

    /** Exit code of the last run, or null while it runs (or if it never started). */
    fun exitCode(): Int? = last?.let { p -> runCatching { p.exitValue() }.getOrNull() }

    /** The last [TAIL_LINES] output lines, oldest first. */
    fun tail(): List<String> = synchronized(tailLines) { tailLines.toList() }

    private fun remember(line: String) = synchronized(tailLines) {
        tailLines.addLast(line.take(300))
        while (tailLines.size > TAIL_LINES) tailLines.removeFirst()
    }

    fun start() {
        check(binary.isFile) { "${binary.name} is missing for this device" }
        if (!binary.canExecute()) binary.setExecutable(true, false)
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args)
            .directory(workDir)
            .redirectErrorStream(true)
        pb.environment().putAll(env)
        synchronized(tailLines) { tailLines.clear() }
        val p = ChildProcesses.spawn(binary.name) { pb.start() }
        process = p
        last = p
        pid = pidOf(p)
        scope.launch {
            runCatching {
                p.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        remember(line)
                        onLine(line)
                    }
                }
            }
        }
    }

    /** Waits until every port accepts TCP, or the process dies, or [timeoutMs] passes. */
    suspend fun awaitPorts(ports: List<Int>, timeoutMs: Long): Boolean = withTimeoutOrNull(timeoutMs) {
        while (true) {
            if (!isAlive) return@withTimeoutOrNull false
            if (ports.all(::portOpen)) return@withTimeoutOrNull true
            delay(60)
        }
        @Suppress("UNREACHABLE_CODE") false
    } ?: false

    fun stop() {
        val p = process ?: return
        process = null
        runCatching { p.outputStream.close() }
        runCatching { p.destroy() }
        val exited = waitExit(p, 1500)
        val id = pid ?: pidOf(p)
        if (!exited) {
            id?.let { runCatching { android.os.Process.sendSignal(it, 9) } }
            waitExit(p, 1000)
        }
        id?.let { ChildProcesses.remove(it) }
    }

    /** Runs to completion and returns (exit code, output). */
    fun runToEnd(timeoutMs: Long): Pair<Int, String> {
        check(binary.isFile) { "${binary.name} is missing for this device" }
        if (!binary.canExecute()) binary.setExecutable(true, false)
        val pb = ProcessBuilder(listOf(binary.absolutePath) + args).directory(workDir).redirectErrorStream(true)
        pb.environment().putAll(env)
        val p = ChildProcesses.spawn(binary.name) { pb.start() }
        val childPid = pidOf(p)
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } } }
        reader.start()
        val done = waitExit(p, timeoutMs)
        if (!done) runCatching { p.destroy() }
        reader.join(500)
        childPid?.let { ChildProcesses.remove(it) }
        return (if (done) p.exitValue() else -1) to out.toString()
    }

    companion object {
        const val TAIL_LINES = 20

        fun portOpen(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) }
            true
        }.getOrDefault(false)

        /** Free localhost TCP ports, picked by the kernel. */
        fun freePorts(count: Int): List<Int> {
            val sockets = (0 until count).map { java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")) }
            val ports = sockets.map { it.localPort }
            sockets.forEach { runCatching { it.close() } }
            return ports
        }

        /** Process.waitFor(timeout) needs API 26; poll instead. */
        fun waitExit(p: Process, timeoutMs: Long): Boolean {
            val end = System.currentTimeMillis() + timeoutMs
            while (true) {
                if (runCatching { p.exitValue(); true }.getOrDefault(false)) return true
                if (System.currentTimeMillis() >= end) return false
                Thread.sleep(25)
            }
        }

        internal fun pidOf(p: Process): Int? = runCatching {
            val f = p.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            f.getInt(p)
        }.getOrNull() ?: runCatching { (Process::class.java.getMethod("pid").invoke(p) as Long).toInt() }.getOrNull()
    }
}
