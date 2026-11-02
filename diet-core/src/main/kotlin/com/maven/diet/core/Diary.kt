package com.maven.diet.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What was eaten / drunk on a day. */
object Diary {
    fun entries(d: DietData, date: LocalDate) = d.log.filter { it.date == date.toString() }

    fun macrosOf(d: DietData, e: LogEntry): Macros {
        val f = d.food(e.foodId) ?: return Macros.ZERO
        return Macros(f.kcal, f.protein, f.carbs, f.fat) * e.servings
    }

    fun totals(d: DietData, date: LocalDate): Macros = entries(d, date).fold(Macros.ZERO) { acc, e -> acc + macrosOf(d, e) }

    fun slotTotals(d: DietData, date: LocalDate, slot: String): Macros =
        entries(d, date).filter { it.slot == slot }.fold(Macros.ZERO) { acc, e -> acc + macrosOf(d, e) }

    /** Nutrient totals and how many of the day's calories came from foods that list that nutrient (coverage 0–1). */
    fun nutrients(d: DietData, date: LocalDate): Map<Nutrient, Pair<Double, Double>> {
        val es = entries(d, date)
        val kcal = es.sumOf { macrosOf(d, it).kcal }.coerceAtLeast(1.0)
        return Nutrient.entries.associateWith { n ->
            var sum = 0.0; var covered = 0.0
            for (e in es) {
                val f = d.food(e.foodId) ?: continue
                val v = f.nutrients[n] ?: continue
                sum += v * e.servings
                covered += f.kcal * e.servings
            }
            sum to (covered / kcal).coerceIn(0.0, 1.0)
        }
    }

    fun waterOz(d: DietData, date: LocalDate) = d.water.filter { it.date == date.toString() }.sumOf { it.oz }

    /** Complete = marked by hand, or every slot that has a deadline has food in it. */
    fun isComplete(d: DietData, date: LocalDate): Boolean {
        if (date.toString() in d.completeDays) return true
        val needed = d.settings.slots.filter { it.by != null }.map { it.name }
        if (needed.isEmpty()) return entries(d, date).isNotEmpty()
        val have = entries(d, date).map { it.slot }.toSet()
        return needed.all { it in have }
    }

    fun completeDays(d: DietData): List<LocalDate> =
        d.log.map { LocalDate.parse(it.date) }.distinct().filter { isComplete(d, it) }.sorted()

    /** Recently used foods first (for the add-food list). */
    fun recentFoods(d: DietData, limit: Int = 12): List<Food> =
        d.log.sortedByDescending { it.at }.map { it.foodId }.distinct().mapNotNull { d.food(it) }.filter { !it.archived }.take(limit)
}

/** Intermittent fasting: the eating window, and how long since you last ate. */
object Fasting {
    data class Status(
        val enabled: Boolean,
        val inWindow: Boolean,
        val windowStart: LocalTime,
        val windowEnd: LocalTime,
        /** Hours since the last logged food (null = nothing logged in 48 h). */
        val hoursSinceFood: Double?,
        /** Minutes until the window opens (if before it) or closes (if inside). */
        val minutesToChange: Long,
        /** Today's first and last food times. */
        val firstMealAt: Long?,
        val lastMealAt: Long?,
        /** Food logged today outside the window. */
        val outsideWindow: Int,
    )

    fun time(s: String): LocalTime = runCatching { LocalTime.parse(s) }.getOrDefault(LocalTime.NOON)

    fun status(d: DietData, now: Long, zone: ZoneId): Status {
        val s = d.settings
        val dt = Instant.ofEpochMilli(now).atZone(zone)
        val start = time(s.windowStart); val end = time(s.windowEnd)
        val t = dt.toLocalTime()
        val inWin = !s.fasting || (if (start <= end) t >= start && t < end else t >= start || t < end)
        val toChange = if (!s.fasting) 0L else {
            val target = if (inWin) end else start
            var m = Duration.between(t, target).toMinutes()
            if (m < 0) m += 24 * 60
            m
        }
        val last = d.log.filter { it.at <= now && it.at > now - 48 * 3_600_000L }.maxOfOrNull { it.at }
        val today = d.log.filter { it.date == dt.toLocalDate().toString() }
        val outside = if (!s.fasting) 0 else today.count { e ->
            val et = Instant.ofEpochMilli(e.at).atZone(zone).toLocalTime()
            !(if (start <= end) et >= start && et < end else et >= start || et < end)
        }
        return Status(
            s.fasting, inWin, start, end,
            last?.let { (now - it) / 3_600_000.0 }, toChange,
            today.minOfOrNull { it.at }, today.maxOfOrNull { it.at }, outside,
        )
    }

    /** Overnight fast that ended on [date]: last food the day before → first food that day (hours). */
    fun overnightHours(d: DietData, date: LocalDate): Double? {
        val first = d.log.filter { it.date == date.toString() }.minOfOrNull { it.at } ?: return null
        val prev = d.log.filter { it.at < first && it.at > first - 48 * 3_600_000L }.maxOfOrNull { it.at } ?: return null
        return (first - prev) / 3_600_000.0
    }
}

object Num {
    fun fmt(v: Double?): String = when {
        v == null -> ""
        kotlin.math.abs(v - Math.round(v)) < 1e-6 -> Math.round(v).toString()
        kotlin.math.abs(v * 10 - Math.round(v * 10)) < 1e-6 -> "%.1f".format(v)
        else -> "%.2f".format(v)
    }
}
