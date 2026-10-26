package com.maven.notafraud.core

import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.plan.Homework
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Migration
import com.maven.notafraud.core.seed.Seed
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertTrue

/** Maven's real-shaped data (seed: research pinned hot, catch-up practice, classes) plus Canvas homework. */
class HomeworkPlanTest {
    private val zone = ZoneId.of("America/Chicago")
    private val mon = LocalDate.parse("2026-10-26")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)

    @Test
    fun `canvas homework shows up in the week's plan on real-shaped data`() {
        val events = listOf(
            CalEvent("phone-1", "HW 7 [ME-3525-01]", at(mon.plusDays(2), 23, 59), at(mon.plusDays(2), 23, 59), "phone", calendarId = 9),
            CalEvent("phone-2", "Lab report 4 [ME-4840-01]", at(mon.plusDays(4), 23, 59), at(mon.plusDays(4), 23, 59), "phone", calendarId = 9),
            // Canvas all-day style entry.
            CalEvent("phone-3", "Quiz prep [BIO-SCI-3333]", Time.at(mon.plusDays(3), 0, ZoneId.of("UTC")), Time.at(mon.plusDays(4), 0, ZoneId.of("UTC")), "phone", allDay = true, calendarId = 9),
        )
        val now = at(mon, 14, 44)
        var d = Migration.apply(Seed.data(at(mon.minusDays(30), 6)), now)
        d = Homework.sync(d, events, now)
        val hw = d.tasks.filter { Homework.isHomework(it) }
        assertTrue(hw.size == 3, "made ${hw.size} homework tasks")
        d = Planning.ensureToday(d, now)
        val plans = listOfNotNull(d.todayPlan) + d.weekPlan
        val lines = plans.map { p -> p.date + ": " + p.blocks.filter { b -> hw.any { it.id == b.taskId } }.joinToString { "${it.title} ${it.minutes}m" } }
        println(lines.joinToString("\n"))
        for (t in hw) assertTrue(plans.any { p -> p.blocks.any { it.taskId == t.id } }, "${t.title} not planned all week:\n" + lines.joinToString("\n") +
            "\nall today: " + d.todayPlan!!.blocks.joinToString { "${Time.clock(it.start, zone)} ${it.title}" })
        val quiz = hw.first { it.title.startsWith("Quiz") }
        assertTrue(Time.dateOf(quiz.dueAt ?: 0, zone) == mon.plusDays(3), "all-day due date: ${Time.dateOf(quiz.dueAt ?: 0, zone)}")
    }
}
