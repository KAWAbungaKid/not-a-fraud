package com.maven.notafraud.core.plan

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Time {
    const val MIN = 60_000L
    const val HOUR = 3_600_000L
    const val DAY = 86_400_000L

    fun zone(id: String): ZoneId = runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.systemDefault())

    fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    fun at(date: LocalDate, minuteOfDay: Int, zone: ZoneId): Long =
        date.atStartOfDay(zone).plusMinutes(minuteOfDay.toLong()).toInstant().toEpochMilli()

    fun minuteOfDay(ms: Long, zone: ZoneId): Int {
        val t = Instant.ofEpochMilli(ms).atZone(zone)
        return t.hour * 60 + t.minute
    }

    private val clock = DateTimeFormatter.ofPattern("h:mm", Locale.US)
    private val clockAmPm = DateTimeFormatter.ofPattern("h:mma", Locale.US)

    /** "3:40" — compact, for notifications. */
    fun clock(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(clock)

    /** "3:40pm" */
    fun clockAmPm(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(clockAmPm).lowercase(Locale.US)

    fun roundUp(ms: Long, stepMin: Int): Long {
        val step = stepMin * MIN
        return ((ms + step - 1) / step) * step
    }

    /** 1320 → "10:00 pm". */
    fun clockText(minuteOfDay: Int): String {
        val h = (minuteOfDay / 60) % 24
        return "%d:%02d %s".format((h + 11) % 12 + 1, minuteOfDay % 60, if (h < 12) "am" else "pm")
    }

    fun durationText(min: Int): String = when {
        min < 60 -> "${min}m"
        min % 60 == 0 -> "${min / 60}h"
        else -> "${min / 60}h ${min % 60}m"
    }
}
