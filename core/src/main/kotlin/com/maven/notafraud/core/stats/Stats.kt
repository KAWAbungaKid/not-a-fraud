package com.maven.notafraud.core.stats

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.TaskKind
import com.maven.notafraud.core.plan.Practice
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.RoutinePolicy
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Stats v1 (STATE.md section 3 rule: sensor-filled or one tap, or it doesn't exist).
 * Each stat is 1–10 over the last 7 days, with the 7 days before for a trend.
 * Automatic: sleep, workouts, steps, routine on-time rates, logged study / research time, plan adherence.
 * One tap a week: social, spirit, stress, happiness (Sunday check-in).
 */
data class Stat(
    val key: String,
    val name: String,
    val score: Double?,
    val prev: Double?,
    val parts: List<Pair<String, String>>,
    val areaIds: Set<String>,
) {
    val trend: Double? get() = if (score != null && prev != null) score - prev else null
}

object Stats {
    const val LOW = 4.0

    /** Stat key -> areas it covers (for stat-driven importance). */
    val AREAS = mapOf(
        "body" to setOf("a-fitness", "a-health"),
        "mind" to setOf("a-school", "a-mind"),
        "work" to setOf("a-research", "a-pt", "a-career"),
        "hygiene" to setOf("a-health"),
        "discipline" to setOf("a-systems"),
        "social" to setOf("a-people"),
        "spirit" to setOf("a-faith", "a-church"),
    )

    private fun clamp(x: Double) = x.coerceIn(1.0, 10.0)
    private fun avg(xs: List<Double>) = if (xs.isEmpty()) null else xs.average()

    fun all(d: AppData, today: LocalDate): List<Stat> = listOf(
        body(d, today), mind(d, today), work(d, today), hygiene(d, today), discipline(d, today), checkIn(d, today, "social", "Social"),
        checkIn(d, today, "spirit", "Spirit"),
    )

    /** Areas whose stat is low: the planner gives them one more importance. */
    fun lowAreas(d: AppData, today: LocalDate): Set<String> {
        if (!d.settings.statsDriveImportance) return emptySet()
        return all(d, today).filter { (it.score ?: 10.0) < LOW }.flatMap { it.areaIds }.toSet()
    }

    private fun days(end: LocalDate, back: Int = 0) = (0..6).map { end.minusDays((it + back * 7).toLong()) }

    private fun sleepScore(d: AppData, ds: List<LocalDate>): Double? {
        val hrs = ds.mapNotNull { day -> d.days.firstOrNull { it.date == day.toString() }?.sleepHours }
        return avg(hrs)?.let { clamp((it - 4) * 2.5) }
    }

    private fun workoutScore(d: AppData, ds: List<LocalDate>): Double? {
        val w = d.routine.firstOrNull { it.id == "r-workout" } ?: return null
        val n = ds.count { day -> RoutinePolicy.day(d, day).done.containsKey(w.id) }
        return clamp(10.0 * n / (w.weeklyTarget ?: 6))
    }

    private fun stepsScore(d: AppData, ds: List<LocalDate>): Double? =
        avg(ds.mapNotNull { day -> d.days.firstOrNull { it.date == day.toString() }?.steps?.toDouble() })?.let { clamp(it / 1000.0) }

    private fun rate(d: AppData, ds: List<LocalDate>, cat: RoutineCategory, onTimeOnly: Boolean = false): Double? {
        val items = d.routine.filter { it.enabled && it.category == cat && it.weeklyTarget == null }
        var due = 0
        var ok = 0
        for (day in ds) for (i in items) {
            if (!RoutinePolicy.appliesOn(i, day)) continue
            val at = RoutinePolicy.day(d, day).done[i.id]
            due++
            if (at != null && (!onTimeOnly || Time.minuteOfDay(at, Time.zone(d.settings.zoneId)) <= i.angryMin)) ok++
        }
        return if (due == 0) null else clamp(10.0 * ok / due)
    }

    private fun body(d: AppData, today: LocalDate): Stat {
        fun calc(ds: List<LocalDate>) = avg(listOfNotNull(sleepScore(d, ds), workoutScore(d, ds), stepsScore(d, ds), rate(d, ds, RoutineCategory.MEAL)))
        val ds = days(today)
        return Stat(
            "body", "Body", calc(ds), calc(days(today, 1)),
            listOfNotNull(
                sleepScore(d, ds)?.let { "Sleep" to "%.1f".format(it) },
                workoutScore(d, ds)?.let { "Workouts" to "%.1f".format(it) },
                stepsScore(d, ds)?.let { "Steps" to "%.1f".format(it) },
                rate(d, ds, RoutineCategory.MEAL)?.let { "Meals eaten" to "%.1f".format(it) },
            ),
            AREAS.getValue("body"),
        )
    }

    private fun minutesIn(d: AppData, ds: List<LocalDate>, areas: Set<String>): Int {
        val zone = Time.zone(d.settings.zoneId)
        val from = Time.at(ds.min(), 0, zone)
        val to = Time.at(ds.max().plusDays(1), 0, zone)
        val projects = d.projects.filter { it.areaId in areas }.map { it.id }.toSet()
        return d.sessions.filter { it.start in from until to && it.projectId in projects }.sumOf { it.minutes }
    }

    private fun mind(d: AppData, today: LocalDate): Stat {
        val target = d.tasks.filter { it.kind == TaskKind.PRACTICE && d.projects.firstOrNull { p -> p.id == it.projectId }?.areaId in setOf("a-school", "a-mind") }
            .sumOf { it.weeklyTargetMin ?: 0 }.coerceAtLeast(300)
        fun calc(ds: List<LocalDate>) = clamp(10.0 * minutesIn(d, ds, setOf("a-school", "a-mind")) / target).takeIf { d.sessions.isNotEmpty() }
        val m = minutesIn(d, days(today), setOf("a-school", "a-mind"))
        return Stat("mind", "Mind", calc(days(today)), calc(days(today, 1)), listOf("Study logged" to Time.durationText(m), "Weekly aim" to Time.durationText(target)), AREAS.getValue("mind"))
    }

    private fun work(d: AppData, today: LocalDate): Stat {
        val areas = setOf("a-research", "a-pt", "a-career")
        val target = 360
        fun calc(ds: List<LocalDate>) = clamp(10.0 * minutesIn(d, ds, areas) / target).takeIf { d.sessions.isNotEmpty() }
        val m = minutesIn(d, days(today), areas)
        return Stat("work", "Work", calc(days(today)), calc(days(today, 1)), listOf("Research / PT / career" to Time.durationText(m), "Weekly aim" to Time.durationText(target)), AREAS.getValue("work"))
    }

    private fun hygiene(d: AppData, today: LocalDate) = Stat(
        "hygiene", "Hygiene", rate(d, days(today), RoutineCategory.HYGIENE), rate(d, days(today, 1), RoutineCategory.HYGIENE),
        listOfNotNull(rate(d, days(today), RoutineCategory.HYGIENE)?.let { "Done" to "${(it * 10).roundToInt()}%" }), AREAS.getValue("hygiene"),
    )

    /** Plan adherence: logged minutes vs minutes the morning plan asked for. Derived, never self-rated. */
    private fun discipline(d: AppData, today: LocalDate): Stat {
        val zone = Time.zone(d.settings.zoneId)
        fun calc(ds: List<LocalDate>): Double? {
            val ratios = ds.filter { it != today }.mapNotNull { day ->
                val planned = d.days.firstOrNull { it.date == day.toString() }?.plannedTaskMin ?: return@mapNotNull null
                if (planned < 30) return@mapNotNull null
                val from = Time.at(day, 0, zone)
                val worked = d.sessions.filter { it.start in from until from + Time.DAY }.sumOf { it.minutes }
                (worked.toDouble() / planned).coerceAtMost(1.2)
            }
            return avg(ratios)?.let { clamp(it * 10) }
        }
        return Stat("discipline", "Discipline", calc(days(today)), calc(days(today, 1)), listOf("Plan followed" to (calc(days(today))?.let { "${(it * 10).roundToInt()}%" } ?: "not enough days yet")), AREAS.getValue("discipline"))
    }

    private fun checkIn(d: AppData, today: LocalDate, key: String, name: String): Stat {
        val wk = Practice.weekStart(today)
        fun rating(week: LocalDate) = d.checkIns.firstOrNull { it.weekStart == week.toString() }?.ratings?.get(key)
            ?: d.checkIns.firstOrNull { it.weekStart == week.minusDays(7).toString() }?.ratings?.get(key)
        val now = rating(wk)?.let { clamp(it * 2.0) }
        val prev = rating(wk.minusDays(7))?.let { clamp(it * 2.0) }
        return Stat(key, name, now, prev, listOf("Weekly check-in" to (rating(wk)?.let { "$it/5" } ?: "not yet")), AREAS[key].orEmpty())
    }

    /** Most-pushed tasks over the last 14 days (patterns, neutral for now). */
    fun mostPushed(d: AppData, now: Long, n: Int = 5) =
        d.tasks.map { t -> t to t.deferrals.count { it > now - 14 * Time.DAY } }.filter { it.second > 0 }.sortedByDescending { it.second }.take(n)
}

/** Skill levels from practice time: 1 h, 5 h, 15 h, 40 h, 100 h. */
object Skills {
    val LEVEL_HOURS = intArrayOf(0, 1, 5, 15, 40, 100)

    fun minutes(d: AppData, skillId: String): Int {
        val s = d.skills.firstOrNull { it.id == skillId } ?: return 0
        val logged = s.practiceTaskId?.let { tid -> d.sessions.filter { it.taskId == tid }.sumOf { it.minutes } } ?: 0
        return logged + s.manualMin
    }

    fun level(min: Int): Int = LEVEL_HOURS.indexOfLast { min >= it * 60 }.coerceAtLeast(0)

    /** 0..1 toward the next level. */
    fun progress(min: Int): Double {
        val l = level(min)
        if (l >= LEVEL_HOURS.size - 1) return 1.0
        val a = LEVEL_HOURS[l] * 60
        val b = LEVEL_HOURS[l + 1] * 60
        return (min - a).toDouble() / (b - a)
    }

    /** A skill opens once its parent reaches level 1. */
    fun unlocked(d: AppData, skillId: String): Boolean {
        val s = d.skills.firstOrNull { it.id == skillId } ?: return false
        val parent = s.parentId ?: return true
        return level(minutes(d, parent)) >= 1
    }
}

/** How long sessions on a task actually last (15 min of magic tricks vs an hour of piano). */
object Learning {
    const val MIN_SESSIONS = 3

    fun typicalMin(d: AppData, taskId: String): Int? {
        val m = d.sessions.filter { it.taskId == taskId && it.minutes >= 3 }.takeLast(10).map { it.minutes }.sorted()
        if (m.size < MIN_SESSIONS) return null
        return m[m.size / 2]
    }
}
