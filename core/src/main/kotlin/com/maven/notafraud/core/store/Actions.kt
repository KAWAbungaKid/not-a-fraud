package com.maven.notafraud.core.store

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

    fun deleteTask(d: AppData, id: String) = d.copy(tasks = d.tasks.filterNot { it.id == id })

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
        return d.copy(active = null, sessions = d.sessions + session, tasks = tasks, ledger = ledger)
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
            notBefore = nextCraftBlock(now, zone),
            minBlockMin = 30,
        )
        return d.copy(
            tasks = d.tasks + task,
            ideas = d.ideas.map { if (it.id == ideaId) it.copy(promotedTaskId = task.id) else it },
        )
    }

    fun deleteIdea(d: AppData, ideaId: String) = d.copy(ideas = d.ideas.filterNot { it.id == ideaId })

    fun nextCraftBlock(now: Long, zone: java.time.ZoneId): Long {
        val today = Time.dateOf(now, zone)
        val friday = if (today.dayOfWeek == DayOfWeek.FRIDAY) today else today.with(TemporalAdjusters.next(DayOfWeek.FRIDAY))
        val craftStart = Time.at(friday, 16 * 60, zone)
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
}
