package com.maven.notafraud.core.routine

import com.maven.notafraud.core.diet.DietLink
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.RoutineDay
import com.maven.notafraud.core.model.RoutineItem
import com.maven.notafraud.core.model.RoutineTrigger
import com.maven.notafraud.core.plan.RoughDay
import com.maven.notafraud.core.plan.Time
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

enum class Mood { CALM, DUE, LATE, ANGRY, DONE, MISSED, OFF }

data class RoutineStatus(
    val item: RoutineItem,
    val mood: Mood,
    val doneAt: Long? = null,
    /** Minutes past due (LATE/ANGRY), else 0. */
    val minutesLate: Int = 0,
    /** Weekly-target items: done this week / target. */
    val weekDone: Int? = null,
    val note: String? = null,
    /** STREAK items: days in a row so far (today included unless broken) and the best run. */
    val streak: Int? = null,
    val best: Int? = null,
    /** Driven by Not a Twig (a linked meal, or one of its own reminders). */
    val fromDiet: Boolean = false,
)

/** [fromDiet]: a Not a Twig reminder; the notification opens Twig instead of offering "Done". */
data class RoutineAlert(val item: RoutineItem, val mood: Mood, val title: String, val text: String, val fromDiet: Boolean = false)

/**
 * Daily habits that get angrier the longer they're put off (STATE.md section 10).
 * Pure functions over AppData; the Android side turns alerts into notifications.
 */
object RoutinePolicy {
    /** While angry, nag again this often. */
    const val ANGRY_REPEAT_MIN = 30
    /** No routine nags after this (bed + 30). */
    const val QUIET_AFTER_MIN = 22 * 60 + 45

    fun day(d: AppData, date: LocalDate): RoutineDay =
        d.routineDays.firstOrNull { it.date == date.toString() } ?: RoutineDay(date.toString())

    fun appliesOn(item: RoutineItem, date: LocalDate) = item.enabled && date.dayOfWeek.value in item.days

    /** Notes that mark a workout as a sport (climbing, games…). These only count once [STANDARD_FIRST] standard workouts are in. */
    val SPORT_NOTES = setOf("Climbing", "Sport", "Hike", "Swim", "Breaking")
    const val STANDARD_FIRST = 3

    data class WeekTally(val standard: Int, val sport: Int) {
        val counted: Int get() = standard + if (standard >= STANDARD_FIRST) sport else 0
    }

    fun weekTally(d: AppData, item: RoutineItem, date: LocalDate): WeekTally {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        var standard = 0
        var sport = 0
        for (i in 0..6) {
            val rd = d.routineDays.firstOrNull { it.date == monday.plusDays(i.toLong()).toString() } ?: continue
            if (!rd.done.containsKey(item.id)) continue
            if (rd.notes[item.id] in SPORT_NOTES) sport++ else standard++
        }
        return WeekTally(standard, sport)
    }

    fun weekCount(d: AppData, item: RoutineItem, date: LocalDate): Int = weekTally(d, item, date).counted

    /** Done, but after its due time (woke at 6:40 when due by 6:00). */
    fun doneLate(d: AppData, st: RoutineStatus): Boolean {
        val at = st.doneAt ?: return false
        return Time.minuteOfDay(at, Time.zone(d.settings.zoneId)) > st.item.dueMin
    }

    // ---- Streaks (RoutineCategory.STREAK): done every day on their own until broken -------------

    private fun brokenDates(d: AppData, item: RoutineItem): Set<LocalDate> =
        d.routineDays.filter { item.id in it.broken }.map { LocalDate.parse(it.date) }.toSet()

