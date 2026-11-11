package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskKind
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.model.liveTasks
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.score.Scorer
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * Planned vs actual (STATE.md § 27): how each task block went, a 0–100 day score, the "you said this mattered"
 * nag, and the end-of-day summary. Feedback 2026-11-10: "there's a lot of black, and I should feel more guilty
 * about that, and I should somehow feel rewarded for doing things I was supposed to."
 */
object DayReview {
    enum class Mark { DONE, OTHER, PARTIAL, MISSED, EXCUSED, NOW, LATER }

    data class BlockMark(val mark: Mark, val workedMin: Int, val instead: String? = null)

    private fun overlap(a0: Long, a1: Long, b0: Long, b1: Long): Int = max(0L, min(a1, b1) - max(a0, b0)).let { (it / Time.MIN).toInt() }

    /** Task blocks only (anchors, events and breaks aren't graded); null for the rest. */
    fun mark(d: AppData, b: PlanBlock, now: Long): BlockMark? {
        if (b.kind != BlockKind.TASK) return null
        if (b.contains(now)) return BlockMark(Mark.NOW, d.active?.takeIf { it.taskId == b.taskId }?.let { overlap(it.start, now, b.start, b.end) } ?: 0)
        if (b.start >= now) return BlockMark(Mark.LATER, 0)
        val hits = d.sessions.filter { it.start < b.end && b.start < it.end }
        val onPlan = hits.filter { it.taskId != null && it.taskId == b.taskId }.sumOf { overlap(it.start, it.end, b.start, b.end) }
        val elsewhere = hits.filter { it.taskId == null || it.taskId != b.taskId }
        val otherMin = elsewhere.sumOf { overlap(it.start, it.end, b.start, b.end) }
        val task = b.taskId?.let { id -> d.tasks.firstOrNull { it.id == id } }
        val finished = task?.status == TaskStatus.DONE && (task.doneAt ?: Long.MAX_VALUE) <= b.end + 30 * Time.MIN
        val len = b.minutes.coerceAtLeast(1)
        return when {
            finished || onPlan >= len * 0.6 -> BlockMark(Mark.DONE, onPlan)
            onPlan > 0 -> BlockMark(Mark.PARTIAL, onPlan)
            otherMin >= len * 0.5 -> BlockMark(Mark.OTHER, otherMin, elsewhere.maxByOrNull { overlap(it.start, it.end, b.start, b.end) }?.title)
            excuseFor(d, b) != null -> BlockMark(Mark.EXCUSED, 0, excuseFor(d, b))
            otherMin > 0 -> BlockMark(Mark.OTHER, otherMin, elsewhere.first().title)
            else -> BlockMark(Mark.MISSED, 0)
        }
    }

    fun excuseFor(d: AppData, b: PlanBlock): String? {
        val day = d.days.firstOrNull { it.date == Time.dateOf(b.start, Time.zone(d.settings.zoneId)).toString() } ?: return null
        return day.excused.firstOrNull { it.counts && overlap(it.start, it.end, b.start, b.end) * 2 >= b.minutes }?.reason
    }

    /** Task blocks that ended unworked and unexplained (the ladder's "slipped", the check-in's "what happened?"). */
    fun missed(d: AppData, now: Long): List<PlanBlock> {
        val plan = d.todayPlan ?: return emptyList()
        return plan.blocks.filter { it.kind == BlockKind.TASK && it.end <= now && mark(d, it, now)?.mark == Mark.MISSED }
    }

    data class Totals(val plannedMin: Int, val onPlanMin: Int, val workedMin: Int, val done: Int, val missed: Int, val other: Int)

    /** Today so far: task minutes planned up to now, worked on plan, worked at all. */
    fun totals(d: AppData, now: Long): Totals {
        val plan = d.todayPlan ?: return Totals(0, 0, 0, 0, 0, 0)
        val zone = Time.zone(d.settings.zoneId)
        val dayStart = Time.at(Time.dateOf(now, zone), 0, zone)
        var planned = 0; var onPlan = 0; var done = 0; var missed = 0; var other = 0
        for (b in plan.blocks) {
            if (b.kind != BlockKind.TASK || b.start >= now) continue
            val m = mark(d, b, now) ?: continue
            if (m.mark == Mark.EXCUSED) continue
            planned += overlap(b.start, b.end, b.start, now)
            onPlan += min(m.workedMin, b.minutes)
            when (m.mark) { Mark.DONE -> done++; Mark.MISSED -> missed++; Mark.OTHER -> other++; else -> {} }
        }
        val worked = d.sessions.filter { it.end > dayStart }.sumOf { overlap(it.start, it.end, dayStart, now) } +
            (d.active?.let { ((now - it.start) / Time.MIN).toInt() } ?: 0)
        return Totals(planned, onPlan, worked, done, missed, other)
    }

