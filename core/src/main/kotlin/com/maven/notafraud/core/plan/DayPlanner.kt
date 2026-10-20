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

        val fixed = mutableListOf<PlanBlock>()
        for (a in Anchors.on(date, data.anchors)) {
            fixed += PlanBlock(
                start = Time.at(date, a.startMin, zone),
                end = Time.at(date, a.endMin, zone),
                kind = BlockKind.ANCHOR,
                title = a.title,
                anchorKind = a.kind,
                protected = a.protected,
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

        // Busy intervals (merged) inside the planning window.
        val from = max(dayStart, Time.roundUp(now, 5))
        val busy = merge(fixed.map { it.start to it.end })
        val windows = freeWindows(from, dayEnd, busy).filter { (a, b) -> (b - a) / Time.MIN >= minWindowMin }

        val taskBlocks = mutableListOf<PlanBlock>()
        val allotted = HashMap<String, Int>()
        var focusUsed = 0
        val open = data.tasks.filter { it.status == TaskStatus.TODO }

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
                    .map { (task, n) ->
                        val virtual = task.copy(loggedMin = task.loggedMin + (allotted[task.id] ?: 0))
                        Triple(task, n, Scorer.score(virtual, t, w, energy))
                    }
                    .maxByOrNull { it.third } ?: break

                val (task, n, _) = pick
                var len = minOf(n, w, chunkCap, capLeft)
                len = roundDown5(len).coerceAtLeast(min(5, len))
                if (len < minWindowMin && len < n) break

                taskBlocks += PlanBlock(
                    start = t,
                    end = t + len * Time.MIN,
                    kind = BlockKind.TASK,
                    title = task.title,
                    taskId = task.id,
                    projectId = task.projectId,
                )
                t += len * Time.MIN
                allotted[task.id] = (allotted[task.id] ?: 0) + len
                focusUsed += len

                if (len >= 45 && (wEnd - t) / Time.MIN >= s.breakMin + minWindowMin) {
                    taskBlocks += PlanBlock(t, t + s.breakMin * Time.MIN, BlockKind.BREAK, "Break: stand up, water, walk")
                    t += s.breakMin * Time.MIN
                }
            }
        }

        // Craft blocks are containers: fill them with project-kind tasks (side projects, PT device, ideas).
        val craftProjects = data.projects.filter { it.kind == com.maven.notafraud.core.model.ProjectKind.PROJECT }.map { it.id }.toSet()
        for (craft in fixed.filter { it.anchorKind == AnchorKind.CRAFT && it.end > from }) {
            var t = max(craft.start, from)
            while ((craft.end - t) / Time.MIN >= minWindowMin) {
                val w = ((craft.end - t) / Time.MIN).toInt()
                val pick = open.asSequence()
                    .filter { it.projectId in craftProjects }
                    .filter { it.notBefore == null || it.notBefore <= t }
                    .map { it to need(it, allotted) }
                    .filter { (_, n) -> n >= 5 }
                    .map { (task, n) ->
                        val virtual = task.copy(loggedMin = task.loggedMin + (allotted[task.id] ?: 0))
                        Triple(task, n, Scorer.score(virtual, t, w, Energy.HIGH))
                    }
                    .maxByOrNull { it.third } ?: break
                val (task, n, _) = pick
                val len = roundDown5(minOf(n, w)).coerceAtLeast(5)
                taskBlocks += PlanBlock(t, t + len * Time.MIN, BlockKind.TASK, task.title, task.id, task.projectId)
                t += len * Time.MIN
                allotted[task.id] = (allotted[task.id] ?: 0) + len
            }
        }

        val note = buildString {
            if (rough) {
                append("Rough day")
                ctx?.sleepHours?.let { append(" (%.1fh sleep)".format(it)) }
                append(": lighter plan, ${Time.durationText(roughFocusCapMin)} focus cap, low-energy tasks first.")
            }
        }

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
