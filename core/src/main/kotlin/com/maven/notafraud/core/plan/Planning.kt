package com.maven.notafraud.core.plan

import com.maven.notafraud.core.model.AppData

/** Keeps a stable plan for today: generated once per day, regenerated on demand from "now" forward. */
object Planning {
    private val planner = DayPlanner()

    fun ensureToday(d: AppData, now: Long): AppData {
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId))
        return if (d.todayPlan?.date == date.toString()) d else d.copy(todayPlan = planner.plan(date, d, now))
    }

    /** Re-plan from now on, keeping the task blocks that already happened today. */
    fun replan(d: AppData, now: Long): AppData {
        val date = Time.dateOf(now, Time.zone(d.settings.zoneId))
        val fresh = planner.plan(date, d, now)
        val old = d.todayPlan?.takeIf { it.date == date.toString() }
        val past = old?.blocks?.filter { (it.kind == BlockKind.TASK || it.kind == BlockKind.BREAK) && it.end <= now }.orEmpty()
        val blocks = (fresh.blocks + past).sortedWith(compareBy({ it.start }, { it.kind.ordinal }))
        return d.copy(todayPlan = fresh.copy(blocks = blocks))
    }

    fun preview(d: AppData, date: java.time.LocalDate): DayPlan {
        val zone = Time.zone(d.settings.zoneId)
        return planner.plan(date, d, Time.at(date, 0, zone))
    }

    fun nowState(d: AppData, now: Long): NowState {
        val withPlan = ensureToday(d, now)
        return NowResolver.resolve(withPlan.todayPlan!!, withPlan, now)
    }
}
