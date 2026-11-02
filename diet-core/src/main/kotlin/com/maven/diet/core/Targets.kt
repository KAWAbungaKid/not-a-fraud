package com.maven.diet.core

import java.time.LocalDate
import kotlin.math.roundToInt

data class Macros(val kcal: Double, val protein: Double, val carbs: Double, val fat: Double) {
    operator fun plus(o: Macros) = Macros(kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat)
    operator fun times(k: Double) = Macros(kcal * k, protein * k, carbs * k, fat * k)
    companion object { val ZERO = Macros(0.0, 0.0, 0.0, 0.0) }
}

/** Today's targets and where every number came from (shown in the app so nothing is a black box). */
data class DayTargets(
    val date: LocalDate,
    val macros: Macros,
    val waterOz: Double,
    val training: Boolean,
    val maintenance: Double,
    /** "formula" until enough data, then "you" (adaptive) or a blend. */
    val maintenanceSource: String,
    val goalKcal: Double,
    val cycleKcal: Double,
    val activityKcal: Double,
    val lines: List<String>,
)

/**
 * Calorie and macro targets.
 * 1. Maintenance: a formula at first (Katch-McArdle with body fat, else Mifflin-St Jeor, × activity factor),
 *    replaced step by step by YOUR measured maintenance from weigh-ins + logged food ([Adaptive]).
 * 2. + goal (lb/week × 3500 / 7), + training/rest cycling, + eat-back when today was more active than usual.
 * 3. Protein and fat per lb of trend weight; carbs fill the rest.
 */
object Targets {
    fun kg(lb: Double) = lb * 0.45359237
    fun cm(inches: Double) = inches * 2.54

    fun bmr(p: Profile, weightLb: Double): Double {
        val bf = p.bodyFatPct
        return if (bf != null && bf in 3.0..60.0) {
            370 + 21.6 * kg(weightLb) * (1 - bf / 100)
        } else {
            10 * kg(weightLb) + 6.25 * cm(p.heightIn) - 5 * p.age + if (p.sex == Sex.MALE) 5 else -161
        }
    }

    fun formulaMaintenance(d: DietData, weightLb: Double) = bmr(d.profile, weightLb) * d.settings.activityFactor

    fun currentWeight(d: DietData, today: LocalDate): Double = Trend.latest(d, today) ?: d.profile.weightLb

    /** Training day: set by hand, else the workout app says you trained / are training, else yes if the week still needs workouts. */
    fun isTraining(d: DietData, today: LocalDate, workoutSaysLikely: Boolean?): Boolean {
        d.trainingOverride[today.toString()]?.let { return it }
        val act = d.activity.firstOrNull { it.date == today.toString() }
        if ((act?.liftMin ?: 0) > 0) return true
        return workoutSaysLikely ?: true
    }

    fun forDay(d: DietData, today: LocalDate, workoutSaysLikely: Boolean? = null): DayTargets {
        val s = d.settings
        val w = currentWeight(d, today)
        val formula = formulaMaintenance(d, w)
        val est = Adaptive.estimate(d, today)
        val (maint, source) = when {
            est == null -> formula to "formula"
            est.confidence >= 1.0 -> est.tdee to "you"
            else -> (formula * (1 - est.confidence) + est.tdee * est.confidence) to "blend"
        }
        val goal = d.profile.goal.lbPerWeek * 3500 / 7
        val training = isTraining(d, today, workoutSaysLikely)
        val cycle = if (training) s.cycleKcal.toDouble() else -s.cycleKcal.toDouble()
        val activity = extraActivity(d, today) * s.eatBackShare
        val kcal = (maint + goal + cycle + activity).coerceAtLeast(1500.0)
        val protein = s.proteinPerLb * w
        val fat = s.fatPerLb * w
        val carbs = ((kcal - protein * 4 - fat * 9) / 4).coerceAtLeast(50.0)
        val lines = buildList {
            add("Maintenance ${maint.roundToInt()} kcal (" + when (source) {
                "formula" -> "formula: BMR ${bmr(d.profile, w).roundToInt()} × ${s.activityFactor} — until 2 weeks of weigh-ins + logs"
                "you" -> "measured from your weigh-ins and food, last ${est!!.days} days"
                else -> "${(est!!.confidence * 100).roundToInt()}% measured (${est.tdee.roundToInt()}), rest formula (${formula.roundToInt()})"
            } + ")")
            if (goal != 0.0) add("Goal ${d.profile.goal.label}: ${signed(goal)} kcal")
            add(if (training) "Training day: ${signed(cycle)} kcal, as carbs" else "Rest day: ${signed(cycle)} kcal")
            if (activity > 0) add("More active than usual today: +${activity.roundToInt()} kcal")
            add("Protein ${s.proteinPerLb} g/lb, fat ${s.fatPerLb} g/lb of ${"%.1f".format(w)} lb trend weight; carbs fill the rest")
        }
        val water = s.waterOz + if (training) s.waterTrainingExtraOz else 0.0
        return DayTargets(today, Macros(kcal, protein, carbs, fat), water, training, maint, source, goal, cycle, activity, lines)
    }

