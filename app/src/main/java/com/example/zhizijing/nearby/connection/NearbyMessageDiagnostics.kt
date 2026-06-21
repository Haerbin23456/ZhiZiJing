package com.example.zhizijing.nearby.connection

import com.example.zhizijing.nearby.message.NearbyMessage
import java.util.Locale

object NearbyMessageDiagnostics {
    private const val MAX_REASONABLE_DELAY_MS = 60_000L

    fun estimateDelayMs(
        message: NearbyMessage,
        receivedAtMs: Long,
    ): Int? {
        message.networkDelayMs
            ?.takeIf { it > 0 }
            ?.let { return it.coerceAtMost(MAX_REASONABLE_DELAY_MS.toInt()) }

        val rawDelayMs = receivedAtMs - message.timestampMs
        if (rawDelayMs < 0L || rawDelayMs > MAX_REASONABLE_DELAY_MS) return null
        return rawDelayMs.toInt()
    }

    fun roundTripDelayMs(
        sentAtMs: Long,
        receivedAtMs: Long,
    ): Int? {
        val rawDelayMs = receivedAtMs - sentAtMs
        if (rawDelayMs < 0L || rawDelayMs > MAX_REASONABLE_DELAY_MS) return null
        return rawDelayMs.toInt()
    }

    fun formatAge(nowMs: Long, timestampMs: Long): String {
        if (timestampMs <= 0L) return "暂无"
        val ageMs = (nowMs - timestampMs).coerceAtLeast(0L)
        return when {
            ageMs < 1_000L -> "${ageMs}ms 前"
            ageMs < 60_000L -> "${"%.1f".format(Locale.US, ageMs / 1000f)}s 前"
            else -> "${ageMs / 60_000L}min 前"
        }
    }
}