    data class Score(val value: Int, val parts: List<String>)

    /**
     * 0–100 for [date] (today: so far). Work 50 % (worked ÷ planned task time), routine 30 % (done ÷ due),
     * phone 20 % (blocked-app minutes; 2 h = 0). A part with nothing to measure drops out.
     */
    fun score(d: AppData, date: LocalDate, now: Long): Score? {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        if (date.isAfter(today)) return null
        val start = Time.at(date, 0, zone)
        val end = if (date == today) now else Time.at(date.plusDays(1), 0, zone)
        val parts = mutableListOf<Pair<Double, Double>>()
        val why = mutableListOf<String>()

        val worked = d.sessions.filter { it.end > start && it.start < end }.sumOf { overlap(it.start, it.end, start, end) } +
            (if (date == today) d.active?.let { ((now - it.start) / Time.MIN).toInt() } ?: 0 else 0)
        val planned = if (date == today) totals(d, now).plannedMin else d.days.firstOrNull { it.date == date.toString() }?.plannedTaskMin ?: 0
        if (planned >= 15) {
            val r = (worked.toDouble() / planned).coerceAtMost(1.0)
            parts += r to 0.5
            why += "work ${Time.durationText(worked)} of ${Time.durationText(planned)}"
        }

        val rd = RoutinePolicy.day(d, date)
        val nowMin = if (date == today) Time.minuteOfDay(now, zone) else 24 * 60
        val due = d.routine.filter {
            RoutinePolicy.appliesOn(it, date) && it.weeklyTarget == null && it.category != RoutineCategory.STREAK && it.dueMin <= nowMin
        }
        if (due.isNotEmpty()) {
            val n = due.count { it.id in rd.done }
            parts += (n.toDouble() / due.size) to 0.3
            why += "routine $n/${due.size}"
        }

        val usage = d.usage.firstOrNull { it.date == date.toString() && it.device == "phone" }
        if (usage != null) {
            val wasted = usage.minutesByApp.filterKeys { it in d.rules.blocked }.values.sum()
            parts += (1.0 - wasted / 120.0).coerceIn(0.0, 1.0) to 0.2
            why += "${Time.durationText(wasted)} blocked apps"
        }
        if (parts.isEmpty()) return null
        val v = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
        return Score((v * 100).toInt().coerceIn(0, 100), why)
    }

    /** Good days in a row before today (score ≥ Settings.goodDayScore). */
    fun goodStreak(d: AppData, now: Long): Int {
        val today = Time.dateOf(now, Time.zone(d.settings.zoneId))
        var n = 0
        var day = today.minusDays(1)
        while (n < 365) {
            val s = score(d, day, now) ?: break
            if (s.value < d.settings.goodDayScore) break
            n++; day = day.minusDays(1)
        }
        return n
    }

    data class Nag(val task: Task, val text: String)

