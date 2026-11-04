package com.maven.workout.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** How the week's minimum is going. Not a Fraud reads this through the sync provider. */
enum class Risk(val label: String) {
    MET("Minimum met"),
    ON_TRACK("On track"),
    /** One spare day left. */
    TIGHT("Cutting it close"),
    /** Every remaining day (today included) must be a workout. */
    MUST_TODAY("Every day left counts — go today"),
    /** Can't reach the minimum this week any more. */
    MISSED("Minimum out of reach this week"),
}

data class WeekStatus(
    val weekStart: LocalDate,
    val today: LocalDate,
    val done: Int,
    val minimum: Int,
    val target: Int,
    /** Days still available for a workout, today included unless already trained today. */
    val daysLeft: Int,
    val trainedToday: Boolean,
    /** Dates (this week) with a finished workout. */
    val doneDates: List<LocalDate>,
) {
    val needed get() = (minimum - done).coerceAtLeast(0)
    /** Spare days: how many days can still be skipped and the minimum still met. */
    val slack get() = daysLeft - needed
    val risk: Risk get() = when {
        needed == 0 -> Risk.MET
        slack < 0 -> Risk.MISSED
        slack == 0 -> Risk.MUST_TODAY
        slack == 1 -> Risk.TIGHT
        else -> Risk.ON_TRACK
    }
    fun message(): String = when (risk) {
        Risk.MET -> if (done >= target) "Full week done ($done/$target)." else "Minimum met ($done/$minimum). Extra days: ${target - done} left in the plan."
        Risk.ON_TRACK -> "$done of $minimum so far · $needed more in $daysLeft days."
        Risk.TIGHT -> "$done of $minimum · $needed more in $daysLeft days — one spare day left."
        Risk.MUST_TODAY -> "$done of $minimum · $needed more in $daysLeft days — no spare days. Train today."
        Risk.MISSED -> "$done of $minimum · only $daysLeft days left. Get as many as you can."
    }
}

object Week {
    fun start(day: LocalDate, mondayStart: Boolean): LocalDate =
        day.with(TemporalAdjusters.previousOrSame(if (mondayStart) DayOfWeek.MONDAY else DayOfWeek.SUNDAY))

    /** Distinct dates with a finished workout that has at least one completed set. */
    fun trainedDates(d: WorkoutData): Set<LocalDate> =
        d.finished().filter { it.doneSets > 0 }.map { LocalDate.parse(it.date) }.toSet()

    fun target(d: WorkoutData): Int = d.settings.targetPerWeek ?: (d.program()?.days?.size ?: d.settings.minPerWeek)

    fun status(d: WorkoutData, today: LocalDate): WeekStatus {
        val ws = start(today, d.settings.weekStartsMonday)
        val dates = trainedDates(d).filter { !it.isBefore(ws) && !it.isAfter(today) }.sorted()
        val trainedToday = today in dates
        val end = ws.plusDays(6)
        val left = ChronoUnit.DAYS.between(today, end).toInt() + (if (trainedToday) 0 else 1)
        return WeekStatus(ws, today, dates.size, d.settings.minPerWeek, target(d), left, trainedToday, dates)
    }

    /** Consecutive finished weeks (before this one) that met the minimum. */
    fun streak(d: WorkoutData, today: LocalDate): Int {
        val dates = trainedDates(d)
        var ws = start(today, d.settings.weekStartsMonday).minusWeeks(1)
        var n = 0
        while (n < 520) {
            val c = dates.count { !it.isBefore(ws) && it.isBefore(ws.plusDays(7)) }
            if (c < d.settings.minPerWeek) break
            n++; ws = ws.minusWeeks(1)
        }
        return n
    }
}

/**
 * Moves the program week/block forward as calendar weeks finish (the sheet's Week/Block dropdowns, automated).
 * EARNED: a calendar week counts only if you hit the minimum; otherwise the program week repeats.
 * A long gap (no workouts for [Settings.reentryAfterDays]) restarts the current block at week 1.
 */
object Cycle {
    data class Rolled(val data: WorkoutData, val notes: List<String>)

