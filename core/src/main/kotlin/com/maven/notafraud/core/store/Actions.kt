package com.maven.notafraud.core.store

import com.maven.notafraud.core.model.routineFor
import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.model.ActiveSession
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.DayContext
import com.maven.notafraud.core.model.Idea
import com.maven.notafraud.core.model.MirroredMessage
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.model.UsageDay
import com.maven.notafraud.core.model.WorkSession
import com.maven.notafraud.core.plan.Time
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Every state change the UI or a service can make. Pure: AppData in, AppData out. */
object Actions {

    fun addTask(d: AppData, task: Task) = d.copy(tasks = d.tasks + task)

    fun updateTask(d: AppData, task: Task) = d.copy(tasks = d.tasks.map { if (it.id == task.id) task else it })

    /** Deleting a generated task (exam study, deadline, make-up, catch-up) remembers it so it isn't re-created. */
    fun deleteTask(d: AppData, id: String): AppData {
        val t = d.tasks.firstOrNull { it.id == id } ?: return d
        return d.copy(
            tasks = d.tasks.filterNot { it.id == id },
            ignoredSourceKeys = t.sourceKey?.let { d.ignoredSourceKeys + it } ?: d.ignoredSourceKeys,
            reviewTaskIds = d.reviewTaskIds - id,
            picks = d.picks.mapValues { (_, v) -> v - id },
        )
    }

    fun setDone(d: AppData, id: String, done: Boolean, now: Long) = d.copy(tasks = d.tasks.map {
        if (it.id != id) it
        else it.copy(status = if (done) TaskStatus.DONE else TaskStatus.TODO, doneAt = if (done) now else null)
    }).let { if (done && it.active?.taskId == id) stopSession(it, now) else it }

    fun startSession(d: AppData, taskId: String?, title: String?, now: Long): AppData {
        val stopped = if (d.active != null) stopSession(d, now) else d
        val task = taskId?.let { id -> stopped.tasks.firstOrNull { it.id == id } }
        return stopped.copy(
            active = ActiveSession(
                taskId = task?.id,
                projectId = task?.projectId,
                title = task?.title ?: title ?: "Focus session",
                start = now,
            )
        )
    }

    /** Ends the running session, logs time on the task and earns credits. */
    fun stopSession(d: AppData, now: Long): AppData {
        val a = d.active ?: return d
        val minutes = ((now - a.start) / Time.MIN).toInt()
        if (minutes < 1) return d.copy(active = null)
        val session = WorkSession(taskId = a.taskId, projectId = a.projectId, title = a.title, start = a.start, end = now, minutes = minutes)
        val tasks = d.tasks.map {
            if (it.id == a.taskId) it.copy(loggedMin = it.loggedMin + minutes, lastWorkedAt = now) else it
        }
        val ledger = Credits.earn(d.ledger, d.settings, minutes.toDouble(), d.settings.earnPerSessionMin, "Worked: ${a.title}", now)
        val logged = d.copy(active = null, sessions = d.sessions + session, tasks = tasks, ledger = ledger)
        return sportCountsAsWorkout(logged, a.taskId, minutes, now)
    }

