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

/**
 * Keeps a stable plan for today (generated once, re-planned from "now" on changes) plus draft
 * plans for the next 6 days. Work placed on earlier days is carried forward, so the week doesn't
 * book the same hours twice.
 */
object Planning {
    private val planner = DayPlanner()
    const val WEEK_DAYS = 6

    fun ensureToday(d: AppData, now: Long): AppData {
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId))
        if (d.todayPlan?.date == date.toString() && d.weekPlan.firstOrNull()?.date == date.plusDays(1).toString()) return d
        if (d.todayPlan?.date == date.toString()) return withWeek(d, now)
        val withGenerated = Generated.makeupTasks(d, date, now)
        return withWeek(withGenerated.copy(todayPlan = planner.plan(date, withGenerated, now)), now)
    }

    /** Re-plan from now on, keeping the task blocks that already happened today. */
    fun replan(d: AppData, now: Long): AppData {
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId))
        val x = Generated.makeupTasks(d, date, now)
        val fresh = planner.plan(date, x, now)
        val old = x.todayPlan?.takeIf { it.date == date.toString() }
        val past = old?.blocks?.filter { (it.kind == BlockKind.TASK || it.kind == BlockKind.BREAK) && it.end <= now }.orEmpty()
        val blocks = (fresh.blocks + past).sortedWith(compareBy({ it.start }, { it.kind.ordinal }))
        return withWeek(x.copy(todayPlan = fresh.copy(blocks = blocks)), now)
    }

    /** Drafts for tomorrow onward, carrying forward what's already placed. Practice resets on Mondays. */
    fun withWeek(d: AppData, now: Long): AppData {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        val carried = HashMap(d.todayPlan?.allotted.orEmpty())
        val practiceIds = d.tasks.filter { it.kind == com.maven.notafraud.core.model.TaskKind.PRACTICE }.map { it.id }.toSet()
        val week = mutableListOf<DayPlan>()
        for (i in 1..WEEK_DAYS) {
            val date = today.plusDays(i.toLong())
            if (date.dayOfWeek == java.time.DayOfWeek.MONDAY) practiceIds.forEach { carried.remove(it) }
            val plan = planner.plan(date, d, Time.at(date, 0, zone), carried)
            plan.allotted.forEach { (k, v) -> carried[k] = (carried[k] ?: 0) + v }
            week += plan
        }
        return d.copy(weekPlan = week)
    }

    /** Today's plan or a draft day from the week. */
    fun dayPlan(d: AppData, date: LocalDate): DayPlan? =
        if (d.todayPlan?.date == date.toString()) d.todayPlan else d.weekPlan.firstOrNull { it.date == date.toString() }

    fun preview(d: AppData, date: LocalDate): DayPlan {
        dayPlan(d, date)?.let { return it }
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
            if (x.tasks.any { it.sourceKey == key } || key in x.ignoredSourceKeys) continue
            if (date.toString() in c.madeUpDates) continue
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
                d.tasks.none { it.sourceKey == "exam:${e.uid}" } && "exam:${e.uid}" !in d.ignoredSourceKeys &&
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

    /** Course number in a title ("HW 5 [ME-3525-01]" -> "3525") matched to a project with the same number. */
    fun projectForTitle(d: AppData, title: String): String? {
        val nums = Regex("""\b(\d{4})\b""").findAll(title.replace('-', ' ')).map { it.groupValues[1] }.toList()
        val byNum = d.projects.filter { !it.archived }.firstOrNull { p -> nums.any { n -> p.name.contains(n) } }
        if (byNum != null) return byNum.id
        val words = title.lowercase()
        return d.projects.filter { !it.archived }.firstOrNull { p ->
            p.name.lowercase().split(' ').any { w -> w.length > 4 && words.contains(w) }
        }?.id
    }

    /** Exams in calendars get study tasks without asking (Settings.autoExamTasks). */
    fun autoExams(d: AppData, now: Long): AppData {
        if (!d.settings.autoExamTasks) return d
        val found = examCandidates(d, now)
        if (found.isEmpty()) return d
        return d.copy(tasks = d.tasks + found.map { studyTaskFor(d, it, now) })
    }

    /**
     * Deadline calendars (Canvas): each entry is a task due at its time. Re-sync updates titles and
     * due dates; entries removed from the calendar remove untouched tasks.
     */
    fun syncDeadlines(d: AppData, deadlines: List<CalEvent>, now: Long): AppData {
        val zone = Time.zone(d.settings.zoneId)
        fun key(e: CalEvent) = "deadline:" + e.uid
        fun due(e: CalEvent) = if (e.allDay) Time.at(Time.dateOf(e.start, zone), 23 * 60 + 59, zone) else e.start
        val byKey = deadlines.associateBy(::key)
        var tasks = d.tasks.mapNotNull { t ->
            val k = t.sourceKey ?: return@mapNotNull t
            if (!k.startsWith("deadline:")) return@mapNotNull t
            val e = byKey[k]
            when {
                e != null -> if (t.title != e.title || t.dueAt != due(e)) t.copy(title = e.title, dueAt = due(e)) else t
                t.status == com.maven.notafraud.core.model.TaskStatus.TODO && t.loggedMin == 0 -> null
                else -> t
            }
        }
        val have = tasks.mapNotNull { it.sourceKey }.toSet()
        for ((k, e) in byKey) {
            if (k in have || k in d.ignoredSourceKeys) continue
            if (due(e) < now - Time.DAY) continue
            tasks = tasks + Task(
                title = e.title,
                projectId = projectForTitle(d, e.title),
                estMin = 60,
                dueAt = due(e),
                priority = Priority.NORMAL,
                energy = Energy.MED,
                minBlockMin = 30,
                notBefore = due(e) - 7 * Time.DAY,
                createdAt = now,
                sourceKey = k,
            )
        }
        return d.copy(tasks = tasks)
    }

    /**
     * Tests calendar: every entry gets a study task (Settings.studyMinPerTest, may start studyLeadDays before),
     * keyed by the calendar event so moving the test moves the deadline and deleting it removes untouched studying.
     */
    fun syncTests(d: AppData, tests: List<CalEvent>, now: Long): AppData {
        val s = d.settings
        fun key(e: CalEvent) = "test:" + e.uid
        val byKey = tests.filter { it.start > now - Time.DAY }.associateBy(::key)
        var tasks = d.tasks.mapNotNull { t ->
            val k = t.sourceKey ?: return@mapNotNull t
            if (!k.startsWith("test:")) return@mapNotNull t
            val e = byKey[k]
            when {
                e != null -> {
                    val title = "Study: ${e.title}"
                    if (t.title != title || t.dueAt != e.start) t.copy(title = title, dueAt = e.start, notBefore = e.start - s.studyLeadDays * Time.DAY) else t
                }
                t.status == com.maven.notafraud.core.model.TaskStatus.TODO && t.loggedMin == 0 -> null
                else -> t
            }
        }
        val have = tasks.mapNotNull { it.sourceKey }.toSet()
        for ((k, e) in byKey) {
            if (k in have || k in d.ignoredSourceKeys || e.start < now) continue
            tasks = tasks + studyTaskFor(d, e, now, s.studyMinPerTest).copy(
                projectId = projectForTitle(d, e.title) ?: studyTaskFor(d, e, now).projectId,
                notBefore = e.start - s.studyLeadDays * Time.DAY,
                sourceKey = k,
            )
        }
        return d.copy(tasks = tasks)
    }
}
