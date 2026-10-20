package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.DayContext
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.score.Scorer
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class BlockKind { ANCHOR, EVENT, TASK, BREAK }

@Serializable
data class PlanBlock(
    val start: Long,
    val end: Long,
    val kind: BlockKind,
    val title: String,
    val taskId: String? = null,
    val projectId: String? = null,
    val anchorKind: AnchorKind? = null,
    val protected: Boolean = false,
    val mode: com.maven.notafraud.core.model.AnchorMode? = null,
    /** One of the day's "pick 3". */
    val picked: Boolean = false,
    /** Deadline closing in (or pinned hot). */
    val hot: Boolean = false,
    val routineId: String? = null,
) {
    val minutes: Int get() = ((end - start) / Time.MIN).toInt()
    fun contains(t: Long) = t in start until end
}

@Serializable
data class DayPlan(
    val date: String,
    val blocks: List<PlanBlock>,
    val roughDay: Boolean,
    val focusCapMin: Int?,
    val energy: Energy,
    val generatedAt: Long,
    val note: String = "",
) {
    val boundaries: List<Long> get() = blocks.flatMap { listOf(it.start, it.end) }.distinct().sorted()
}

/** Which anchors apply on a given date. */
object Anchors {
    fun on(date: LocalDate, anchors: List<Anchor>): List<Anchor> {
        val iso = date.toString()
        val dow = date.dayOfWeek.value
        val dated = anchors.filter { it.enabled && iso in it.dates }
        val recurring = anchors.filter { a ->
            a.enabled && a.dates.isEmpty() && dow in a.days && iso !in a.skipDates &&
                (a.fromDate == null || iso >= a.fromDate) && (a.untilDate == null || iso <= a.untilDate)
        }.filterNot { r ->
            r.yieldsToDated && dated.any { d -> d.startMin < r.endMin && r.startMin < d.endMin }
        }
        return (dated + recurring).sortedBy { it.startMin }
    }
}

object RoughDay {
    fun isRough(ctx: DayContext?, badSleepHours: Double): Boolean =
        ctx != null && (ctx.markedRough || (ctx.sleepHours != null && ctx.sleepHours < badSleepHours))
}

/**
 * Generates a day plan: fixed anchors and calendar events first, then fills the free
 * windows with the best-scoring tasks.
 *
 * Rough day (bad sleep or marked by hand): energy drops to LOW, chunks shrink to 50 min,
 * and total focus time is capped at 4 hours. The plan gets lighter rather than the user
 * getting yelled at.
 */