    /** 20+ minutes on a skill in a sport tree (climbing, athletics…) checks today's workout with that sport. */
    private fun sportCountsAsWorkout(d: AppData, taskId: String?, minutes: Int, now: Long): AppData {
        if (taskId == null || minutes < d.settings.sportMin) return d
        val skill = d.skills.firstOrNull { it.practiceTaskId == taskId } ?: return d
        val tree = d.skillTrees.firstOrNull { it.id == skill.treeId && it.sport } ?: return d
        val workout = d.routineFor(com.maven.notafraud.core.model.AutoCheck.WORKOUT) ?: return d
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId)).toString()
        if (d.routineDays.firstOrNull { it.date == date }?.done?.containsKey(workout.id) == true) return d
        val note = com.maven.notafraud.core.routine.RoutinePolicy.SPORT_NOTES.firstOrNull { it.equals(tree.name, ignoreCase = true) } ?: "Sport"
        return checkRoutine(d, workout.id, now, note)
    }

    /**
     * Midweek ideas wait for the Friday craft block (STATE.md section 7): captured now,
     * promoted to a task the planner won't place before Friday 4pm.
     */
    fun captureIdea(d: AppData, text: String, now: Long): AppData = d.copy(ideas = d.ideas + Idea(text = text, createdAt = now))

    fun promoteIdea(d: AppData, ideaId: String, now: Long): AppData {
        val idea = d.ideas.firstOrNull { it.id == ideaId } ?: return d
        val zone = Time.zone(d.settings.zoneId)
        val task = Task(
            title = idea.text,
            estMin = 60,
            projectId = d.projects.firstOrNull { it.name == "Side projects" }?.id,
            notBefore = nextCraftBlock(now, zone, d.anchors),
            minBlockMin = 30,
        )
        return d.copy(
            tasks = d.tasks + task,
            ideas = d.ideas.map { if (it.id == ideaId) it.copy(promotedTaskId = task.id) else it },
        )
    }

    fun deleteIdea(d: AppData, ideaId: String) = d.copy(ideas = d.ideas.filterNot { it.id == ideaId })

    fun nextCraftBlock(now: Long, zone: java.time.ZoneId, anchors: List<com.maven.notafraud.core.model.Anchor> = emptyList()): Long {
        val today = Time.dateOf(now, zone)
        val friday = if (today.dayOfWeek == DayOfWeek.FRIDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        val startMin = anchors.firstOrNull { it.kind == com.maven.notafraud.core.model.AnchorKind.CRAFT && 5 in it.days && it.enabled }?.startMin ?: (16 * 60)
        val craftStart = Time.at(friday, startMin, zone)
        // Saturday and Sunday are already "after Friday": allow right away.
        return if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) now else craftStart
    }

    fun upsertDay(d: AppData, ctx: DayContext): AppData {
        val existing = d.days.firstOrNull { it.date == ctx.date }
        val merged = if (existing == null) ctx else existing.copy(
            sleepHours = ctx.sleepHours ?: existing.sleepHours,
            sleepStart = ctx.sleepStart ?: existing.sleepStart,
            sleepEnd = ctx.sleepEnd ?: existing.sleepEnd,
            steps = ctx.steps ?: existing.steps,
            workoutMin = ctx.workoutMin ?: existing.workoutMin,
            markedRough = ctx.markedRough || existing.markedRough,
            naps = if (ctx.naps.isNotEmpty()) ctx.naps else existing.naps,
        )
        return d.copy(days = d.days.filterNot { it.date == ctx.date } + merged)
    }

    fun setRough(d: AppData, date: LocalDate, rough: Boolean): AppData {
        val existing = d.days.firstOrNull { it.date == date.toString() } ?: DayContext(date.toString())
        return d.copy(days = d.days.filterNot { it.date == date.toString() } + existing.copy(markedRough = rough))
    }

    /** Replace all events from [source] with [events] (calendar re-sync is a full replace per source). */
    fun replaceEvents(d: AppData, source: String, events: List<CalEvent>) =
        d.copy(events = d.events.filterNot { it.source == source } + events.map { it.copy(source = source) })

    fun mergeIcs(d: AppData, events: List<CalEvent>): AppData {
        val byUid = d.events.associateBy { it.uid }.toMutableMap()
        events.forEach { byUid[it.uid] = it.copy(source = "ics") }
        return d.copy(events = byUid.values.sortedBy { it.start })
    }

    fun recordUsage(d: AppData, day: UsageDay): AppData =
        d.copy(usage = d.usage.filterNot { it.date == day.date && it.device == day.device } + day)

    fun addMessage(d: AppData, m: MirroredMessage): AppData =
        d.copy(messages = d.messages.filterNot { it.key == m.key } + m)

    fun markMessagesRead(d: AppData, pkg: String?) =
        d.copy(messages = d.messages.map { if (pkg == null || it.pkg == pkg) it.copy(read = true) else it })

    // ---- Routine ---------------------------------------------------------------------------

    private fun editDay(d: AppData, date: String, fn: (com.maven.notafraud.core.model.RoutineDay) -> com.maven.notafraud.core.model.RoutineDay): AppData {
        val existing = d.routineDays.firstOrNull { it.date == date } ?: com.maven.notafraud.core.model.RoutineDay(date)
        return d.copy(routineDays = (d.routineDays.filterNot { it.date == date } + fn(existing)).sortedBy { it.date }.takeLast(400))
    }

    fun checkRoutine(d: AppData, itemId: String, at: Long, note: String? = null): AppData {
        val date = Time.dateOf(at, Time.zone(d.settings.zoneId)).toString()
        return editDay(d, date) { day ->
            day.copy(done = day.done + (itemId to at), notes = if (note.isNullOrBlank()) day.notes - itemId else day.notes + (itemId to note))
        }
    }

    fun uncheckRoutine(d: AppData, itemId: String, date: LocalDate) =
        editDay(d, date.toString()) { it.copy(done = it.done - itemId, notes = it.notes - itemId) }

    fun markAlerted(d: AppData, itemIds: Collection<String>, at: Long): AppData {
        if (itemIds.isEmpty()) return d
        val date = Time.dateOf(at, Time.zone(d.settings.zoneId)).toString()
        return editDay(d, date) { day -> day.copy(alerted = day.alerted + itemIds.associateWith { at }) }
    }

    fun upsertRoutine(d: AppData, item: com.maven.notafraud.core.model.RoutineItem) =
        d.copy(routine = if (d.routine.any { it.id == item.id }) d.routine.map { if (it.id == item.id) item else it }
        else d.routine + item.copy(order = (d.routine.maxOfOrNull { it.order } ?: 0) + 1))

    fun deleteRoutine(d: AppData, id: String) = d.copy(routine = d.routine.filterNot { it.id == id })

    /** Health Connect fills in wake-up (sleep end) and workouts so they don't need a tap. */
    fun autoFromHealth(d: AppData, date: LocalDate): AppData {
        val ctx = d.days.firstOrNull { it.date == date.toString() } ?: return d
        val day = d.routineDays.firstOrNull { it.date == date.toString() }
        var x = d
        val wake = d.routineFor(com.maven.notafraud.core.model.AutoCheck.WAKE)
        if (wake != null && day?.done?.containsKey(wake.id) != true) {
            val woke = ctx.sleepEnd?.takeIf { Time.dateOf(it, Time.zone(d.settings.zoneId)) == date }
            if (woke != null) x = checkRoutine(x, wake.id, woke, "from your watch")
            else if (ctx.firstUnlock != null) x = checkRoutine(x, wake.id, ctx.firstUnlock, "first unlock")
        }
        val workout = d.routineFor(com.maven.notafraud.core.model.AutoCheck.WORKOUT)
        // With the workout app connected, it alone ticks the workout (not Samsung Health guesses).
        if (workout != null && d.workout == null && (ctx.workoutMin ?: 0) >= 20 && day?.done?.containsKey(workout.id) != true) {
            val zone = Time.zone(d.settings.zoneId)
            x = checkRoutine(x, workout.id, Time.at(date, 12 * 60, zone).coerceAtMost(System.currentTimeMillis()), "from Samsung Health")
        }
        return x
    }

    // ---- Pick 3 ----------------------------------------------------------------------------

    fun setPicks(d: AppData, date: LocalDate, taskIds: List<String>, now: Long): AppData {
        val withPicks = d.copy(picks = (d.picks + (date.toString() to taskIds.distinct().take(3))).filterKeys { it >= date.minusDays(14).toString() })
        val pick = d.routineFor(com.maven.notafraud.core.model.AutoCheck.PICK3)
        return if (taskIds.isNotEmpty() && pick != null) checkRoutine(withPicks, pick.id, now) else withPicks
    }

    // ---- Hierarchy -------------------------------------------------------------------------

    fun upsertGoal(d: AppData, g: com.maven.notafraud.core.model.Goal) =
        d.copy(goals = if (d.goals.any { it.id == g.id }) d.goals.map { if (it.id == g.id) g else it }
        else d.goals + g.copy(order = (d.goals.maxOfOrNull { it.order } ?: 0) + 1))

    fun deleteGoal(d: AppData, id: String) = d.copy(
        goals = d.goals.filterNot { it.id == id },
        areas = d.areas.map { it.copy(goalIds = it.goalIds - id) },
    )

    fun upsertArea(d: AppData, a: com.maven.notafraud.core.model.Area) =
        d.copy(areas = if (d.areas.any { it.id == a.id }) d.areas.map { if (it.id == a.id) a else it }
        else d.areas + a.copy(order = (d.areas.maxOfOrNull { it.order } ?: 0) + 1))

    fun deleteArea(d: AppData, id: String) = d.copy(
        areas = d.areas.filterNot { it.id == id },
        projects = d.projects.map { if (it.areaId == id) it.copy(areaId = null) else it },
    )

    fun upsertProject(d: AppData, p: com.maven.notafraud.core.model.Project) =
        d.copy(projects = if (d.projects.any { it.id == p.id }) d.projects.map { if (it.id == p.id) p else it } else d.projects + p)

    // ---- Weekly blocks ---------------------------------------------------------------------

    fun upsertAnchor(d: AppData, a: com.maven.notafraud.core.model.Anchor) =
        d.copy(anchors = if (d.anchors.any { it.id == a.id }) d.anchors.map { if (it.id == a.id) a else it } else d.anchors + a)

    fun deleteAnchor(d: AppData, id: String) = d.copy(anchors = d.anchors.filterNot { it.id == id })

    /** Replace the set of dated research-session days, keeping the session's four blocks per day. */
    fun setResearchDates(d: AppData, dates: List<String>): AppData {
        val template = d.anchors.filter { it.kind == com.maven.notafraud.core.model.AnchorKind.RESEARCH && it.dates.isNotEmpty() }
            .groupBy { it.title }.map { (_, list) -> list.first() }
        if (template.isEmpty()) return d
        val others = d.anchors.filterNot { it.kind == com.maven.notafraud.core.model.AnchorKind.RESEARCH && it.dates.isNotEmpty() }
        val fresh = dates.distinct().sorted().flatMap { date -> template.map { it.copy(id = com.maven.notafraud.core.model.newId(), dates = setOf(date)) } }
        return d.copy(anchors = others + fresh)
    }

    fun ignoreExam(d: AppData, uid: String) = d.copy(ignoredExamUids = d.ignoredExamUids + uid)

    // ---- Do later / not today ------------------------------------------------------------

    private fun defer(d: AppData, id: String, until: Long, now: Long) = d.copy(tasks = d.tasks.map {
        if (it.id == id) it.copy(notBefore = until, deferrals = (it.deferrals + now).takeLast(50)) else it
    }).let { if (it.active?.taskId == id) stopSession(it, now) else it }

    /** Off today's plan; back tomorrow. */
    fun notToday(d: AppData, id: String, now: Long): AppData {
        val zone = Time.zone(d.settings.zoneId)
        return defer(d, id, Time.at(Time.dateOf(now, zone).plusDays(1), 0, zone), now)
    }

    fun laterToday(d: AppData, id: String, at: Long, now: Long) = defer(d, id, at, now)

    fun pickDay(d: AppData, id: String, date: LocalDate, now: Long): AppData {
        val zone = Time.zone(d.settings.zoneId)
        return defer(d, id, Time.at(date, 0, zone), now)
    }

    /** Something came up: the current task steps aside for an hour and the day re-plans. */
    fun somethingCameUp(d: AppData, taskId: String?, now: Long): AppData {
        val stopped = if (d.active != null) stopSession(d, now) else d
        return if (taskId != null) defer(stopped, taskId, now + 60 * Time.MIN, now) else stopped
    }

    // ---- Skip a block and make it up -----------------------------------------------------

    /** Skip a weekly block on [date]; with [makeUp], a make-up task of the same length is created. */
    fun skipAnchor(d: AppData, anchorId: String, date: LocalDate, makeUp: Boolean, now: Long): AppData {
        val a = d.anchors.firstOrNull { it.id == anchorId } ?: return d
        val zone = Time.zone(d.settings.zoneId)
        val iso = date.toString()
        val updated = if (a.dates.isNotEmpty()) a.copy(dates = a.dates - iso, madeUpDates = if (makeUp) a.madeUpDates + iso else a.madeUpDates)
        else a.copy(skipDates = a.skipDates + iso, madeUpDates = if (makeUp) a.madeUpDates + iso else a.madeUpDates)
        var x = d.copy(anchors = d.anchors.map { if (it.id == a.id) updated else it })
        if (makeUp) {
            val key = "makeup:${a.title}:$iso"
            if (x.tasks.none { it.sourceKey == key }) {
                val code = a.title.split(' ').take(2).joinToString(" ")
                x = x.copy(tasks = x.tasks + Task(
                    title = "Make up: ${a.title} (${date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }} ${date.monthValue}/${date.dayOfMonth})",
                    projectId = x.projects.firstOrNull { !it.archived && it.name.startsWith(code) }?.id,
                    estMin = (a.endMin - a.startMin).coerceAtLeast(15),
                    dueAt = Time.at(date.plusDays(3), 21 * 60, zone),
                    priority = com.maven.notafraud.core.model.Priority.HIGH,
                    minBlockMin = 30,
                    createdAt = now,
                    sourceKey = key,
                ))
            }
        }
        return x
    }

    // ---- Importance ----------------------------------------------------------------------

    /** One priority level up (v0.7: permanent, no more week-long bumps). */
    fun bump(d: AppData, id: String, now: Long) = d.copy(tasks = d.tasks.map {
        if (it.id == id) it.copy(importance = (com.maven.notafraud.core.plan.Importance.level(it, d, now) + 1).coerceAtMost(5), bumpUntil = null) else it
    })

    fun setLevel(d: AppData, id: String, level: Int?) = d.copy(tasks = d.tasks.map { if (it.id == id) it.copy(importance = level, hot = false, bumpUntil = null) else it })

    // ---- Steps ---------------------------------------------------------------------------

    fun checkStep(d: AppData, taskId: String, stepId: String, done: Boolean, now: Long): AppData {
        val x = d.copy(tasks = d.tasks.map { t ->
            if (t.id != taskId) t else t.copy(
                steps = t.steps.map { st -> if (st.id == stepId) st.copy(doneAt = if (done) now else null) else st },
                lastWorkedAt = now,
            )
        })
        val t = x.tasks.first { it.id == taskId }
        return if (done && t.steps.all { it.doneAt != null }) setDone(x, taskId, true, now) else x
    }

    // ---- Review of old tasks -------------------------------------------------------------

    fun reviewKeep(d: AppData, id: String) = d.copy(reviewTaskIds = d.reviewTaskIds - id)
    fun reviewDone(d: AppData, id: String, now: Long) = setDone(d, id, true, now).copy(reviewTaskIds = d.reviewTaskIds - id)

    // ---- Day mode ------------------------------------------------------------------------

    fun setDayMode(d: AppData, date: LocalDate, mode: com.maven.notafraud.core.model.DayMode?, rechargeMin: Int, now: Long): AppData {
        val existing = d.days.firstOrNull { it.date == date.toString() } ?: com.maven.notafraud.core.model.DayContext(date.toString())
        val ctx = existing.copy(mode = mode, modeAt = now, rechargeMin = rechargeMin, markedRough = mode != null && mode != com.maven.notafraud.core.model.DayMode.PUSH)
        return d.copy(days = d.days.filterNot { it.date == date.toString() } + ctx)
    }

    fun recordFirstUnlock(d: AppData, at: Long): AppData {
        val zone = Time.zone(d.settings.zoneId)
        if (Time.minuteOfDay(at, zone) < 4 * 60) return d
        val date = Time.dateOf(at, zone).toString()
        val existing = d.days.firstOrNull { it.date == date }
        if (existing?.firstUnlock != null) return d
        val ctx = (existing ?: com.maven.notafraud.core.model.DayContext(date)).copy(firstUnlock = at)
        return d.copy(days = d.days.filterNot { it.date == date } + ctx)
    }

    // ---- Skills --------------------------------------------------------------------------

    fun upsertTree(d: AppData, t: com.maven.notafraud.core.model.SkillTree) =
        d.copy(skillTrees = if (d.skillTrees.any { it.id == t.id }) d.skillTrees.map { if (it.id == t.id) t else it } else d.skillTrees + t.copy(order = d.skillTrees.size))

    fun deleteTree(d: AppData, id: String) = d.copy(skillTrees = d.skillTrees.filterNot { it.id == id }, skills = d.skills.filterNot { it.treeId == id })

    fun upsertSkill(d: AppData, s: com.maven.notafraud.core.model.Skill) =
        d.copy(skills = if (d.skills.any { it.id == s.id }) d.skills.map { if (it.id == s.id) s else it } else d.skills + s)

    fun deleteSkill(d: AppData, id: String) = d.copy(skills = d.skills.filterNot { it.id == id }.map { if (it.parentId == id) it.copy(parentId = null) else it })

    /** A practice task for the skill (created once), so the planner sprinkles it into gaps and its sessions level the skill. */
    fun practiceSkill(d: AppData, skillId: String, weeklyMin: Int, now: Long): AppData {
        val s = d.skills.firstOrNull { it.id == skillId } ?: return d
        if (s.practiceTaskId != null && d.tasks.any { it.id == s.practiceTaskId }) return d
        val tree = d.skillTrees.firstOrNull { it.id == s.treeId }
        val project = d.projects.firstOrNull { it.name == "Skills" } ?: com.maven.notafraud.core.model.Project(name = "Skills", kind = com.maven.notafraud.core.model.ProjectKind.PROJECT, areaId = if (tree?.sport == true) "a-fitness" else "a-mind")
        val task = Task(
            title = "Practice: ${s.name}", projectId = project.id, kind = com.maven.notafraud.core.model.TaskKind.PRACTICE,
            weeklyTargetMin = weeklyMin, estMin = weeklyMin, minBlockMin = 15, maxBlockMin = 60, createdAt = now,
            sourceKey = "skill:${s.id}",
        )
        val withProject = if (d.projects.any { it.id == project.id }) d else d.copy(projects = d.projects + project)
        return withProject.copy(tasks = withProject.tasks + task, skills = withProject.skills.map { if (it.id == skillId) it.copy(practiceTaskId = task.id) else it })
    }

    fun addSkillMinutes(d: AppData, skillId: String, minutes: Int) =
        d.copy(skills = d.skills.map { if (it.id == skillId) it.copy(manualMin = (it.manualMin + minutes).coerceAtLeast(0)) else it })

    // ---- Weekly check-in / burnout prompt --------------------------------------------------

    fun checkIn(d: AppData, weekStart: LocalDate, ratings: Map<String, Int>, now: Long) =
        d.copy(checkIns = (d.checkIns.filterNot { it.weekStart == weekStart.toString() } + com.maven.notafraud.core.model.CheckIn(weekStart.toString(), ratings, now)).takeLast(60))

    fun markPrompted(d: AppData, date: LocalDate, now: Long): AppData {
        val ctx = d.days.firstOrNull { it.date == date.toString() } ?: com.maven.notafraud.core.model.DayContext(date.toString())
        return d.copy(days = d.days.filterNot { it.date == date.toString() } + ctx.copy(promptedAt = now))
    }

    // ---- Semester -------------------------------------------------------------------------

    /** Sets the term dates; every class block follows them (start, end, days off). */
    fun setSemester(d: AppData, sem: com.maven.notafraud.core.model.Semester): AppData = d.copy(
        semester = sem,
        anchors = d.anchors.map {
            if (it.kind == com.maven.notafraud.core.model.AnchorKind.CLASS) it.copy(fromDate = sem.start, untilDate = sem.end, skipDates = sem.offDates) else it
        },
    )

    /**
     * Adds or edits a class: its meeting blocks (one anchor per days/time pattern) plus a School project of the same name,
     * with a catch-up practice item if [catchUpMin] > 0.
     */
    fun saveClass(d: AppData, name: String, meetings: List<com.maven.notafraud.core.model.Anchor>, oldName: String?, catchUpMin: Int, now: Long): AppData {
        val sem = d.semester
        val keep = d.anchors.filterNot { it.kind == com.maven.notafraud.core.model.AnchorKind.CLASS && (it.title == oldName || it.title == name) }
        val fresh = meetings.map {
            it.copy(title = name, kind = com.maven.notafraud.core.model.AnchorKind.CLASS, mode = com.maven.notafraud.core.model.AnchorMode.HARD,
                fromDate = sem?.start ?: it.fromDate, untilDate = sem?.end ?: it.untilDate, skipDates = sem?.offDates ?: it.skipDates, dates = emptySet())
        }
        var x = d.copy(anchors = keep + fresh)
        val existing = classProject(x, oldName ?: name) ?: classProject(x, name)
        val school = x.areas.firstOrNull { it.stat == "mind" && it.name.contains("school", true) } ?: x.areas.firstOrNull { it.name.contains("school", true) }
        val project = existing ?: com.maven.notafraud.core.model.Project(name = name, kind = com.maven.notafraud.core.model.ProjectKind.SCHOOL, areaId = school?.id)
        x = x.copy(projects = if (existing != null) x.projects.map { if (it.id == existing.id) project else it } else x.projects + project)
        val key = "catchup:${project.id}"
        val cu = x.tasks.firstOrNull { it.sourceKey == key }
        x = when {
            catchUpMin > 0 && cu == null -> x.copy(tasks = x.tasks + Task(
                title = "Study: $name", projectId = project.id, kind = com.maven.notafraud.core.model.TaskKind.PRACTICE, estMin = catchUpMin,
                weeklyTargetMin = catchUpMin, minBlockMin = 30, maxBlockMin = 90, energy = com.maven.notafraud.core.model.Energy.HIGH, createdAt = now, sourceKey = key,
            ))
            catchUpMin > 0 && cu != null -> x.copy(tasks = x.tasks.map { if (it.id == cu.id) it.copy(weeklyTargetMin = catchUpMin, estMin = catchUpMin) else it })
            catchUpMin == 0 && cu != null && cu.status == com.maven.notafraud.core.model.TaskStatus.TODO -> x.copy(tasks = x.tasks.filterNot { it.id == cu.id })
            else -> x
        }
        return x
    }

    /** The project for a class: same name, or a School project with the same course number ("ME 3521 ..." ↔ "ME 3521 Applied Thermo"). */
    fun classProject(d: AppData, name: String): com.maven.notafraud.core.model.Project? {
        val live = d.projects.filter { !it.archived }
        live.firstOrNull { it.name == name }?.let { return it }
        val num = Regex("""\b(\d{4})\b""").find(name)?.groupValues?.get(1) ?: return null
        return live.firstOrNull { it.kind == com.maven.notafraud.core.model.ProjectKind.SCHOOL && Regex("""\b$num\b""").containsMatchIn(it.name) }
    }

    fun deleteClass(d: AppData, name: String): AppData =
        d.copy(anchors = d.anchors.filterNot { it.kind == com.maven.notafraud.core.model.AnchorKind.CLASS && it.title == name })

    /**
     * Semester over: class blocks go, the chosen class projects are archived (their tasks and logged time stay,
     * but nothing in them gets planned), open weekly study items in them are closed.
     */
    fun endSemester(d: AppData, archiveProjectIds: Set<String>, now: Long): AppData = d.copy(
        semester = null,
        anchors = d.anchors.filterNot { it.kind == com.maven.notafraud.core.model.AnchorKind.CLASS },
        projects = d.projects.map { if (it.id in archiveProjectIds) it.copy(archived = true) else it },
        tasks = d.tasks.map {
            if (it.projectId in archiveProjectIds && it.kind == com.maven.notafraud.core.model.TaskKind.PRACTICE && it.status == com.maven.notafraud.core.model.TaskStatus.TODO)
                it.copy(status = com.maven.notafraud.core.model.TaskStatus.DONE, doneAt = now) else it
        },
        todayPlan = null,
        weekPlan = emptyList(),
    )

    // ---- To-do list -----------------------------------------------------------------------

    /** Puts a task on the to-do list at a level: ASAP / on [day] / between [from] and [to] / someday. */
    fun setHorizon(d: AppData, id: String, h: com.maven.notafraud.core.model.Horizon?, day: LocalDate?, from: LocalDate?, to: LocalDate?): AppData {
        val zone = Time.zone(d.settings.zoneId)
        return d.copy(tasks = d.tasks.map { t ->
            if (t.id != id) t else when (h) {
                com.maven.notafraud.core.model.Horizon.ON_DAY -> t.copy(horizon = h, notBefore = Time.at(day!!, 0, zone), dueAt = Time.at(day, 23 * 60 + 59, zone))
                com.maven.notafraud.core.model.Horizon.WITHIN -> t.copy(horizon = h, notBefore = Time.at(from!!, 0, zone), dueAt = Time.at(to!!, 23 * 60 + 59, zone))
                com.maven.notafraud.core.model.Horizon.ASAP, com.maven.notafraud.core.model.Horizon.SOMEDAY ->
                    t.copy(horizon = h, notBefore = null, dueAt = if (t.horizon == com.maven.notafraud.core.model.Horizon.ON_DAY || t.horizon == com.maven.notafraud.core.model.Horizon.WITHIN) null else t.dueAt)
                null -> t.copy(horizon = null)
            }
        })
    }

    fun addTodo(d: AppData, title: String, h: com.maven.notafraud.core.model.Horizon, day: LocalDate?, from: LocalDate?, to: LocalDate?, estMin: Int, now: Long): AppData {
        val t = Task(title = title.trim(), estMin = estMin, minBlockMin = minOf(15, estMin), energy = com.maven.notafraud.core.model.Energy.MED, createdAt = now, horizon = h)
        return setHorizon(d.copy(tasks = d.tasks + t), t.id, h, day, from, to)
    }

    // ---- Block suggestions -----------------------------------------------------------------

    /** Apps averaging [Settings.suggestBlockMin]+ min/day over the last 7 days that aren't blocked, focus or dismissed. */
    fun blockSuggestions(d: AppData, today: LocalDate, ignore: Set<String>): List<Triple<String, String, Int>> {
        val days = (0..6).map { today.minusDays(it.toLong()).toString() }.toSet()
        val use = d.usage.filter { it.device == "phone" && it.date in days }
        val total = HashMap<String, Int>()
        val labels = HashMap<String, String>()
        for (u in use) { u.minutesByApp.forEach { (k, v) -> total[k] = (total[k] ?: 0) + v }; labels.putAll(u.labels) }
        val n = use.size.coerceAtLeast(1)
        return total.map { (k, v) -> Triple(k, labels[k] ?: k, v / n) }
            .filter { it.third >= d.settings.suggestBlockMin }
            .filter { it.first !in d.rules.blocked && it.first !in d.rules.focus && it.first !in d.rules.alwaysAllowed && it.first !in d.settings.notSuggest && it.first !in ignore }
            .sortedByDescending { it.third }
    }
}
