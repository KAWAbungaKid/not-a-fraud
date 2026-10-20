package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Keeps a stable plan for today: generated once per day, regenerated on demand from "now" forward. */
object Planning {
    private val planner = DayPlanner()

    fun ensureToday(d: AppData, now: Long): AppData {
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId))
        if (d.todayPlan?.date == date.toString()) return d
        val withGenerated = Generated.makeupTasks(d, date, now)
        return withGenerated.copy(todayPlan = planner.plan(date, withGenerated, now))
    }

    /** Re-plan from now on, keeping the task blocks that already happened today. */
    fun replan(d: AppData, now: Long): AppData {
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId))
        val x = Generated.makeupTasks(d, date, now)
        val fresh = planner.plan(date, x, now)
        val old = x.todayPlan?.takeIf { it.date == date.toString() }
        val past = old?.blocks?.filter { (it.kind == BlockKind.TASK || it.kind == BlockKind.BREAK) && it.end <= now }.orEmpty()
        val blocks = (fresh.blocks + past).sortedWith(compareBy({ it.start }, { it.kind.ordinal }))
        return x.copy(todayPlan = fresh.copy(blocks = blocks))
    }

    fun preview(d: AppData, date: LocalDate): DayPlan {
        val zone = Time.zone(d.settings.zoneId)
        return planner.plan(date, d, Time.at(date, 0, zone))
    }

    fun nowState(d: AppData, now: Long): NowState {
        val withPlan = ensureToday(d, now)
        return NowResolver.resolve(withPlan.todayPlan!!, withPlan, now)
    }
}

/** Tasks the app creates on its own, each exactly once (keyed by [Task.sourceKey]). */
object Generated {
    private val md = DateTimeFormatter.ofPattern("EEE M/d", Locale.US)

    /**
     * Trading overlaps a class (ME 3411 on Tue/Thu): Maven trades and makes the class up later.
     * Creates a "make up" task for each such class on [date], due two days later.
     */
    fun makeupTasks(d: AppData, date: LocalDate, now: Long): AppData {
        val zone = Time.zone(d.settings.zoneId)
        val todays = Anchors.on(date, d.anchors)
        val markets = todays.filter { it.kind == AnchorKind.MARKET }
        if (markets.isEmpty()) return d
        val classes = todays.filter { c -> c.kind == AnchorKind.CLASS && markets.any { m -> m.startMin < c.endMin && c.startMin < m.endMin } }
        var x = d
        for (c in classes) {
            val key = "makeup:${c.title}:$date"
            if (x.tasks.any { it.sourceKey == key }) continue
            val code = c.title.split(' ').take(2).joinToString(" ")
            val project = x.projects.firstOrNull { !it.archived && it.name.startsWith(code) }
            x = x.copy(
                tasks = x.tasks + Task(
                    title = "Make up $code class (${date.format(md)})",
                    projectId = project?.id,
                    estMin = c.endMin - c.startMin,
                    dueAt = Time.at(date.plusDays(2), 21 * 60, zone),
                    priority = Priority.HIGH,
                    energy = Energy.MED,
                    minBlockMin = 30,
                    createdAt = now,
                    sourceKey = key,
                )
            )
        }
        return x
    }

    private val EXAM = Regex("""\b(exam|test|midterm|final|quiz)\b""", RegexOption.IGNORE_CASE)

    /** Calendar events that look like exams and have no study task yet. */
    fun examCandidates(d: AppData, now: Long, daysAhead: Int = 35): List<CalEvent> =
        d.events.filter { e ->
            e.start > now && e.start < now + daysAhead * Time.DAY &&
                EXAM.containsMatchIn(e.title) && e.uid !in d.ignoredExamUids &&
                d.tasks.none { it.sourceKey == "exam:${e.uid}" } &&
                // An open study task due within a day of the event already covers it.
                d.tasks.none { t ->
                    t.status == com.maven.notafraud.core.model.TaskStatus.TODO && t.dueAt != null &&
                        kotlin.math.abs(t.dueAt - e.start) < Time.DAY && EXAM.containsMatchIn(t.title)
                }
        }.sortedBy { it.start }

    fun studyTaskFor(d: AppData, e: CalEvent, now: Long, estMin: Int = 360): Task {
        val words = e.title.lowercase()
        val project = d.projects.filter { !it.archived }.firstOrNull { p ->
            val code = p.name.split(' ').take(2).joinToString(" ").lowercase()
            code.length >= 4 && (words.contains(code) || p.name.lowercase().split(' ').any { w -> w.length > 4 && words.contains(w) })
        }
        return Task(
            title = "Study: ${e.title}",
            projectId = project?.id,
            estMin = estMin,
            dueAt = e.start,
            priority = Priority.HIGH,
            energy = Energy.HIGH,
            minBlockMin = 45,
            notBefore = e.start - 10 * Time.DAY,
            createdAt = now,
            sourceKey = "exam:${e.uid}",
        )
    }
}
