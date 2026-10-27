package com.example.remoteandroid.util

import java.net.Inet4Address
import java.net.NetworkInterface

object Network {
    private const val CACHE_TTL_MS = 30_000L

    private var cachedIps: List<String> = emptyList()
    private var cachedAt = 0L

    @Synchronized
    fun localIPv4s(): List<String> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_TTL_MS && cachedIps.isNotEmpty()) return cachedIps
        val result = enumerate()
        cachedIps = result
        cachedAt = now
        return result
    }

    private fun enumerate(): List<String> {
        val out = mutableListOf<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                        addr.hostAddress?.let { out.add(it) }
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) out.add("127.0.0.1")
        return out
    }
}
