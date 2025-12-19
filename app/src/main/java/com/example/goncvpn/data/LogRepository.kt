package com.example.goncp2pvpn.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogRepository {
    private val _logs = MutableSharedFlow<String>(replay = 100, extraBufferCapacity = 500)
    val logs: SharedFlow<String> = _logs.asSharedFlow()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val entry = "[$timestamp] $tag: $message"
        _logs.tryEmit(entry)
        // Also print to Logcat for double visibility
        android.util.Log.e(tag, message)
    }
}
