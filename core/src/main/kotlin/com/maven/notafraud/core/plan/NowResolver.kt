package com.maven.notafraud.core.plan

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
)

object NowResolver {

    fun resolve(plan: DayPlan, data: AppData, now: Long): NowState {
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

        val suggested = Scorer.rank(data.tasks, now, minutesLeft ?: 60, plan.energy).firstOrNull()?.first

        if (current != null) {
            val until = "until ${Time.clock(current.end, zone)}"
            val (headline, detail) = when (current.kind) {
                BlockKind.TASK -> "Do: ${current.title}" to "$until · tap Start to log it"
                BlockKind.BREAK -> "Break" to "Stand up, water, walk · $until"
                BlockKind.EVENT -> current.title to until
                BlockKind.ANCHOR -> current.title to if (current.protected) "Protected time. Enjoy it." else until
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

    /** When blocks overlap, the more specific one wins: event > anchor > task > break. */
    private fun pickCurrent(blocks: List<PlanBlock>, now: Long): PlanBlock? =
        blocks.filter { it.contains(now) }.minByOrNull {
            when (it.kind) {
                BlockKind.EVENT -> 0
                BlockKind.ANCHOR -> if (it.protected) -1 else 1
                BlockKind.TASK -> 2
                BlockKind.BREAK -> 3
            }
        }
}
