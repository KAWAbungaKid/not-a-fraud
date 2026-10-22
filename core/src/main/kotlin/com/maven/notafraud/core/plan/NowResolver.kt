package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.liveTasks
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.score.Scorer

/** What the notification bar says right now. */
data class NowState(
    val headline: String,
    val detail: String,
    val current: PlanBlock?,
    val next: PlanBlock?,
    val suggested: Task?,
    val protected: Boolean,
    val working: Boolean,
    val minutesLeftInBlock: Int?,
    /** The most pressing routine item, e.g. "Lunch · 40 min late". */
    val routine: String? = null,
    val routineId: String? = null,
)

object NowResolver {

    fun resolve(plan: DayPlan, data: AppData, now: Long): NowState {
        val base = resolveBlocks(plan, data, now)
        val pressing = com.maven.notafraud.core.routine.RoutinePolicy.statuses(data, now)
            .filter { it.mood == com.maven.notafraud.core.routine.Mood.ANGRY || it.mood == com.maven.notafraud.core.routine.Mood.LATE || it.mood == com.maven.notafraud.core.routine.Mood.DUE }
            .maxWithOrNull(compareBy({ it.mood.ordinal }, { it.minutesLate })) ?: return base
        val text = when (pressing.mood) {
            com.maven.notafraud.core.routine.Mood.DUE -> "${pressing.item.title} · now"
            else -> "${pressing.item.title} · ${Time.durationText(pressing.minutesLate)} late"
        }
        return base.copy(routine = text, routineId = pressing.item.id)
    }

    private fun resolveBlocks(plan: DayPlan, data: AppData, now: Long): NowState {
        val zone = Time.zone(data.settings.zoneId)
        val current = pickCurrent(plan.blocks, now)
        val next = plan.blocks.filter { it.start > now }.minByOrNull { it.start }
        val nextText = next?.let { "Next: ${it.title} at ${Time.clock(it.start, zone)}" } ?: ""
        val minutesLeft = current?.let { ((it.end - now) / Time.MIN).toInt() + 1 }

        val active = data.active
        if (active != null) {
            val mins = ((now - active.start) / Time.MIN).toInt()
            return NowState(
                headline = "Working: ${active.title}",
                detail = listOf("${Time.durationText(mins)} in", nextText).filter { it.isNotBlank() }.joinToString(" · "),
                current = current, next = next, suggested = null,
                protected = false, working = true, minutesLeftInBlock = minutesLeft,
            )
        }

        val suggested = Scorer.rank(data.liveTasks(), now, minutesLeft ?: 60, plan.energy).firstOrNull()?.first

        if (current != null) {
            val until = "until ${Time.clock(current.end, zone)}"
            val (headline, detail) = when (current.kind) {
                BlockKind.TASK -> "Do: ${current.title}" to "$until · tap Start to log it"
                BlockKind.BREAK -> "Break" to "Stand up, water, walk · $until"
                BlockKind.EVENT -> current.title to until
                BlockKind.ANCHOR -> current.title to if (current.protected) "Protected time. Enjoy it." else until
                BlockKind.RECHARGE -> current.title to "$until · this counts as part of the plan"
                BlockKind.BACKGROUND -> current.title to until
            }
            return NowState(
                headline, listOf(detail, nextText).filter { it.isNotBlank() }.joinToString(" · "),
                current, next, if (current.kind == BlockKind.TASK) data.tasks.firstOrNull { it.id == current.taskId } else suggested,
                current.protected, false, minutesLeft,
            )
        }

        val dayEnd = plan.blocks.maxOfOrNull { it.end } ?: now
        if (next == null && now >= dayEnd) {
            return NowState("Wind down", "Day's done. Phone down, lights low.", null, null, null, true, false, null)
        }

        return if (suggested != null) {
            NowState(
                "Free: start ${suggested.title}",
                listOf("Open window", nextText).filter { it.isNotBlank() }.joinToString(" · "),
                null, next, suggested, false, false, null,
            )
        } else {
            NowState("Free time", nextText.ifBlank { "Nothing pressing." }, null, next, null, false, false, null)
        }
    }

    /**
     * When blocks overlap, the more specific one wins: protected > event > task > anchor > break.
     * Tasks only overlap anchors inside craft blocks, where the task is the useful answer.
     */
    fun pickCurrent(blocks: List<PlanBlock>, now: Long): PlanBlock? =
        blocks.filter { it.contains(now) }.minByOrNull {
            when (it.kind) {
                BlockKind.ANCHOR -> when {
                    it.protected -> -1.0
                    it.anchorKind == com.maven.notafraud.core.model.AnchorKind.MARKET -> 0.5
                    it.mode == com.maven.notafraud.core.model.AnchorMode.HARD -> 0.8
                    else -> 2.0
                }
                BlockKind.EVENT -> 0.0
                BlockKind.TASK -> 1.0
                BlockKind.BREAK -> 3.0
                BlockKind.RECHARGE -> -0.5
                BlockKind.BACKGROUND -> 5.0
            }
        }
}
