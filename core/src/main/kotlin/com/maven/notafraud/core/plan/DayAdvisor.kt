package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.DayMode

/**
 * "How's today?" suggestion. Rules for now (STATE.md section 11); later versions learn from which
 * choice actually led to a productive evening.
 *
 * - Nap only before 3pm (later naps wreck the night): 20 min after one short night, 90 min after two or more.
 * - After 3pm with short sleep or skipped blocks: reset rather than nap.
 * - Otherwise push.
 */
object DayAdvisor {
    data class Advice(val mode: DayMode, val rechargeMin: Int, val reason: String)

    fun shortNights(d: AppData, now: Long): Int {
        val zone = Time.zone(d.settings.zoneId)
        val today = Time.dateOf(now, zone)
        var n = 0
        for (i in 0..2) {
            val h = d.days.firstOrNull { it.date == today.minusDays(i.toLong()).toString() }?.sleepHours ?: break
            if (h < d.settings.badSleepHours) n++ else break
        }
        return n
    }

    /** Task blocks in today's plan that ended without any logged session overlapping them. */
    fun skippedBlocks(d: AppData, now: Long): Int {
        val plan = d.todayPlan ?: return 0
        return plan.blocks.count { b ->
            b.kind == BlockKind.TASK && b.end <= now &&
                d.sessions.none { s -> s.start < b.end && b.start < s.end } &&
                d.tasks.firstOrNull { it.id == b.taskId }?.status != com.maven.notafraud.core.model.TaskStatus.DONE
        }
    }

    fun suggest(d: AppData, now: Long): Advice {
        val zone = Time.zone(d.settings.zoneId)
        val m = Time.minuteOfDay(now, zone)
        val nights = shortNights(d, now)
        val skipped = skippedBlocks(d, now)
        val beforeThree = m < 15 * 60
        val sleep = d.days.firstOrNull { it.date == Time.dateOf(now, zone).toString() }?.sleepHours
        val sleepText = sleep?.let { "%.1fh sleep".format(it) } ?: "no sleep data"
        return when {
            nights >= 2 && beforeThree -> Advice(DayMode.RECHARGE, 90, "$nights short nights in a row. A 90-minute nap now, then a lighter plan.")
            nights >= 1 && beforeThree -> Advice(DayMode.RECHARGE, 20, "$sleepText. A 20-minute nap or walk, then push.")
            (nights >= 1 || skipped >= 2) && !beforeThree -> Advice(DayMode.RESET, 45, "It's late for a nap. Keep what's due, do easy wins, protect tonight's sleep.")
            skipped >= 2 -> Advice(DayMode.PUSH, 0, "$skipped blocks slipped. Short 25-minute blocks, easiest win first.")
            else -> Advice(DayMode.PUSH, 0, "You're fine. Short blocks get you moving.")
        }
    }
}