    fun roll(d: WorkoutData, today: LocalDate): Rolled {
        val p = d.program() ?: return Rolled(d, emptyList())
        val s = d.settings
        val thisWeek = Week.start(today, s.weekStartsMonday)
        val counted = d.cycle.countedThrough?.let { LocalDate.parse(it) }
        if (counted == null) {
            // First run: start counting from last week, don't advance.
            return Rolled(d.copy(cycle = d.cycle.copy(countedThrough = thisWeek.minusWeeks(1).toString())), emptyList())
        }
        var c = d.cycle
        val notes = ArrayList<String>()
        val dates = Week.trainedDates(d)
        var ws = counted.plusWeeks(1)
        while (ws.isBefore(thisWeek)) {
            val n = dates.count { !it.isBefore(ws) && it.isBefore(ws.plusDays(7)) }
            val advance = when (s.advance) {
                Advance.EARNED -> n >= s.minPerWeek
                Advance.CALENDAR -> true
                Advance.MANUAL -> false
            }
            if (advance) c = next(c, p)
            else if (s.advance == Advance.EARNED) notes += "Week of ${ws}: $n/${s.minPerWeek} — repeating week ${c.week}."
            c = c.copy(countedThrough = ws.toString())
            ws = ws.plusWeeks(1)
        }
        // Re-entry after a long break.
        val last = dates.filter { it.isBefore(today) }.maxOrNull()
        if (s.reentryAfterDays > 0 && last != null && c.week != 1 &&
            ChronoUnit.DAYS.between(last, today) >= s.reentryAfterDays && d.cycle.countedThrough != c.countedThrough
        ) {
            notes += "${ChronoUnit.DAYS.between(last, today)} days off — easing back in at week 1 of block ${c.block}."
            c = c.copy(week = 1)
        }
        return Rolled(if (c == d.cycle) d else d.copy(cycle = c), notes)
    }

    fun next(c: CycleState, p: Program): CycleState = when {
        c.week < p.weeksPerBlock -> c.copy(week = c.week + 1)
        c.block < p.blocks -> c.copy(week = 1, block = c.block + 1)
        else -> c.copy(week = 1, block = 1, cyclesDone = c.cyclesDone + 1)
    }

    fun label(c: CycleState, p: Program?): String =
        if (p == null) "" else "Week ${c.week} · Block ${c.block}  (${Prescriber.stepIndex(p, c.week, c.block) + 1}/${p.cycleWeeks})"
}

/**
 * Today's suggested workout. First the core days (Push / Pull / Legs) not yet done this week, then the extra days.
 * Habit-breaker: over the last 4 weeks, a core day that keeps getting skipped jumps the queue, with a reason shown.
 */
object Suggest {
    data class Pick(val template: DayTemplate, val reason: String)
    data class Result(val pick: Pick?, val others: List<DayTemplate>, val balance: Map<String, Int>, val nudge: String?)

    fun counts(d: WorkoutData, today: LocalDate, days: Long = 28): Map<String, Int> {
        val from = today.minusDays(days)
        val m = HashMap<String, Int>()
        for (s in d.finished()) {
            val t = s.templateId ?: continue
            val dt = LocalDate.parse(s.date)
            if (dt.isBefore(from) || dt.isAfter(today) || s.doneSets == 0) continue
            m[t] = (m[t] ?: 0) + 1
        }
        return m
    }

