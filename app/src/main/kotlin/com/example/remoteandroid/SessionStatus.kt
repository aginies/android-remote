package com.example.remoteandroid

import com.example.remoteandroid.settings.StreamSettings
import java.security.SecureRandom
import java.time.LocalTime
import java.time.format.DateTimeFormatter

const val ACCESS_TOKEN_KEY = "access_token"

/**
 * In-memory session state shared between the screen-capture service, the web
 * server and the UI, plus a small rolling log so the app's main window can show
 * what is happening (who is connected, from where, current settings, ...).
 */
object SessionStatus {
    private const val MAX_LOG = 200
    private const val TOKEN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    @Volatile
    var running = false

    @Volatile
    var settings: StreamSettings = StreamSettings()

    @Volatile
    var url: String = ""

    @Volatile
    var token: String = ""

    private val lock = Any()
    private val logLines = mutableListOf<String>()
    private val connectionCounts = LinkedHashMap<String, Int>()

    fun log(message: String) {
        synchronized(lock) { appendLocked(message) }
    }

    /** Generate a fresh per-session access token (no ambiguous characters). */
    fun newToken(length: Int = 8): String {
        val rnd = SecureRandom()
        val sb = StringBuilder(length)
        repeat(length) { sb.append(TOKEN_ALPHABET[rnd.nextInt(TOKEN_ALPHABET.length)]) }
        return sb.toString()
    }

    fun connect(ip: String) {
        synchronized(lock) {
            val count = connectionCounts[ip] ?: 0
            connectionCounts[ip] = count + 1
            if (count == 0) appendLocked("viewer connected: $ip")
        }
    }

    fun disconnect(ip: String) {
        synchronized(lock) {
            val count = connectionCounts[ip] ?: return
            if (count <= 1) {
                connectionCounts.remove(ip)
                appendLocked("viewer disconnected: $ip")
            } else {
                connectionCounts[ip] = count - 1
            }
        }
    }

    fun clients(): List<String> = synchronized(lock) { connectionCounts.keys.toList() }

    fun recent(max: Int): List<String> = synchronized(lock) { logLines.takeLast(max) }

    private fun appendLocked(message: String) {
        val stamp = LocalTime.now().format(timeFormatter)
        logLines.add("[$stamp] $message")
        while (logLines.size > MAX_LOG) logLines.removeAt(0)
    }
}