    /** Active calories today above your usual day (14-day average of full past days). 0 when unknown or below. */
    fun extraActivity(d: DietData, today: LocalDate): Double {
        val todayAct = d.activity.firstOrNull { it.date == today.toString() }?.activeKcal ?: return 0.0
        val past = d.activity.filter {
            val dt = LocalDate.parse(it.date); dt.isBefore(today) && !dt.isBefore(today.minusDays(14)) && it.activeKcal != null
        }
        if (past.size < 4) return 0.0
        val avg = past.mapNotNull { it.activeKcal }.average()
        return (todayAct - avg).coerceIn(0.0, 800.0)
    }

    private fun signed(x: Double) = (if (x >= 0) "+" else "−") + kotlin.math.abs(x).roundToInt()
}

/** Weight trend: exponentially smoothed daily weigh-ins (α 0.1, like Hacker's Diet / MacroFactor) so water swings don't steer decisions. */
object Trend {
    const val ALPHA = 0.1

    /** (date, raw or null, trend) for every day from the first weigh-in to [until]. */
    fun series(d: DietData, until: LocalDate): List<Triple<LocalDate, Double?, Double>> {
        val byDay = d.weights.groupBy { it.date }.mapValues { (_, v) -> v.maxBy { it.at }.lb }
        val first = byDay.keys.minOrNull()?.let { LocalDate.parse(it) } ?: return emptyList()
        val out = ArrayList<Triple<LocalDate, Double?, Double>>()
        var t: Double? = null
        var day = first
        while (!day.isAfter(until)) {
            val raw = byDay[day.toString()]
            t = when {
                t == null -> raw
                raw == null -> t
                else -> t + ALPHA * (raw - t)
            }
            if (t != null) out += Triple(day, raw, t)
            day = day.plusDays(1)
        }
        return out
    }

    fun latest(d: DietData, today: LocalDate): Double? = series(d, today).lastOrNull()?.third

    /** lb per week over the last [days] days of trend. */
    fun ratePerWeek(d: DietData, today: LocalDate, days: Int = 14): Double? {
        val s = series(d, today)
        if (s.size < days / 2) return null
        val end = s.last()
        val start = s.lastOrNull { !it.first.isAfter(today.minusDays(days.toLong())) } ?: s.first()
        val span = java.time.temporal.ChronoUnit.DAYS.between(start.first, end.first)
        if (span < 7) return null
        return (end.third - start.third) / span * 7
    }
}

/**
 * Your real maintenance: average intake on complete days − energy stored or lost (trend change × 3500 kcal/lb).
 * Needs 14+ days spanned with 10+ weigh-ins and 10+ complete logged days in the last 28; confidence reaches 1 at 28 days.
 */
object Adaptive {
    data class Estimate(val tdee: Double, val days: Int, val intake: Double, val trendChange: Double, val confidence: Double)

    fun estimate(d: DietData, today: LocalDate, window: Int = 28): Estimate? {
        val from = today.minusDays(window.toLong())
        val yesterday = today.minusDays(1)
        val series = Trend.series(d, yesterday).filter { !it.first.isBefore(from) }
        val weighIns = series.count { it.second != null }
        if (series.size < 14 || weighIns < 10) return null
        val days = Diary.completeDays(d).filter { !it.isBefore(from) && !it.isAfter(yesterday) }
        if (days.size < 10) return null
        val intake = days.map { Diary.totals(d, it).kcal }.average()
        val change = series.last().third - series.first().third
        val span = java.time.temporal.ChronoUnit.DAYS.between(series.first().first, series.last().first).coerceAtLeast(1)
        val tdee = intake - change * 3500 / span
        val conf = ((span - 13) / 14.0).coerceIn(0.25, 1.0)
        return Estimate(tdee, span.toInt(), intake, change, conf)
    }
}
