package com.maven.notafraud.core.routine

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
)

data class RoutineAlert(val item: RoutineItem, val mood: Mood, val title: String, val text: String)

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

    fun weekCount(d: AppData, item: RoutineItem, date: LocalDate): Int {
        val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0..6).map { monday.plusDays(it.toLong()).toString() }.count { iso ->
            d.routineDays.firstOrNull { it.date == iso }?.done?.containsKey(item.id) == true
        }
    }

    fun status(d: AppData, item: RoutineItem, now: Long): RoutineStatus {
        val zone = Time.zone(d.settings.zoneId)
        val date = Time.dateOf(now, zone)
        val today = day(d, date)
        val week = item.weeklyTarget?.let { weekCount(d, item, date) }
        if (!appliesOn(item, date)) return RoutineStatus(item, Mood.OFF, weekDone = week)
        today.done[item.id]?.let { return RoutineStatus(item, Mood.DONE, it, weekDone = week, note = today.notes[item.id]) }

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
        return d.routine.filter { appliesOn(it, date) && it.weeklyTarget == null && it.id !in rd.done }
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
                Mood.ANGRY -> last == null || now - last >= (ANGRY_REPEAT_MIN - 1) * Time.MIN ||
                    (bedtimeSignal && item.trigger == RoutineTrigger.BEDTIME && now - last >= 10 * Time.MIN)
                else -> false
            }
            if (fire) {
                val (title, text) = message(item, mood, st.minutesLate, Time.clock(now, zone), bedtimeSignal)
                out += RoutineAlert(item, mood, title, text)
            }
        }
        return out
    }

    /** Never a wall of notifications: at most this many at once, the most pressing first. */
    const val MAX_AT_ONCE = 2

    /** Next instant at which [alertsDue] could return something new, for the alarm. */
    fun nextAlertAt(d: AppData, now: Long): Long? {
        if (!d.settings.routineNagsEnabled) return null
        val zone = Time.zone(d.settings.zoneId)
        val date = Time.dateOf(now, zone)
        val today = day(d, date)
        val candidates = mutableListOf<Long>()
        for (st in statuses(d, now)) {
            if (st.mood == Mood.DONE || st.mood == Mood.OFF) continue
            if (st.item.weeklyTarget != null && (st.weekDone ?: 0) >= st.item.weeklyTarget) continue
            val i = st.item
            candidates += Time.at(date, i.dueMin, zone)
            candidates += Time.at(date, i.angryMin, zone)
            today.alerted[i.id]?.let { candidates += it + ANGRY_REPEAT_MIN * Time.MIN }
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
            RoutineCategory.OTHER -> when (mood) {
                Mood.LATE -> "$name" to "Due now."
                else -> "$name ($lateText)" to "Do it before bed."
            }
        }
    }
}