class DayPlanner(
    private val minWindowMin: Int = 15,
    private val roughChunkMin: Int = 50,
    private val roughFocusCapMin: Int = 240,
) {

    fun plan(date: LocalDate, data: AppData, now: Long): DayPlan {
        val s = data.settings
        val zone: ZoneId = Time.zone(s.zoneId)
        val ctx = data.days.firstOrNull { it.date == date.toString() }
        val rough = RoughDay.isRough(ctx, s.badSleepHours)
        val energy = if (rough) Energy.LOW else s.energy
        val chunkCap = if (rough) min(roughChunkMin, s.maxChunkMin) else s.maxChunkMin
        val focusCap: Int? = if (rough) roughFocusCapMin else null

        val dayStart = Time.at(date, s.dayStartMin, zone)
        val dayEnd = Time.at(date, s.dayEndMin, zone)

        val open = data.tasks.filter { it.status == TaskStatus.TODO }
        val hotTasks = open.filter { Heat.deadlineHot(it, now) }
        val picks = data.picks[date.toString()].orEmpty().toSet()

        val fixed = mutableListOf<PlanBlock>()
        val gaveWay = mutableListOf<String>()
        for (a in Anchors.on(date, data.anchors)) {
            if (hotTasks.isNotEmpty() && a.yieldsToHot && a.mode != com.maven.notafraud.core.model.AnchorMode.HARD) {
                gaveWay += a.title
                continue
            }
            fixed += PlanBlock(
                start = Time.at(date, a.startMin, zone),
                end = Time.at(date, a.endMin, zone),
                kind = BlockKind.ANCHOR,
                title = a.title,
                anchorKind = a.kind,
                protected = a.protected,
                mode = a.mode,
            )
        }
        for (r in data.routine) {
            if (r.reserveMin <= 0 || !r.enabled || date.dayOfWeek.value !in r.days) continue
            if (rough && r.weeklyTarget != null) continue
            fixed += PlanBlock(
                start = Time.at(date, r.startMin, zone),
                end = Time.at(date, r.startMin + r.reserveMin, zone),
                kind = BlockKind.ANCHOR,
                title = r.title,
                anchorKind = AnchorKind.ROUTINE,
                mode = com.maven.notafraud.core.model.AnchorMode.HABIT,
                routineId = r.id,
            )
        }
        val dayStartMidnight = Time.at(date, 0, zone)
        val nextMidnight = Time.at(date.plusDays(1), 0, zone)
        for (e in data.events) {
            if (e.allDay) continue
            if (e.end <= dayStartMidnight || e.start >= nextMidnight) continue
            fixed += PlanBlock(
                start = max(e.start, dayStartMidnight),
                end = min(e.end, nextMidnight),
                kind = BlockKind.EVENT,
                title = e.title,
            )
        }
        fixed.sortBy { it.start }

        // Busy intervals (merged) inside the planning window. Containers are filled separately.
        val from = max(dayStart, Time.roundUp(now, 5))
        val isContainer = { b: PlanBlock -> b.mode == com.maven.notafraud.core.model.AnchorMode.CONTAINER }
        val busy = merge(fixed.map { it.start to it.end })
        val windows = freeWindows(from, dayEnd, busy).filter { (a, b) -> (b - a) / Time.MIN >= minWindowMin }

        val taskBlocks = mutableListOf<PlanBlock>()
        val allotted = HashMap<String, Int>()
        var focusUsed = 0

        fun effective(task: Task, t: Long, w: Int, e: Energy): Double {
            val virtual = task.copy(loggedMin = task.loggedMin + (allotted[task.id] ?: 0))
            var sc = Scorer.score(virtual, t, w, e)
            if (task.id in picks) sc = sc * 2 + 40
            if (task.hot) sc *= 1.5
            if (Heat.deadlineHot(task, t)) sc *= 1.3
            return sc
        }
        fun block(t: Long, len: Int, task: Task) = PlanBlock(
            start = t, end = t + len * Time.MIN, kind = BlockKind.TASK, title = task.title,
            taskId = task.id, projectId = task.projectId, picked = task.id in picks, hot = task.hot || Heat.deadlineHot(task, t),
        )

        windowLoop@ for ((wStart, wEnd) in windows) {
            var t = wStart
            while ((wEnd - t) / Time.MIN >= minWindowMin) {
                val w = ((wEnd - t) / Time.MIN).toInt()
                val capLeft = focusCap?.let { it - focusUsed } ?: Int.MAX_VALUE
                if (capLeft < minWindowMin) break@windowLoop

                val pick = open.asSequence()
                    .filter { it.notBefore == null || it.notBefore <= t }
                    .map { it to need(it, allotted) }
                    .filter { (_, n) -> n >= 5 }
                    .filter { (task, n) -> w >= min(task.minBlockMin, n) }
                    .map { (task, n) -> Triple(task, n, effective(task, t, w, energy)) }
                    .maxByOrNull { it.third } ?: break

                val (task, n, _) = pick
                var len = minOf(n, w, chunkCap, capLeft)
                len = roundDown5(len).coerceAtLeast(min(5, len))
                if (len < minWindowMin && len < n) break

                taskBlocks += block(t, len, task)
                t += len * Time.MIN
                allotted[task.id] = (allotted[task.id] ?: 0) + len
                focusUsed += len

                if (len >= 45 && (wEnd - t) / Time.MIN >= s.breakMin + minWindowMin) {
                    taskBlocks += PlanBlock(t, t + s.breakMin * Time.MIN, BlockKind.BREAK, "Break: stand up, water, walk")
                    t += s.breakMin * Time.MIN
                }
            }
        }

        // Containers (craft blocks) are filled with the hottest task from their areas,
        // falling back to project-kind tasks when no area is set.
        val areaOf = data.projects.associate { it.id to it.areaId }
        val projectKind = data.projects.filter { it.kind == com.maven.notafraud.core.model.ProjectKind.PROJECT }.map { it.id }.toSet()
        for (craft in fixed.filter { isContainer(it) && it.end > from }) {
            val anchor = data.anchors.firstOrNull { it.title == craft.title && it.mode == com.maven.notafraud.core.model.AnchorMode.CONTAINER }
            val fill = anchor?.fillAreaIds.orEmpty()
            val eligible = { task: Task -> if (fill.isEmpty()) task.projectId in projectKind else areaOf[task.projectId] in fill }
            var t = max(craft.start, from)
            while ((craft.end - t) / Time.MIN >= minWindowMin) {
                val w = ((craft.end - t) / Time.MIN).toInt()
                val pick = open.asSequence()
                    .filter(eligible)
                    .filter { it.notBefore == null || it.notBefore <= t }
                    .map { it to need(it, allotted) }
                    .filter { (_, n) -> n >= 5 }
                    .map { (task, n) -> Triple(task, n, effective(task, t, w, Energy.HIGH)) }
                    .maxByOrNull { it.third } ?: break
                val (task, n, _) = pick
                val len = roundDown5(minOf(n, w)).coerceAtLeast(5)
                taskBlocks += block(t, len, task)
                t += len * Time.MIN
                allotted[task.id] = (allotted[task.id] ?: 0) + len
            }
        }

        val note = listOfNotNull(
            if (rough) buildString {
                append("Rough day")
                ctx?.sleepHours?.let { append(" (%.1fh sleep)".format(it)) }
                append(": lighter plan, ${Time.durationText(roughFocusCapMin)} focus cap, workout optional.")
            } else null,
            if (gaveWay.isNotEmpty()) "${gaveWay.distinct().joinToString()} gave way: ${hotTasks.first().title} is due soon." else null,
        ).joinToString(" ")

        return DayPlan(
            date = date.toString(),
            blocks = (fixed + taskBlocks).sortedWith(compareBy({ it.start }, { it.kind.ordinal })),
            roughDay = rough,
            focusCapMin = focusCap,
            energy = energy,
            generatedAt = now,
            note = note,
        )
    }

    private fun need(task: Task, allotted: Map<String, Int>): Int =
        Scorer.minutesLeft(task) - (allotted[task.id] ?: 0)

    private fun roundDown5(m: Int) = (m / 5) * 5

    companion object {
        fun merge(intervals: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
            if (intervals.isEmpty()) return emptyList()
            val sorted = intervals.filter { it.second > it.first }.sortedBy { it.first }
            val out = mutableListOf<Pair<Long, Long>>()
            for (iv in sorted) {
                val last = out.lastOrNull()
                if (last != null && iv.first <= last.second) out[out.size - 1] = last.first to max(last.second, iv.second)
                else out += iv
            }
            return out
        }

        fun freeWindows(from: Long, to: Long, busy: List<Pair<Long, Long>>): List<Pair<Long, Long>> {
            val out = mutableListOf<Pair<Long, Long>>()
            var cursor = from
            for ((a, b) in busy) {
                if (b <= cursor) continue
                if (a >= to) break
                if (a > cursor) out += cursor to min(a, to)
                cursor = max(cursor, b)
            }
            if (cursor < to) out += cursor to to
            return out
        }
    }
}

/** What counts as "hot": a high-priority deadline closing in. Pinned-hot tasks are boosted but don't bump habits. */
object Heat {
    /** Hot when the work left would leave less than this much slack before the deadline. */
    const val SLACK_HOURS = 24.0

    fun slackHours(task: Task, now: Long): Double? = task.dueAt?.let { (it - now) / 3_600_000.0 - Scorer.minutesLeft(task) / 60.0 }

    fun deadlineHot(task: Task, now: Long): Boolean {
        if (task.status != TaskStatus.TODO || task.priority != com.maven.notafraud.core.model.Priority.HIGH) return false
        val due = task.dueAt ?: return false
        if (due < now - 36 * 3_600_000L) return false
        val slack = slackHours(task, now) ?: return false
        return slack < SLACK_HOURS
    }
}
