package com.maven.notafraud.core

import com.maven.notafraud.core.model.Horizon
import com.maven.notafraud.core.model.Idea
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.Importance
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.seed.Migration
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.workout.WorkoutLink
import com.maven.notafraud.core.workout.WorkoutStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class V7Test {
    private val zone = ZoneId.of("America/Chicago")
    private val thu = LocalDate.parse("2026-10-29")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)
    private fun base() = Seed.data(at(thu, 4)).copy(tasks = emptyList(), anchors = emptyList())

    @Test
    fun `must beats normal, low waits, pick 3 beats must`() {
        val must = Task(title = "Must", estMin = 60, createdAt = 0, importance = Importance.MUST)
        val low = Task(title = "Low", estMin = 60, createdAt = 0, importance = Importance.LOW)
        val normal = Task(title = "Normal", estMin = 60, createdAt = 0)
        var d = base().copy(tasks = listOf(low, normal, must))
        val first = DayPlanner().plan(thu, d, at(thu, 6)).blocks.first { it.kind == BlockKind.TASK }
        assertEquals("Must", first.title)
        d = d.copy(picks = mapOf(thu.toString() to listOf(low.id)))
        assertEquals("Low", DayPlanner().plan(thu, d, at(thu, 6)).blocks.first { it.kind == BlockKind.TASK }.title)
    }

    @Test
    fun `v8 folds pinned hot into priority and ideas into someday`() {
        val old = base().copy(schema = 7, tasks = listOf(Task(title = "Hot one", hot = true, createdAt = 0)), ideas = listOf(Idea(text = "Build a lamp", createdAt = 1)))
        val v8 = Migration.apply(old, at(thu, 9))
        val t = v8.tasks.first { it.title == "Hot one" }
        assertFalse(t.hot)
        assertEquals(4, t.importance)
        assertEquals(Horizon.SOMEDAY, v8.tasks.first { it.title == "Build a lamp" }.horizon)
        assertTrue(v8.ideas.isEmpty())
        assertEquals(v8, Migration.apply(v8, at(thu, 9)))
    }

    @Test
    fun `workout status drives the routine, the plan and the credit pause`() {
        val must = WorkoutStatus(today = thu.toString(), minPerWeek = 3, doneThisWeek = 0, daysLeft = 3, needed = 3, slack = 0, risk = WorkoutLink.MUST_TODAY, suggestedToday = "Push")
        var d = WorkoutLink.receive(base(), must, at(thu, 9))
        assertTrue(WorkoutLink.mustToday(d, at(thu, 9)))
        assertTrue(WorkoutLink.creditsPaused(d, at(thu, 9)))
        assertFalse(WorkoutLink.mustToday(d, at(thu.plusDays(1), 9)), "a stale status means nothing")
        val plan = DayPlanner().plan(thu, d, at(thu, 6))
        assertTrue(plan.blocks.any { it.title == "Workout: Push" }, plan.blocks.joinToString { it.title })
        // Started in the workout app: no pause.
        assertFalse(WorkoutLink.creditsPaused(WorkoutLink.receive(d, must.copy(inProgress = true), at(thu, 17)), at(thu, 17)))
        // Done: the routine ticks itself, the block goes.
        d = WorkoutLink.receive(d, must.copy(trainedToday = true, doneThisWeek = 1, doneDates = listOf(thu.toString()), risk = WorkoutLink.TIGHT), at(thu, 18))
        val w = d.routine.first { it.id == "r-workout" }
        assertTrue(RoutinePolicy.day(d, thu).done.containsKey(w.id))
        assertTrue(DayPlanner().plan(thu, d, at(thu, 6)).blocks.none { it.routineId == w.id })
        assertFalse(WorkoutLink.creditsPaused(d, at(thu, 18)))
    }
}