    /**
     * Something you marked important (high priority, importance 4–5, hot, or an exam's study task) is due within
     * 4 days and has had under 30 minutes in the last 3 days. Shown once you've spent an hour on other things
     * today, or after noon. "3h on trading today · Thursday's test: 0 so far, ~3h left."
     */
    fun priorityNag(d: AppData, now: Long): Nag? {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        val dayStart = Time.at(today, 0, zone)
        val cands = d.liveTasks().filter { t ->
            t.status == TaskStatus.TODO && t.kind != TaskKind.WAITING && t.dueAt != null && t.dueAt > now && t.dueAt < now + 4 * Time.DAY &&
                (t.hot || t.priority == Priority.HIGH || (t.importance ?: 0) >= 4 || t.sourceKey?.startsWith("exam:") == true || Heat.deadlineHot(t, now)) &&
                Scorer.minutesLeft(t) >= 30
        }
        val recentOn = { t: Task -> d.sessions.filter { it.taskId == t.id && it.end > now - 3 * Time.DAY }.sumOf { it.minutes } }
        val t = cands.filter { recentOn(it) < 30 }.minByOrNull { it.dueAt!! } ?: return null
        if (d.active?.taskId == t.id) return null
        val todays = d.sessions.filter { it.end > dayStart && it.taskId != t.id }
        val elsewhere = todays.groupBy { s -> s.projectId?.let { p -> d.projects.firstOrNull { it.id == p }?.name } ?: s.title }
            .mapValues { (_, v) -> v.sumOf { it.minutes } }.maxByOrNull { it.value }
        val m = Time.minuteOfDay(now, zone)
        if ((elsewhere?.value ?: 0) < 60 && m < 12 * 60) return null
        val dueDay = Time.dateOf(t.dueAt!!, zone)
        val whenText = when (dueDay) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> dueDay.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.US)
        }
        val lead = elsewhere?.takeIf { it.value >= 30 }?.let { "${Time.durationText(it.value)} on ${it.key} today · " } ?: ""
        return Nag(t, "$lead${t.title} (due $whenText): ${Time.durationText(recentOn(t))} so far, ~${Time.durationText(Scorer.minutesLeft(t))} left.")
    }

    data class Summary(
        val date: LocalDate,
        val score: Score?,
        val totals: Totals?,
        val top: List<Pair<String, Int>>,
        val missedBlocks: List<String>,
        val routineDone: Int,
        val routineDue: Int,
        val routineMissed: List<String>,
        val wastedMin: Int,
        val goodStreak: Int,
    )

    /** The end-of-day card (evening, and next morning until dismissed). */
    fun summary(d: AppData, date: LocalDate, now: Long): Summary {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        val start = Time.at(date, 0, zone)
        val end = Time.at(date.plusDays(1), 0, zone)
        val sessions = d.sessions.filter { it.end > start && it.start < end }
        val top = sessions.groupBy { s -> s.projectId?.let { p -> d.projects.firstOrNull { it.id == p }?.name } ?: s.title }
            .mapValues { (_, v) -> v.sumOf { overlap(it.start, it.end, start, end) } }
            .entries.sortedByDescending { it.value }.take(4).map { it.key to it.value }
        val isToday = date == today
        val totals = if (isToday) totals(d, now) else null
        val missed = if (isToday) missed(d, now).map { it.title } else emptyList()
        val rd = RoutinePolicy.day(d, date)
        val nowMin = if (isToday) Time.minuteOfDay(now, zone) else 24 * 60
        val due = d.routine.filter { RoutinePolicy.appliesOn(it, date) && it.weeklyTarget == null && it.category != RoutineCategory.STREAK && it.dueMin <= nowMin }
        val usage = d.usage.firstOrNull { it.date == date.toString() && it.device == "phone" }
        val wasted = usage?.minutesByApp?.filterKeys { it in d.rules.blocked }?.values?.sum() ?: 0
        return Summary(
            date, score(d, date, now), totals, top, missed.distinct(),
            due.count { it.id in rd.done }, due.size, due.filter { it.id !in rd.done }.map { it.title },
            wasted, goodStreak(d, if (isToday) now + Time.DAY else now),
        )
    }

    /** Evening: last hour of the planned day, or later. Morning: yesterday's, until seen. */
    fun summaryDate(d: AppData, now: Long): LocalDate? {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        val m = Time.minuteOfDay(now, zone)
        fun seen(date: LocalDate) = d.days.firstOrNull { it.date == date.toString() }?.summarySeen == true
        if (m >= d.settings.dayEndMin - 60 && !seen(today)) return today
        val y = today.minusDays(1)
        if (m < 12 * 60 && !seen(y) && (d.sessions.any { Time.dateOf(it.start, zone) == y } || RoutinePolicy.day(d, y).done.isNotEmpty())) return y
        return null
    }

    /** "Lab's done early: 45 minutes free." The best task for the time, after the plan re-flows. */
    fun fill(d: AppData, now: Long, freeMin: Int): Task? =
        Scorer.rank(d.liveTasks().filter { it.status == TaskStatus.TODO && it.kind != TaskKind.WAITING }, now, freeMin, d.todayPlan?.energy ?: d.settings.energy)
            .firstOrNull()?.first

    /**
     * After you went over: hot or exam work that no longer fits before the day ends, and today's protected blocks
     * still ahead (date night, Sunday off). The app never takes protected time on its own; it asks.
     */
    data class Squeeze(val unplaced: List<Task>, val protectedAhead: List<PlanBlock>)

    fun squeeze(d: AppData, now: Long): Squeeze {
        val plan = d.todayPlan ?: return Squeeze(emptyList(), emptyList())
        val placed = plan.blocks.filter { it.kind == BlockKind.TASK && it.end > now }.mapNotNull { it.taskId }.toSet()
        val urgent = d.liveTasks().filter { t ->
            t.status == TaskStatus.TODO && t.dueAt != null && t.dueAt < now + 36 * Time.HOUR && t.dueAt > now &&
                (Heat.deadlineHot(t, now) || t.hot || t.sourceKey?.startsWith("exam:") == true) && t.id !in placed
        }
        val prot = plan.blocks.filter { it.protected && it.start > now }
        return Squeeze(urgent, prot)
    }
}
