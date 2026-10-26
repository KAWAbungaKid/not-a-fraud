package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import kotlin.math.roundToInt

/**
 * Homework calendars (Canvas): every entry is an assignment due then.
 *  - default length [Settings.homeworkMin] (2 h); change any one (Plan or Tasks) and the sync keeps your number;
 *  - at most [Settings.homeworkDailyMaxMin] a day on one assignment, so a big one spreads over days
 *    (more per day only when its target date needs it);
 *  - aims to be done [Settings.homeworkAheadDays] early ([Task.targetAt]), which is what builds and keeps "ahead".
 */
object Homework {
    const val PREFIX = "homework:"

    fun isHomework(t: Task) = t.sourceKey?.startsWith(PREFIX) == true

    private fun due(d: AppData, e: CalEvent): Long {
        val zone = Time.zone(d.settings.zoneId)
        return if (e.allDay) Time.at(java.time.Instant.ofEpochMilli(e.start).atZone(java.time.ZoneOffset.UTC).toLocalDate(), 23 * 60 + 59, zone) else e.start
    }

    fun target(d: AppData, due: Long) = due - d.settings.homeworkAheadDays * Time.DAY

    fun sync(d: AppData, entries: List<CalEvent>, now: Long): AppData {
        val s = d.settings
        val byKey = entries.associateBy { PREFIX + it.uid }
        var tasks = d.tasks.mapNotNull { t ->
            val k = t.sourceKey ?: return@mapNotNull t
            if (!k.startsWith(PREFIX)) return@mapNotNull t
            val e = byKey[k]
            when {
                e != null -> {
                    val du = due(d, e)
                    val fresh = t.copy(title = e.title, dueAt = du, targetAt = target(d, du), notBefore = du - s.homeworkLeadDays * Time.DAY, dailyCapMin = s.homeworkDailyMaxMin)
                    if (fresh != t) fresh else t
                }
                t.status == TaskStatus.TODO && t.loggedMin == 0 -> null
                else -> t
            }
        }
        val have = tasks.mapNotNull { it.sourceKey }.toSet()
        for ((k, e) in byKey) {
            if (k in have || k in d.ignoredSourceKeys) continue
            val du = due(d, e)
            if (du < now - Time.DAY) continue
            tasks = tasks + Task(
                title = e.title,
                projectId = Generated.projectForTitle(d, e.title),
                estMin = s.homeworkMin,
                dueAt = du,
                targetAt = target(d, du),
                dailyCapMin = s.homeworkDailyMaxMin,
                priority = Priority.HIGH,
                energy = Energy.MED,
                minBlockMin = 30,
                maxBlockMin = 90,
                notBefore = du - s.homeworkLeadDays * Time.DAY,
                createdAt = now,
                sourceKey = k,
            )
        }
        return d.copy(tasks = tasks)
    }

    data class Ahead(
        /** Days until the next unfinished assignment is due (null = nothing due in the next 2 weeks). */
        val days: Double?,
        val goalDays: Int,
        /** Work left on everything due before the goal line (now + goal days). */
        val behindMin: Int,
        val next: Task?,
        val openCount: Int,
    ) {
        val onTrack: Boolean get() = days == null || days >= goalDays
    }

    fun ahead(d: AppData, now: Long): Ahead {
        val open = d.tasks.filter { isHomework(it) && it.status == TaskStatus.TODO && (it.dueAt ?: 0) > now - Time.DAY && (it.dueAt ?: 0) < now + 14 * Time.DAY }
            .sortedBy { it.dueAt }
        val goal = d.settings.homeworkAheadDays
        val next = open.firstOrNull()
        val days = next?.dueAt?.let { ((it - now) / Time.DAY.toDouble() * 10).roundToInt() / 10.0 }
        val behind = open.filter { (it.dueAt ?: 0) < now + goal * Time.DAY }.sumOf { com.maven.notafraud.core.score.Scorer.minutesLeft(it) }
        return Ahead(days, goal, behind, next, open.size)
    }
}
