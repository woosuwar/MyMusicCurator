package com.mymusiccurator.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object Format {
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR
    private val dateTime = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

    fun listenTime(ms: Long): String {
        val h = ms / HOUR
        val m = (ms % HOUR) / MIN
        return when {
            h > 0 -> "${h}시간 ${m}분"
            else -> "${m}분"
        }
    }

    fun interval(ms: Long?): String = when {
        ms == null -> "—"
        ms >= DAY -> String.format("%.1f일", ms.toDouble() / DAY)
        ms >= HOUR -> String.format("%.1f시간", ms.toDouble() / HOUR)
        else -> "${(ms / MIN).coerceAtLeast(1)}분"
    }

    fun ago(time: Long?, now: Long = System.currentTimeMillis()): String {
        if (time == null) return "—"
        val d = now - time
        return when {
            d < HOUR -> "${(d / MIN).coerceAtLeast(1)}분 전"
            d < DAY -> "${d / HOUR}시간 전"
            d < 30 * DAY -> "${d / DAY}일 전"
            else -> dateTime(time).substring(0, 10)
        }
    }

    fun dateTime(time: Long?): String =
        time?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(dateTime) } ?: "—"

    /** (마지막 - 처음) / (횟수 - 1) */
    fun averageInterval(count: Int, first: Long?, last: Long?): Long? =
        if (count >= 2 && first != null && last != null && last > first) (last - first) / (count - 1) else null
}
