package com.example.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** In-memory log shown in the app's log sheet (and mirrored to logcat). */
object AppLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    @Synchronized
    fun i(tag: String, message: String) {
        runCatching { Log.i("MohrVpn", "[$tag] $message") }
        lines.addLast("${time.format(Date())}  [$tag] $message")
        while (lines.size > MAX) lines.removeFirst()
        _count.value = _count.value + 1
    }

    fun w(tag: String, message: String, error: Throwable? = null) =
        i(tag, if (error == null) message else "$message: ${error.javaClass.simpleName} ${error.message.orEmpty()}")

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        _count.value = 0
    }
}
