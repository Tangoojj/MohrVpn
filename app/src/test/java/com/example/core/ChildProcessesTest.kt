package com.example.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChildProcessesTest {
    private val libDir = "/data/app/~~x/com.meh.vpn-1/lib/arm64"

    private fun fakeProc(root: File, pid: Int, argv0: String, uid: Int, ppid: Int = 1) {
        val d = File(root, pid.toString()).apply { mkdirs() }
        File(d, "cmdline").writeBytes((argv0 + "\u0000-c\u0000x\u0000").toByteArray())
        File(d, "status").writeText("Name:\tx\nPPid:\t$ppid\nUid:\t$uid\t$uid\t$uid\t$uid\n")
    }

    @Test fun killLeftovers_killsOnlyOurOwnCores() {
        val root = Files.createTempDirectory("proc").toFile()
        val pidFile = File(Files.createTempDirectory("files").toFile(), "child_pids")
        ChildProcesses.init(pidFile)
        fakeProc(root, 100, "$libDir/libxray.so", 10234)        // orphan xray: kill
        fakeProc(root, 101, "$libDir/libaether.so", 10234)      // orphan WARP core: kill
        fakeProc(root, 102, "$libDir/libtor.so", 10234)         // orphan tor: kill
        fakeProc(root, 103, "$libDir/liblyrebird.so", 10234, ppid = 102) // its transport: kill
        fakeProc(root, 200, "$libDir/libxray.so", 10999)        // other uid: keep
        fakeProc(root, 201, "/data/app/other/lib/arm64/libxray.so", 10234) // other app's dir: keep
        fakeProc(root, 202, "/system/bin/app_process64", 10234) // our app itself / anything else: keep
        fakeProc(root, 300, "$libDir/libxray.so", 10234)        // selfPid guard
        val killed = mutableListOf<Int>()
        val result = ChildProcesses.killLeftovers(libDir, 10234, selfPid = 300, procRoot = root, includeTracked = true) { killed += it }
        assertEquals(listOf(100, 101, 102, 103), killed.sorted())
        assertEquals(killed, result)
    }

    @Test fun killLeftovers_sparesLiveTrackedCoresAndTheirChildren_unlessAsked() {
        val root = Files.createTempDirectory("proc").toFile()
        val pidFile = File(Files.createTempDirectory("files").toFile(), "child_pids")
        ChildProcesses.init(pidFile)
        ChildProcesses.killLeftovers(libDir, 1, 1, root, includeTracked = true) {} // reset state
        ChildProcesses.add(500, "libtor.so")
        fakeProc(root, 500, "$libDir/libtor.so", 10234)
        fakeProc(root, 501, "$libDir/liblyrebird.so", 10234, ppid = 500)
        fakeProc(root, 502, "$libDir/libxray.so", 10234)
        val k1 = mutableListOf<Int>()
        ChildProcesses.killLeftovers(libDir, 10234, 1, root, includeTracked = false) { k1 += it }
        assertEquals(listOf(502), k1)
        assertTrue(pidFile.readText().contains("500 libtor.so"))
        val k2 = mutableListOf<Int>()
        ChildProcesses.killLeftovers(libDir, 10234, 1, root, includeTracked = true) { k2 += it }
        assertEquals(listOf(500, 501, 502), k2.sorted())
        assertTrue(ChildProcesses.tracked().isEmpty())
    }

    @Test fun pidFile_survivesAcrossProcesses_andIsSweptFromIt() {
        val root = Files.createTempDirectory("proc").toFile()
        val pidFile = File(Files.createTempDirectory("files").toFile(), "child_pids")
        // Written by an earlier (now dead) app process; that PID is not even listed under /proc scan order.
        pidFile.writeText("777 libaether.so\n")
        ChildProcesses.init(pidFile)
        ChildProcesses.killLeftovers(libDir, 1, 1, Files.createTempDirectory("empty").toFile(), includeTracked = true) {}
        pidFile.writeText("777 libaether.so\n")
        fakeProc(root, 777, "$libDir/libaether.so", 10234)
        val killed = mutableListOf<Int>()
        ChildProcesses.killLeftovers(libDir, 10234, 1, root, includeTracked = false) { killed += it }
        assertEquals(listOf(777), killed)
        assertFalse(pidFile.readText().contains("777"))
    }

    /** Real processes on the build host: a started core is recorded, and an orphan is really killed. */
    @Test fun realOrphanProcess_isRecordedAndKilled() {
        val dir = Files.createTempDirectory("lib").toFile()
        val fake = File(dir, "libxray.so")
        File("/bin/sleep").copyTo(fake)
        fake.setExecutable(true)
        val pidFile = File(dir, "child_pids")
        ChildProcesses.init(pidFile)
        ChildProcesses.killLeftovers(dir.absolutePath, -1, 1, Files.createTempDirectory("empty").toFile(), includeTracked = true) {}
        val p = NativeProcess(fake, listOf("30"), dir, "test")
        p.start()
        val pid = p.pid!!
        assertTrue(pidFile.readText().contains("$pid libxray.so"))
        val uid = File("/proc/self/status").readLines().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1].toInt()
        val killed = ChildProcesses.killLeftovers(dir.absolutePath, uid, ProcessHandle.current().pid().toInt(), includeTracked = true) { victim ->
            ProcessBuilder("kill", "-9", victim.toString()).start().waitFor()
        }
        assertEquals(listOf(pid), killed)
        Thread.sleep(300)
        assertFalse(p.isAlive)
        assertFalse(pidFile.readText().contains("$pid"))
    }

    @Test fun nativeProcess_keepsExitCodeAndLastLines() {
        val dir = Files.createTempDirectory("np").toFile()
        val p = NativeProcess(File("/bin/sh"), listOf("-c", "for i in \$(seq 1 30); do echo line\$i; done; exit 7"), dir, "test") {}
        p.start()
        val end = System.currentTimeMillis() + 5_000
        while (p.isAlive && System.currentTimeMillis() < end) Thread.sleep(20)
        Thread.sleep(200)
        assertEquals(7, p.exitCode())
        val tail = p.tail()
        assertEquals(NativeProcess.TAIL_LINES, tail.size)
        assertEquals("line11", tail.first())
        assertEquals("line30", tail.last())
    }
}
