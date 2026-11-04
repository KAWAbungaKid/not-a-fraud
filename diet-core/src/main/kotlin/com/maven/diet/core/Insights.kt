package com.maven.diet.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

/**
 * The case study, v1: one row per day of everything logged, and honest two-group comparisons
 * ("days fasted 16 h+ vs shorter: clarity 3.9 vs 3.1, n = 12 vs 9"). Comparisons only show with 5+ days in each
 * group, and say how sure they are. Correlation, not proof — real answers come from experiments (later version).
 */
object Insights {
    data class Row(
        val date: LocalDate,
        val kcal: Double, val protein: Double, val carbs: Double,
        val overnightFastH: Double?, val firstMealHour: Double?,
        val waterOz: Double,
        val trained: Boolean, val activeKcal: Double?,
        val clarity: Int?, val energy: Int?,
        val sleepH: Double? = null,
    )

    fun rows(d: DietData, zone: ZoneId, until: LocalDate, days: Int = 90): List<Row> {
        val from = until.minusDays(days.toLong())
        val dates = (d.log.map { it.date } + d.checkins.map { it.date }).toSet().map { LocalDate.parse(it) }
            .filter { !it.isBefore(from) && !it.isAfter(until) }.sorted()
        return dates.map { dt ->
            val m = Diary.totals(d, dt)
            val c = d.checkins.filter { it.date == dt.toString() }.maxByOrNull { it.at }
            val a = d.activity.firstOrNull { it.date == dt.toString() }
            val first = Diary.entries(d, dt).minOfOrNull { it.at }?.let {
                val z = Instant.ofEpochMilli(it).atZone(zone); z.hour + z.minute / 60.0
            }
            Row(dt, m.kcal, m.protein, m.carbs, Fasting.overnightHours(d, dt), first, Diary.waterOz(d, dt),
                (a?.liftMin ?: 0) > 0, a?.activeKcal, c?.clarity, c?.energy, a?.sleepH)
        }
    }

    data class Comparison(
        val question: String, val groupA: String, val groupB: String,
        val metric: String, val meanA: Double, val meanB: Double, val nA: Int, val nB: Int,
        /** Rough: |difference| / standard error. ≥ 2 = probably real, 1–2 = maybe, < 1 = noise. */
        val strength: Double,
    ) {
        val verdict get() = when { strength >= 2 -> "likely real"; strength >= 1 -> "maybe — keep collecting"; else -> "no clear difference yet" }
    }

    private fun compare(q: String, a: String, b: String, metric: String, xs: List<Double>, ys: List<Double>): Comparison? {
        if (xs.size < 5 || ys.size < 5) return null
        fun mean(v: List<Double>) = v.average()
        fun varOf(v: List<Double>): Double { val m = mean(v); return v.sumOf { (it - m) * (it - m) } / (v.size - 1) }
        val se = sqrt(varOf(xs) / xs.size + varOf(ys) / ys.size).coerceAtLeast(0.05)
        return Comparison(q, a, b, metric, mean(xs), mean(ys), xs.size, ys.size, kotlin.math.abs(mean(xs) - mean(ys)) / se)
    }

    /** The comparisons Maven asked about: fasting, eating, water, training → brain fog and energy. */
    fun comparisons(d: DietData, zone: ZoneId, until: LocalDate): List<Comparison> {
        val r = rows(d, zone, until).filter { it.clarity != null }
        val out = ArrayList<Comparison>()
        fun split(q: String, a: String, b: String, pick: (Row) -> Boolean?) {
            val ga = r.filter { pick(it) == true }; val gb = r.filter { pick(it) == false }
            compare(q, a, b, "Clarity", ga.map { it.clarity!!.toDouble() }, gb.map { it.clarity!!.toDouble() })?.let { out += it }
            compare(q, a, b, "Energy", ga.mapNotNull { it.energy?.toDouble() }, gb.mapNotNull { it.energy?.toDouble() })?.let { out += it }
        }
        split("Fasting", "fasted 16 h+", "shorter fast") { it.overnightFastH?.let { h -> h >= 16 } }
        split("First meal", "first meal before noon", "noon or later") { it.firstMealHour?.let { h -> h < 12 } }
        val pMed = r.map { it.protein }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        split("Protein", "higher-protein days", "lower-protein days") { if (it.protein > 0) it.protein >= pMed else null }
        split("Water", "80+ oz water", "under 80 oz") { it.waterOz >= 80 }
        split("Training", "lifted", "didn't lift") { it.trained }
        split("Sleep", "slept 7 h+", "under 7 h") { it.sleepH?.let { h -> h >= 7 } }
        return out.sortedByDescending { it.strength }
    }
}