    fun today(d: WorkoutData, today: LocalDate): Result {
        val p = d.program() ?: return Result(null, emptyList(), emptyMap(), null)
        val ws = Week.start(today, d.settings.weekStartsMonday)
        val doneThisWeek = d.finished().filter {
            val dt = LocalDate.parse(it.date); !dt.isBefore(ws) && !dt.isAfter(today) && it.doneSets > 0
        }.mapNotNull { it.templateId }.toSet()
        val core = p.days.take(p.coreDays)
        val extra = p.days.drop(p.coreDays)
        val bal = counts(d, today)
        val coreLeft = core.filter { it.id !in doneThisWeek }
        val pool: List<DayTemplate>
        var why: String
        if (coreLeft.isNotEmpty()) {
            pool = coreLeft; why = "Next of your ${core.size} core days this week."
        } else {
            val extraLeft = extra.filter { it.id !in doneThisWeek }
            pool = extraLeft.ifEmpty { p.days }
            why = if (extraLeft.isNotEmpty()) "Core days done — this is extra day ${p.days.indexOf(pool.first()) + 1}." else "Every day done this week — pick anything."
        }
        // Habit-breaker among core days: most-skipped first (counts over 4 weeks), ties keep program order.
        val maxCore = core.maxOfOrNull { bal[it.id] ?: 0 } ?: 0
        val ranked = pool.sortedWith(compareBy<DayTemplate>({ t ->
            if (t in core) -(maxCore - (bal[t.id] ?: 0)) else 0
        }, { p.days.indexOf(it) }))
        val pick = ranked.firstOrNull()
        if (pick != null && pick in core && maxCore - (bal[pick.id] ?: 0) >= 2) {
            val most = core.maxByOrNull { bal[it.id] ?: 0 }!!
            why = "${pick.name} ${bal[pick.id] ?: 0}× vs ${most.name} ${bal[most.id] ?: 0}× in 4 weeks — evening it out."
        }
        // Not a Fraud says tomorrow is a sport day (climbing): keep shoulders & arms fresh for it.
        val sport = d.sportDays[today.plusDays(1).toString()]
        var finalPick = pick
        if (sport != null && pick != null && armFocus(d, pick) >= ARM_DAY) {
            // Another day from today's list, else any day not done yet this week (an extra day is fine).
            (ranked.firstOrNull { armFocus(d, it) < ARM_DAY } ?: p.days.firstOrNull { it.id !in doneThisWeek && it !in ranked && armFocus(d, it) < ARM_DAY })?.let { alt ->
                finalPick = alt
                why = "$sport tomorrow: ${alt.name} today, so ${pick.name} doesn't tire you for it."
            }
        }
        val others = p.days.filter { it != finalPick }
        return Result(finalPick?.let { Pick(it, why) }, others, bal, imbalance(core, bal))
    }

    /** A day this arm-focused gets swapped out the day before a sport. */
    const val ARM_DAY = 0.6
    private val ARMS = setOf(Muscle.SHOULDERS, Muscle.BICEPS, Muscle.TRICEPS, Muscle.FOREARMS)
    private val BIG = setOf(Muscle.CHEST, Muscle.BACK, Muscle.QUADS, Muscle.HAMSTRINGS, Muscle.GLUTES)

    /** Share of a day's exercises that are mainly shoulders / arms / grip (what climbing needs fresh). */
    fun armFocus(d: WorkoutData, t: DayTemplate): Double {
        val exs = t.exerciseIds.mapNotNull { d.exercise(it) }
        if (exs.isEmpty()) return 0.0
        return exs.count { e -> e.muscles.any { it in ARMS } && e.muscles.none { it in BIG } }.toDouble() / exs.size
    }

    /** "Push 4× · Legs 1× in 4 weeks" when the core days are lopsided by 2 or more. */
    fun imbalance(core: List<DayTemplate>, bal: Map<String, Int>): String? {
        if (core.size < 2) return null
        val hi = core.maxByOrNull { bal[it.id] ?: 0 }!!
        val lo = core.minByOrNull { bal[it.id] ?: 0 }!!
        val gap = (bal[hi.id] ?: 0) - (bal[lo.id] ?: 0)
        return if (gap >= 2) "${hi.name} ${bal[hi.id] ?: 0}× · ${lo.name} ${bal[lo.id] ?: 0}× in the last 4 weeks." else null
    }

    /** Shown when picking a day that is already ahead of the others. */
    fun warnFor(d: WorkoutData, t: DayTemplate, today: LocalDate): String? {
        val p = d.program() ?: return null
        val core = p.days.take(p.coreDays)
        if (t !in core) return null
        val bal = counts(d, today)
        val lo = core.minByOrNull { bal[it.id] ?: 0 } ?: return null
        val gap = (bal[t.id] ?: 0) - (bal[lo.id] ?: 0)
        return if (gap >= 2) "Heads up: ${t.name} ${bal[t.id] ?: 0}× vs ${lo.name} ${bal[lo.id] ?: 0}× in 4 weeks." else null
    }
}