    private fun streakStart(item: RoutineItem, date: LocalDate): LocalDate =
        item.streakSince?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isAfter(date) } ?: date

    /** Applicable days in a row up to [date] (included) since the last break. */
    fun streak(d: AppData, item: RoutineItem, date: LocalDate): Int {
        val broken = brokenDates(d, item)
        val start = streakStart(item, date)
        var n = 0
        var day = date
        while (!day.isBefore(start) && n < 20_000) {
            if (day in broken) break
            if (appliesOn(item, day)) n++
            day = day.minusDays(1)
        }
        return n
    }

    /** Longest run since the streak started. */
    fun bestStreak(d: AppData, item: RoutineItem, date: LocalDate): Int {
        val broken = brokenDates(d, item)
        var best = 0
        var run = 0
        var day = streakStart(item, date)
        while (!day.isAfter(date)) {
            if (day in broken) run = 0 else if (appliesOn(item, day)) run++
            best = maxOf(best, run)
            day = day.plusDays(1)
        }
        return best
    }

    fun brokenOn(d: AppData, item: RoutineItem, date: LocalDate) = item.id in day(d, date).broken

    /** A streak item counts as done on [date] unless it was broken (or the streak hadn't started). */
    fun streakHeld(d: AppData, item: RoutineItem, date: LocalDate) =
        appliesOn(item, date) && !brokenOn(d, item, date) &&
            item.streakSince?.let { runCatching { !date.isBefore(LocalDate.parse(it)) }.getOrDefault(true) } != false

    private fun streakStatus(d: AppData, item: RoutineItem, date: LocalDate): RoutineStatus {
        val best = bestStreak(d, item, date)
        if (brokenOn(d, item, date)) {
            val before = streak(d, item, date.minusDays(1))
            return RoutineStatus(item, Mood.MISSED, streak = 0, best = best, note = "Broken today" + if (before > 0) " (was $before days)" else "")
        }
        val n = streak(d, item, date)
        return RoutineStatus(item, Mood.DONE, streak = n, best = best, note = "$n day" + (if (n == 1) "" else "s") + " in a row" + if (best > n) " · best $best" else "")
    }

    /** A meal Not a Twig owns: done when Twig has food in that slot, late / angry when Twig says so. */
    private fun dietMealStatus(d: AppData, item: RoutineItem, now: Long, m: Int): RoutineStatus? {
        val s = DietLink.today(d, now) ?: return null
        val slot = item.dietSlot ?: return null
        if (DietLink.slotLogged(s, slot)) return RoutineStatus(item, Mood.DONE, s.generatedAt, note = "Not a Twig", fromDiet = true)
        val nag = DietLink.mealNag(s, slot)
        return when {
            nag != null -> RoutineStatus(item, if (nag.level >= 2) Mood.ANGRY else Mood.LATE, note = nag.text, fromDiet = true)
            m < item.startMin -> RoutineStatus(item, Mood.CALM, note = "Not a Twig: $slot", fromDiet = true)
            else -> RoutineStatus(item, Mood.DUE, note = "Not a Twig: $slot", fromDiet = true)
        }
    }

    fun status(d: AppData, item: RoutineItem, now: Long): RoutineStatus {
        val zone = Time.zone(d.settings.zoneId)
        val date = Time.dateOf(now, zone)
        val today = day(d, date)
        val week = item.weeklyTarget?.let { weekCount(d, item, date) }
        if (!appliesOn(item, date)) return RoutineStatus(item, Mood.OFF, weekDone = week)
        if (item.category == RoutineCategory.STREAK) return streakStatus(d, item, date)
        today.done[item.id]?.let { return RoutineStatus(item, Mood.DONE, it, weekDone = week, note = today.notes[item.id]) }
        if (DietLink.owns(d, item)) dietMealStatus(d, item, now, Time.minuteOfDay(now, zone))?.let { return it }

        // Weekly target already met: optional, never angry.
        if (item.weeklyTarget != null && week != null && week >= item.weeklyTarget) {
            return RoutineStatus(item, Mood.CALM, weekDone = week, note = "Target met this week")
        }
        // Rough day: body items stay optional.
        val rough = RoughDay.isRough(d.days.firstOrNull { it.date == date.toString() }, d.settings.badSleepHours)
        val m = Time.minuteOfDay(now, zone)
        val late = (m - item.dueMin).coerceAtLeast(0)
        val mood = when {
            m < item.startMin -> Mood.CALM
            m < item.dueMin -> Mood.DUE
            rough && item.category == RoutineCategory.BODY && item.weeklyTarget != null -> Mood.DUE
            m < item.angryMin -> Mood.LATE
            else -> Mood.ANGRY
        }
        return RoutineStatus(item, mood, minutesLate = if (mood == Mood.LATE || mood == Mood.ANGRY) late else 0, weekDone = week)
    }

    fun statuses(d: AppData, now: Long): List<RoutineStatus> =
        d.routine.sortedBy { it.order }.map { status(d, it, now) }

    /** Past day: anything applicable and not done is missed. */
    fun missedOn(d: AppData, date: LocalDate): List<RoutineItem> {
        val rd = day(d, date)
        return d.routine.filter { appliesOn(it, date) && it.weeklyTarget == null && it.category != RoutineCategory.STREAK && it.id !in rd.done }
    }

    /**
     * Nudges due right now. LATE nags once; ANGRY nags every [ANGRY_REPEAT_MIN].
     * [bedtimeSignal]: the phone looks like it's going to bed — BEDTIME items past their window start go angry now.
     */
    fun alertsDue(d: AppData, now: Long, bedtimeSignal: Boolean = false): List<RoutineAlert> =
        allDue(d, now, bedtimeSignal)
            .sortedByDescending { if (bedtimeSignal && it.item.trigger == RoutineTrigger.BEDTIME) 100 else it.mood.ordinal }
            .take(MAX_AT_ONCE)

    private fun allDue(d: AppData, now: Long, bedtimeSignal: Boolean): List<RoutineAlert> {
        if (!d.settings.routineNagsEnabled) return emptyList()
        val zone = Time.zone(d.settings.zoneId)
        val m = Time.minuteOfDay(now, zone)
        if (m >= QUIET_AFTER_MIN) return emptyList()
        val date = Time.dateOf(now, zone)
        val today = day(d, date)
        val since = d.settings.routineSince
        val out = mutableListOf<RoutineAlert>()
        for (st in statuses(d, now)) {
            val item = st.item
            // Already overdue when tracking began (e.g. installed mid-afternoon): show it, don't nag.
            if (since != null && Time.dateOf(since, zone) == date && Time.at(date, item.dueMin, zone) < since) continue
            val last = today.alerted[item.id]
            var mood = st.mood
            if (bedtimeSignal && item.trigger == RoutineTrigger.BEDTIME && (mood == Mood.DUE || mood == Mood.LATE)) mood = Mood.ANGRY
            val fire = when (mood) {
                Mood.LATE -> last == null
                Mood.ANGRY -> last == null || now - last >= (d.settings.angryRepeatMin - 1) * Time.MIN ||
                    (bedtimeSignal && item.trigger == RoutineTrigger.BEDTIME && now - last >= 10 * Time.MIN)
                else -> false
            }
            if (fire) {
                val (title, text) = message(item, mood, st.minutesLate, Time.clock(now, zone), bedtimeSignal)
                out += RoutineAlert(item, mood, title, if (st.fromDiet && st.note != null) st.note else text, st.fromDiet)
            }
        }
        // Not a Twig's other reminders (water, weigh-in, check-in…) ride the same escalation.
        for (n in DietLink.extraNags(d, now)) {
            val id = DietLink.PREFIX + n.key
            val last = today.alerted[id]
            val mood = if (n.level >= 2) Mood.ANGRY else Mood.LATE
            val fire = last == null || (mood == Mood.ANGRY && now - last >= (d.settings.angryRepeatMin - 1) * Time.MIN)
            if (fire) out += RoutineAlert(dietItem(n), mood, n.title, n.text, fromDiet = true)
        }
        return out
    }

    /** A stand-in routine item for a Twig reminder (not stored). */
    fun dietItem(n: com.maven.notafraud.core.diet.SyncNag) =
        RoutineItem(id = DietLink.PREFIX + n.key, title = n.title, category = RoutineCategory.OTHER, startMin = 0, dueMin = 0, angryMin = 0)

    /** Never a wall of notifications: at most this many at once, the most pressing first. */
    const val MAX_AT_ONCE = 2

    /** How often Twig's status is re-read for reminders. */
    const val DIET_POLL_MIN = 30

    /** Next instant at which [alertsDue] could return something new, for the alarm. */
    fun nextAlertAt(d: AppData, now: Long): Long? {
        if (!d.settings.routineNagsEnabled) return null
        val zone = Time.zone(d.settings.zoneId)
        val date = Time.dateOf(now, zone)
        val today = day(d, date)
        val candidates = mutableListOf<Long>()
        for (st in statuses(d, now)) {
            if (st.mood == Mood.DONE || st.mood == Mood.OFF) continue
            if (st.mood == Mood.MISSED || st.item.category == RoutineCategory.STREAK) continue
            if (st.item.weeklyTarget != null && (st.weekDone ?: 0) >= st.item.weeklyTarget) continue
            val i = st.item
            if (!st.fromDiet) {
                candidates += Time.at(date, i.dueMin, zone)
                candidates += Time.at(date, i.angryMin, zone)
            }
            today.alerted[i.id]?.let { candidates += it + d.settings.angryRepeatMin * Time.MIN }
        }
        // Not a Twig connected: look again every half hour (its reminders grow with time, not with changes).
        if (d.settings.dietNags && d.diet != null) {
            candidates += now + DIET_POLL_MIN * Time.MIN
            for (n in DietLink.extraNags(d, now)) today.alerted[DietLink.PREFIX + n.key]?.let { candidates += it + d.settings.angryRepeatMin * Time.MIN }
        }
        // More due than we post at once: come back in a few minutes for the rest.
        if (allDue(d, now, false).size > MAX_AT_ONCE) candidates += now + 5 * Time.MIN
        val quiet = Time.at(date, QUIET_AFTER_MIN, zone)
        return candidates.filter { it > now + 30_000 && it < quiet }.minOrNull()
    }

    fun message(item: RoutineItem, mood: Mood, minutesLate: Int, clock: String, bedtime: Boolean = false): Pair<String, String> {
        val name = item.title
        val lateText = if (minutesLate >= 60) Time.durationText(minutesLate) + " late" else "$minutesLate min late"
        return when (item.category) {
            RoutineCategory.MEAL -> when (mood) {
                Mood.LATE -> "$name: eat now" to "It's $clock. Eat something now, even something small."
                else -> "$clock and no ${name.lowercase()}" to "Stop and eat. Everything else can wait 15 minutes."
            }
            RoutineCategory.HYGIENE -> when {
                bedtime || item.trigger == RoutineTrigger.BEDTIME -> "Not yet: $name" to "Don't go to bed without it. Two minutes. Go."
                mood == Mood.LATE -> "$name ($lateText)" to "Do it now and it's off your mind."
                else -> "$name still not done" to "It's $clock. Two minutes. Do it now."
            }
            RoutineCategory.SLEEP -> when (mood) {
                Mood.LATE -> "$name" to "It's $clock. Wrap up."
                else -> "$clock: phone down" to "Lights out. Tomorrow starts at 5:30."
            }
            RoutineCategory.BODY -> when (mood) {
                Mood.LATE -> "$name ($lateText)" to "Still time. Even a short one counts."
                else -> "$name still not done" to "It's $clock. Do the short version now rather than none."
            }
            RoutineCategory.STREAK -> "$name" to "Streak."
            RoutineCategory.OTHER -> when (mood) {
                Mood.LATE -> "$name" to "Due now."
                else -> "$name ($lateText)" to "Do it before bed."
            }
        }
    }
}
