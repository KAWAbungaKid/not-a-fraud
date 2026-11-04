package com.maven.diet.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The Monday review (§ 5 step 3): last week in one card. Trend vs goal, your maintenance estimate, how
 * consistent the logging was, and one suggested change, with the reason. Never changes anything by itself.
 */
object WeeklyReview {
    data class Review(
        val weekStart: LocalDate,
        val daysLogged: Int,
        val completeDays: Int,
        val weighIns: Int,
        val avgKcal: Double?,
        val avgTargetKcal: Double?,
        val avgProtein: Double?,
        val proteinTarget: Double?,
        val trendStart: Double?,
        val trendEnd: Double?,
        val ratePerWeek: Double?,
        val goalRate: Double,
        val maintenance: Double?,
        val maintenanceFromYou: Boolean,
        val avgSleep: Double?,
        val avgClarity: Double?,
        val avgEnergy: Double?,
        /** One sentence: what to change, or "keep going". */
        val suggestion: String,
    )

    fun lastWeekStart(today: LocalDate): LocalDate = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusDays(7)

    fun build(d: DietData, weekStart: LocalDate, today: LocalDate = weekStart.plusDays(7)): Review {
        val days = (0..6).map { weekStart.plusDays(it.toLong()) }
        val logged = days.filter { Diary.entries(d, it).isNotEmpty() }
        val complete = days.filter { Diary.isComplete(d, it) }
        val kcal = complete.map { Diary.totals(d, it).kcal }.takeIf { it.isNotEmpty() }?.average()
        val targets = complete.map { Targets.forDay(d, it) }
        val target = targets.map { it.macros.kcal }.takeIf { it.isNotEmpty() }?.average()
        val protein = complete.map { Diary.totals(d, it).protein }.takeIf { it.isNotEmpty() }?.average()
        val proteinT = targets.map { it.macros.protein }.takeIf { it.isNotEmpty() }?.average()
        val series = Trend.series(d, days.last())
        val tStart = series.lastOrNull { !it.first.isAfter(weekStart) }?.third ?: series.firstOrNull { !it.first.isBefore(weekStart) }?.third
        val tEnd = series.lastOrNull()?.third
        val rate = Trend.ratePerWeek(d, days.last())
        val est = Adaptive.estimate(d, today)
        val weighIns = d.weights.map { it.date }.toSet().count { LocalDate.parse(it) in days }
        val acts = days.mapNotNull { day -> d.activity.firstOrNull { it.date == day.toString() } }
        val checks = days.mapNotNull { day -> d.checkins.filter { it.date == day.toString() }.maxByOrNull { it.at } }
        val goal = d.profile.goal.lbPerWeek
        val suggestion = when {
            complete.size < 4 || weighIns < 4 -> "Log ${if (complete.size < 4) "every meal" else "your weight"} on more days: with ${complete.size} complete days and $weighIns weigh-ins, nothing can be judged yet."
            rate == null -> "Keep weighing in daily; the trend needs a couple of weeks before it says anything."
            abs(rate - goal) <= 0.25 -> "On track (${signed(rate)} lb/week vs ${signed(goal)} goal). Keep everything the same."
            rate < goal -> "Below your goal (${signed(rate)} lb/week vs ${signed(goal)}): eat about ${((goal - rate) * 500).roundToInt()} kcal more a day, mostly as carbs around training."
            else -> "Above your goal (${signed(rate)} lb/week vs ${signed(goal)}): eat about ${((rate - goal) * 500).roundToInt()} kcal less a day, from fat or snacks first."
        }
        return Review(
            weekStart, logged.size, complete.size, weighIns, kcal, target, protein, proteinT, tStart, tEnd, rate, goal,
            est?.tdee, est != null && est.confidence >= 0.5,
            acts.mapNotNull { it.sleepH }.takeIf { it.isNotEmpty() }?.average(),
            checks.map { it.clarity.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
            checks.map { it.energy.toDouble() }.takeIf { it.isNotEmpty() }?.average(),
            suggestion,
        )
    }

    private fun signed(x: Double) = (if (x >= 0) "+" else "−") + "%.2f".format(abs(x))
}

/**
 * Self-experiments (§ 5 step 5). Days alternate between the two conditions; what's compared is the day's
 * check-in (clarity, energy) and an optional "how did the workout feel" rating. Only days marked as followed count.
 */
object Experiments {
    fun conditionOn(e: Experiment, date: LocalDate): Char? {
        val start = LocalDate.parse(e.start)
        val n = java.time.temporal.ChronoUnit.DAYS.between(start, date)
        if (n < 0 || n >= e.days || e.ended) return null
        return if (n % 2 == 0L) 'A' else 'B'
    }

    fun active(d: DietData, today: LocalDate) = d.experiments.filter { conditionOn(it, today) != null }

    data class Result(val metric: String, val a: Double, val b: Double, val nA: Int, val nB: Int, val strength: Double) {
        val verdict get() = when { nA < 3 || nB < 3 -> "too few days yet"; strength >= 2 -> "likely real"; strength >= 1 -> "maybe, keep going"; else -> "no clear difference" }
    }

    fun results(d: DietData, e: Experiment): List<Result> {
        val start = LocalDate.parse(e.start)
        val days = (0 until e.days).map { start.plusDays(it.toLong()) }.filter { e.followed[it.toString()] == true }
        fun check(day: LocalDate) = d.checkins.filter { it.date == day.toString() }.maxByOrNull { it.at }
        fun res(metric: String, value: (LocalDate) -> Double?): Result? {
            val a = days.filter { conditionOn(e.copy(ended = false), it) == 'A' }.mapNotNull(value)
            val b = days.filter { conditionOn(e.copy(ended = false), it) == 'B' }.mapNotNull(value)
            if (a.isEmpty() && b.isEmpty()) return null
            fun v(x: List<Double>): Double { if (x.size < 2) return 1.0; val m = x.average(); return x.sumOf { (it - m) * (it - m) } / (x.size - 1) }
            val se = kotlin.math.sqrt(v(a) / a.size.coerceAtLeast(1) + v(b) / b.size.coerceAtLeast(1)).coerceAtLeast(0.05)
            val ma = if (a.isEmpty()) 0.0 else a.average()
            val mb = if (b.isEmpty()) 0.0 else b.average()
            return Result(metric, ma, mb, a.size, b.size, abs(ma - mb) / se)
        }
        return listOfNotNull(
            res("Energy") { check(it)?.energy?.toDouble() },
            res("Clarity") { check(it)?.clarity?.toDouble() },
            res("Workout felt") { e.gym[it.toString()]?.toDouble() },
        )
    }
}
