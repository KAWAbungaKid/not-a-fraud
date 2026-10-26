package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.liveTasks
import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AnchorMode
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.DayContext
import com.maven.notafraud.core.model.DayMode
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskKind
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.score.Scorer
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class BlockKind { ANCHOR, EVENT, TASK, BREAK, BACKGROUND, RECHARGE }

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
    val mode: AnchorMode? = null,
    /** One of the day's "pick 3". */
    val picked: Boolean = false,
    /** Deadline closing in (or pinned hot). */
    val hot: Boolean = false,
    val routineId: String? = null,
    val anchorId: String? = null,
    /** STEPS tasks: which step this block is. */
    val stepId: String? = null,
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
    val mode: DayMode? = null,
    /** Minutes placed per task in this plan (week planning carries these forward). */
    val allotted: Map<String, Int> = emptyMap(),
    /** Planned for today earlier, but no longer fits after a re-plan ("lost the slot"). */
    val dropped: List<String> = emptyList(),
    /** Due within the week but the week doesn't have room for all of it. */
    val atRisk: List<String> = emptyList(),
) {
    /** Instants worth waking for: block starts/ends (background ends too — "washer's done"). */
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

/** Importance 1–5 → score multiplier. Task overrides area; a bump adds one for a week. */
object Importance {
    private val MULT = doubleArrayOf(0.6, 0.8, 1.0, 1.3, 1.6)

    /** [lowStatAreas]: areas whose stat is low get +1 (STATE.md section 11: stats set the baseline, tasks override). */
    fun level(task: Task, d: AppData, now: Long, lowStatAreas: Set<String> = emptySet()): Int {
        val areaId = d.projects.firstOrNull { it.id == task.projectId }?.areaId
        val area = areaId?.let { a -> d.areas.firstOrNull { it.id == a } }
        val base = task.importance ?: ((area?.importance ?: 3) + if (areaId != null && areaId in lowStatAreas) 1 else 0)
        val bump = if ((task.bumpUntil ?: 0) > now) 1 else 0
        return (base + bump).coerceIn(1, 5)
    }

    fun mult(task: Task, d: AppData, now: Long, lowStatAreas: Set<String> = emptySet()) = MULT[level(task, d, now, lowStatAreas) - 1]
}

/** Practice items: time-in toward a weekly target. */
object Practice {
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Minutes logged on [task] in the week containing [date]. */
    fun doneThisWeek(task: Task, d: AppData, date: LocalDate): Int {
        val zone = Time.zone(d.settings.zoneId)
        val from = Time.at(weekStart(date), 0, zone)
        val to = Time.at(weekStart(date).plusDays(7), 0, zone)
        return d.sessions.filter { it.taskId == task.id && it.start in from until to }.sumOf { it.minutes }
    }

    fun need(task: Task, d: AppData, date: LocalDate, carried: Int): Int =
        max(0, (task.weeklyTargetMin ?: 0) - doneThisWeek(task, d, date) - carried)

    /** Pressure rises as the week runs out with time still owed. */
    fun urgency(need: Int, target: Int, date: LocalDate): Double {
        if (target <= 0 || need <= 0) return 0.0
        val daysLeft = 8 - date.dayOfWeek.value
        val pressure = need.toDouble() / target * 7.0 / daysLeft
        return 10 + 30 * pressure.coerceAtMost(2.0)
    }
}

/**
 * Generates a day plan: fixed anchors, routine blocks and calendar events first, then STEPS chains
 * (attended steps with unattended waits between), then fills the free windows with the best-scoring
 * focus / practice / filler work.
 *
 * "How's today?" modes (STATE.md section 11):
 * PUSH 25-minute blocks · RECHARGE nap/walk first, then a lighter plan · RESET only deadlines,
 * picks and filler, plus a recharge block · WRITEOFF fixed commitments only.
 * No mode but a rough day (bad sleep / marked) = the v0.2 softening.
 */
class DayPlanner(
    private val minWindowMin: Int = 15,
    private val roughChunkMin: Int = 50,
    private val roughFocusCapMin: Int = 240,
) {

    fun plan(date: LocalDate, data: AppData, now: Long, carried: Map<String, Int> = emptyMap()): DayPlan {
        val s = data.settings
        val zone: ZoneId = Time.zone(s.zoneId)
        val ctx = data.days.firstOrNull { it.date == date.toString() }
        val mode = ctx?.mode
        val rough = RoughDay.isRough(ctx, s.badSleepHours)
        val soft = rough || mode == DayMode.RECHARGE || mode == DayMode.RESET
        val energy = if (soft) Energy.LOW else s.energy
        val chunkCap = when {
            mode == DayMode.PUSH -> 25
            soft -> min(roughChunkMin, s.maxChunkMin)
            else -> s.maxChunkMin
        }
        val breakLen = if (mode == DayMode.PUSH) 5 else s.breakMin
        val focusCap: Int? = when {
            mode == DayMode.WRITEOFF -> 0
            mode == DayMode.PUSH -> null
            soft -> roughFocusCapMin
            else -> null
        }

        val dayStart = Time.at(date, s.dayStartMin, zone)
        val dayEnd = Time.at(date, s.dayEndMin, zone)

        val open = data.liveTasks().filter { it.status == TaskStatus.TODO && it.kind != TaskKind.WAITING }
        val lowStat = com.maven.notafraud.core.stats.Stats.lowAreas(data, date)
        val hotTasks = open.filter { Heat.deadlineHot(it, now) }
        val picks = data.picks[date.toString()].orEmpty().toSet()

        val fixed = mutableListOf<PlanBlock>()
        val gaveWay = mutableListOf<String>()
        val shedHabits = mode == DayMode.RESET || mode == DayMode.WRITEOFF
        for (a in Anchors.on(date, data.anchors)) {
            val yields = a.yieldsToHot && a.mode != AnchorMode.HARD
            if (yields && (hotTasks.isNotEmpty() || shedHabits)) {
                gaveWay += a.title
                continue
            }
            if (shedHabits && a.mode == AnchorMode.CONTAINER) continue
            fixed += PlanBlock(
                start = Time.at(date, a.startMin, zone),
                end = Time.at(date, a.endMin, zone),
                kind = BlockKind.ANCHOR,
                title = a.title,
                anchorKind = a.kind,
                protected = a.protected,
                mode = a.mode,
                anchorId = a.id,
            )
        }
        for (r in data.routine) {
            if (r.reserveMin <= 0 || !r.enabled || date.dayOfWeek.value !in r.days) continue
            if ((soft || mode == DayMode.WRITEOFF) && r.weeklyTarget != null) continue
            fixed += PlanBlock(
                start = Time.at(date, r.startMin, zone),
                end = Time.at(date, r.startMin + r.reserveMin, zone),
                kind = BlockKind.ANCHOR,
                title = r.title,
                anchorKind = AnchorKind.ROUTINE,
                mode = AnchorMode.HABIT,
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

        val from = max(dayStart, Time.roundUp(now, 5))
        val isContainer = { b: PlanBlock -> b.mode == AnchorMode.CONTAINER }
        val busy = merge(fixed.map { it.start to it.end }).toMutableList()
        val extra = mutableListOf<PlanBlock>()

        fun isFree(a: Long, b: Long) = busy.none { (s0, e0) -> s0 < b && a < e0 } && b <= dayEnd + 90 * Time.MIN
        fun occupy(a: Long, b: Long) { busy += a to b; val m = merge(busy); busy.clear(); busy += m }
        fun earliestFree(after: Long, len: Int, limit: Long): Long? {
            var t = Time.roundUp(after, 5)
            while (t + len * Time.MIN <= limit) {
                if (isFree(t, t + len * Time.MIN)) return t
                t += 5 * Time.MIN
            }
            return null
        }

        // Recharge first (nap / walk), on the day it was chosen.
        if ((mode == DayMode.RECHARGE || mode == DayMode.RESET || mode == DayMode.WRITEOFF) && ctx != null) {
            val len = if (mode == DayMode.RECHARGE) ctx.rechargeMin else 45
            val t0 = earliestFree(max(from, ctx.modeAt ?: from), len, dayEnd)
            if (t0 != null) {
                val title = when {
                    mode == DayMode.RECHARGE && len >= 60 -> "Recharge: ${len}-minute nap"
                    mode == DayMode.RECHARGE -> "Recharge: ${len}-min nap or walk"
                    else -> "Recharge: " + s.rechargeIdeas.take(3).joinToString(", ").ifBlank { "walk, gym, art, or just be bored" }
                }
                extra += PlanBlock(t0, t0 + len * Time.MIN, BlockKind.RECHARGE, title, protected = true)
                occupy(t0, t0 + len * Time.MIN)
            }
        }

        val allotted = HashMap<String, Int>()
        var focusUsed = 0
        val taskBlocks = mutableListOf<PlanBlock>()
        val practicedToday = HashSet<String>()

        /** Per-day cap (homework): at least the cap, more only if the target date needs it. */
        fun capToday(task: Task): Int {
            val cap = task.dailyCapMin ?: return Int.MAX_VALUE
            val target = task.targetAt ?: task.dueAt ?: return cap
            val daysLeft = java.time.temporal.ChronoUnit.DAYS.between(date, Time.dateOf(target, zone)).toInt().coerceAtLeast(0) + 1
            val left = Scorer.minutesLeft(task) - (carried[task.id] ?: 0)
            return maxOf(cap, (left + daysLeft - 1) / daysLeft)
        }

        fun need(task: Task): Int = when (task.kind) {
            TaskKind.PRACTICE -> Practice.need(task, data, date, (carried[task.id] ?: 0) + (allotted[task.id] ?: 0))
            else -> minOf(
                Scorer.minutesLeft(task) - (carried[task.id] ?: 0) - (allotted[task.id] ?: 0),
                capToday(task) - (allotted[task.id] ?: 0),
            )
        }

        fun allowedToday(task: Task, t: Long): Boolean {
            if (task.notBefore != null && task.notBefore > t) return false
            if (mode == DayMode.WRITEOFF) return false
            if (mode == DayMode.RESET) return task.id in picks || Heat.deadlineHot(task, now) || task.kind == TaskKind.FILLER
            return true
        }

        fun effective(task: Task, t: Long, w: Int, e: Energy, n: Int): Double {
            var sc = if (task.kind == TaskKind.PRACTICE) {
                val fit = if (w < task.minBlockMin) 0.2 else 1.0
                Practice.urgency(n, task.weeklyTargetMin ?: 0, date) * fit
            } else {
                val virtual = task.copy(loggedMin = task.loggedMin + (carried[task.id] ?: 0) + (allotted[task.id] ?: 0))
                Scorer.score(virtual, t, w, e)
            }
            sc *= Importance.mult(task, data, now, lowStat)
            if (task.kind == TaskKind.FILLER) sc *= if (e == Energy.LOW || w < 30 || Time.minuteOfDay(t, zone) >= 20 * 60) 1.4 else 0.8
            if (task.id in picks) sc = sc * 2 + 40
            if (task.hot) sc *= 1.5
            if (task.horizon == com.maven.notafraud.core.model.Horizon.ASAP) sc = sc * 1.6 + 25
            if (Heat.deadlineHot(task, t)) sc *= 1.3
            return sc
        }
        fun block(t: Long, len: Int, task: Task, title: String = task.title, stepId: String? = null) = PlanBlock(
            start = t, end = t + len * Time.MIN, kind = BlockKind.TASK, title = title,
            taskId = task.id, projectId = task.projectId, picked = task.id in picks,
            hot = task.hot || Heat.deadlineHot(task, t), stepId = stepId,
        )

        // ---- STEPS chains (attended steps, unattended waits) ----
        if (mode != DayMode.WRITEOFF) {
            val chains = open.filter { it.kind == TaskKind.STEPS && it.steps.any { s0 -> s0.doneAt == null } }
                .filter { (carried[it.id] ?: 0) == 0 }
                .filter { allowedToday(it, max(from, it.notBefore ?: 0)) }
                .sortedByDescending { effective(it, from, 120, energy, 60) }
            for (task in chains) {
                val pending = task.steps.filter { it.doneAt == null }
                val lastDone = task.steps.mapNotNull { it.doneAt }.maxOrNull()
                val earliest = max(from, max(task.notBefore ?: 0, lastDone?.let { it + pending.first().waitBeforeMin * Time.MIN } ?: 0))
                var t0 = Time.roundUp(earliest, 15)
                var placed: List<PlanBlock>? = null
                while (placed == null && t0 < dayEnd) {
                    placed = tryChain(task, pending, t0, lastDone != null, ::isFree, ::earliestFree, dayEnd, ::block)
                    t0 += 15 * Time.MIN
                }
                if (placed != null) {
                    for (b in placed) {
                        if (b.kind == BlockKind.TASK) { occupy(b.start, b.end); allotted[task.id] = (allotted[task.id] ?: 0) + b.minutes; focusUsed += b.minutes }
                        taskBlocks += b
                    }
                }
            }
        }

        // ---- Free windows: focus / practice / filler / errands ----
        val windows = freeWindows(from, dayEnd, merge(busy)).filter { (a, b) -> (b - a) / Time.MIN >= minWindowMin }
        val fillable = open.filter { it.kind != TaskKind.STEPS }
        windowLoop@ for ((wStart, wEnd) in windows) {
            var t = wStart
            while ((wEnd - t) / Time.MIN >= minWindowMin) {
                val w = ((wEnd - t) / Time.MIN).toInt()
                val capLeft = focusCap?.let { it - focusUsed } ?: Int.MAX_VALUE
                if (capLeft < minWindowMin) break@windowLoop

                val pick = fillable.asSequence()
                    .filter { allowedToday(it, t) }
                    .filter { it.kind != TaskKind.PRACTICE || it.id !in practicedToday }
                    .map { it to need(it) }
                    .filter { (_, n) -> n >= 5 }
                    .filter { (task, n) -> w >= min(task.minBlockMin, n) }
                    .map { (task, n) -> Triple(task, n, effective(task, t, w, energy, n)) }
                    .filter { it.third > 0 }
                    .maxByOrNull { it.third } ?: break

                val (task, n, _) = pick
                val learned = if (task.kind == TaskKind.PRACTICE) com.maven.notafraud.core.stats.Learning.typicalMin(data, task.id) else null
                val cap = (learned?.let { roundDown5((it * 1.2).toInt()).coerceAtLeast(task.minBlockMin) } ?: Int.MAX_VALUE)
                    .coerceAtMost(task.maxBlockMin ?: if (task.kind == TaskKind.PRACTICE) 60 else chunkCap)
                var len = minOf(n, w, chunkCap, capLeft, cap)
                len = roundDown5(len).coerceAtLeast(min(5, len))
                if (len < minWindowMin && len < n) break

                taskBlocks += block(t, len, task)
                t += len * Time.MIN
                allotted[task.id] = (allotted[task.id] ?: 0) + len
                if (task.kind == TaskKind.PRACTICE) practicedToday += task.id
                if (task.kind != TaskKind.FILLER) focusUsed += len

                val needsBreak = if (mode == DayMode.PUSH) len >= 20 else len >= 45
                if (needsBreak && (wEnd - t) / Time.MIN >= breakLen + minWindowMin) {
                    taskBlocks += PlanBlock(t, t + breakLen * Time.MIN, BlockKind.BREAK, "Break: stand up, water, walk")
                    t += breakLen * Time.MIN
                }
            }
        }

        // ---- Containers (craft blocks): hottest task from their areas ----
        val areaOf = data.projects.associate { it.id to it.areaId }
        val projectKind = data.projects.filter { it.kind == ProjectKind.PROJECT }.map { it.id }.toSet()
        for (craft in fixed.filter { isContainer(it) && it.end > from }) {
            val anchor = data.anchors.firstOrNull { it.id == craft.anchorId }
            val fill = anchor?.fillAreaIds.orEmpty()
            val eligible = { task: Task -> if (fill.isEmpty()) task.projectId in projectKind else areaOf[task.projectId] in fill }
            var t = max(craft.start, from)
            while ((craft.end - t) / Time.MIN >= minWindowMin) {
                val w = ((craft.end - t) / Time.MIN).toInt()
                val pick = fillable.asSequence()
                    .filter(eligible)
                    .filter { allowedToday(it, t) }
                    .map { it to need(it) }
                    .filter { (_, n) -> n >= 5 }
                    .map { (task, n) -> Triple(task, n, effective(task, t, w, Energy.HIGH, n)) }
                    .maxByOrNull { it.third } ?: break
                val (task, n, _) = pick
                val len = roundDown5(minOf(n, w, task.maxBlockMin ?: w)).coerceAtLeast(5)
                taskBlocks += block(t, len, task)
                t += len * Time.MIN
                allotted[task.id] = (allotted[task.id] ?: 0) + len
            }
        }

        val note = listOfNotNull(
            when (mode) {
                DayMode.PUSH -> "Push: 25-minute blocks with 5-minute breaks."
                DayMode.RECHARGE -> "Recharge first, then a lighter plan."
                DayMode.RESET -> "Reset: only what's due soon, your picks and easy stuff. The rest moves to the coming days."
                DayMode.WRITEOFF -> "Written off: fixed commitments only. Rest."
                null -> if (rough) buildString {
                    append("Rough day")
                    ctx?.sleepHours?.let { append(" (%.1fh sleep)".format(it)) }
                    append(": lighter plan, ${Time.durationText(roughFocusCapMin)} focus cap, workout optional.")
                } else null
            },
            if (gaveWay.isNotEmpty() && hotTasks.isNotEmpty() && !shedHabits) "${gaveWay.distinct().joinToString()} gave way: ${hotTasks.first().title} is due soon." else null,
        ).joinToString(" ")

        return DayPlan(
            date = date.toString(),
            blocks = (fixed + extra + taskBlocks).sortedWith(compareBy({ it.start }, { it.kind.ordinal })),
            roughDay = rough,
            focusCapMin = focusCap,
            energy = energy,
            generatedAt = now,
            note = note,
            mode = mode,
            allotted = allotted,
        )
    }

    /**
     * Places a STEPS task's remaining steps starting at [t0]. Waits become BACKGROUND blocks that
     * don't occupy time; each attended step goes at the end of its wait (exact) or the next free slot.
     * Returns null if the chain doesn't fit today.
     */
    private fun tryChain(
        task: Task,
        pending: List<com.maven.notafraud.core.model.TaskStep>,
        t0: Long,
        resumed: Boolean,
        isFree: (Long, Long) -> Boolean,
        earliestFree: (Long, Int, Long) -> Long?,
        dayEnd: Long,
        block: (Long, Int, Task, String, String?) -> PlanBlock,
    ): List<PlanBlock>? {
        val out = mutableListOf<PlanBlock>()
        val taken = mutableListOf<Pair<Long, Long>>()
        fun free(a: Long, b: Long) = isFree(a, b) && taken.none { (s0, e0) -> s0 < b && a < e0 }
        var cur = t0
        val limit = dayEnd + 90 * Time.MIN
        for ((i, st) in pending.withIndex()) {
            val len = st.min.coerceAtLeast(5)
            val slot: Long = if (i == 0) {
                if (!free(t0, t0 + len * Time.MIN)) return null
                t0
            } else {
                val target = cur + st.waitBeforeMin * Time.MIN
                if (st.waitBeforeMin > 0) {
                    out += PlanBlock(cur, target, BlockKind.BACKGROUND, "${task.title}: running (next: ${st.title})", taskId = task.id, projectId = task.projectId)
                }
                if (st.exact) {
                    if (!free(target, target + len * Time.MIN)) return null
                    target
                } else {
                    var t = target
                    var found: Long? = null
                    while (t + len * Time.MIN <= limit) {
                        if (free(t, t + len * Time.MIN)) { found = t; break }
                        t += 5 * Time.MIN
                    }
                    found ?: return null
                }
            }
            if (slot + len * Time.MIN > limit) return null
            out += block(slot, len, task, "${task.title}: ${st.title}", st.id)
            taken += slot to slot + len * Time.MIN
            cur = slot + len * Time.MIN
        }
        return out
    }

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
        if (task.status != TaskStatus.TODO || task.priority != Priority.HIGH) return false
        val due = task.dueAt ?: return false
        if (due < now - 36 * 3_600_000L) return false
        val slack = slackHours(task, now) ?: return false
        return slack < SLACK_HOURS
    }
}
