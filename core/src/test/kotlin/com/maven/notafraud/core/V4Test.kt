package com.maven.notafraud.core

import com.maven.notafraud.core.model.DayContext
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskKind
import com.maven.notafraud.core.model.WorkSession
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.Importance
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.seed.Migration
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.stats.Burnout
import com.maven.notafraud.core.stats.Learning
import com.maven.notafraud.core.stats.Skills
import com.maven.notafraud.core.stats.Stats
import com.maven.notafraud.core.store.Actions
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class V4Test {
    private val zone = ZoneId.of("America/Chicago")
    private val thu = LocalDate.parse("2026-10-22")
    private val sat = LocalDate.parse("2026-11-14")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)
    private fun base() = Seed.data(at(thu, 4)).copy(tasks = emptyList())

    @Test
    fun `body stat rises with sleep and workouts`() {
        var d = base()
        for (i in 0..6) d = Actions.upsertDay(d, DayContext(date = thu.minusDays(i.toLong()).toString(), sleepHours = 5.0))
        val low = Stats.all(d, thu).first { it.key == "body" }.score!!
        for (i in 0..6) d = Actions.upsertDay(d.copy(days = d.days.filterNot { it.date == thu.minusDays(i.toLong()).toString() }), DayContext(date = thu.minusDays(i.toLong()).toString(), sleepHours = 8.0))
        for (i in 0..5) d = Actions.checkRoutine(d, "r-workout", at(thu.minusDays(i.toLong()), 17))
        val high = Stats.all(d, thu).first { it.key == "body" }.score!!
        assertTrue(high > low + 3, "$low -> $high")
    }

    @Test
    fun `a low stat raises its areas' importance, a task override wins`() {
        val d = base()
        val proj = d.projects.first { it.areaId == "a-research" }
        val t = Task(title = "Run samples", projectId = proj.id, createdAt = 0)
        assertEquals(5, Importance.level(t, d, 0))
        val fit = d.projects.first { it.areaId == "a-health" }
        val t2 = Task(title = "Meal prep", projectId = fit.id, createdAt = 0)
        assertEquals(4, Importance.level(t2, d, 0, setOf("a-health")))
        assertEquals(2, Importance.level(t2.copy(importance = 2), d, 0, setOf("a-health")))
    }

    @Test
    fun `skills level by practice time and unlock children`() {
        var d = base()
        d = Actions.upsertSkill(d, com.maven.notafraud.core.model.Skill(id = "child", treeId = "st-athletics", name = "Double backflip", parentId = "sk-backflip"))
        assertFalse(Skills.unlocked(d, "child"))
        d = Actions.practiceSkill(d, "sk-backflip", 60, at(thu, 9))
        val tid = d.skills.first { it.id == "sk-backflip" }.practiceTaskId!!
        assertTrue(d.tasks.any { it.id == tid && it.kind == TaskKind.PRACTICE })
        d = Actions.startSession(d, tid, null, at(thu, 17))
        d = Actions.stopSession(d, at(thu, 18, 5))
        assertEquals(1, Skills.level(Skills.minutes(d, "sk-backflip")))
        assertTrue(Skills.unlocked(d, "child"))
        // Athletics is a sport tree: 65 minutes counted as today's workout.
        val w = d.routine.first { it.id == "r-workout" }
        assertEquals("Sport", RoutinePolicy.day(d, thu).notes[w.id])
    }

    @Test
    fun `practice blocks size themselves to how long sessions actually last`() {
        val magic = Task(id = "m", title = "Magic tricks", kind = TaskKind.PRACTICE, weeklyTargetMin = 300, minBlockMin = 10, maxBlockMin = 60, createdAt = 0)
        val sessions = (1..4).map { WorkSession(taskId = "m", title = "Magic", start = at(thu.minusDays(it.toLong() + 7), 10), end = at(thu.minusDays(it.toLong() + 7), 10, 15), minutes = 15) }
        val d = base().copy(tasks = listOf(magic), sessions = sessions)
        assertEquals(15, Learning.typicalMin(d, "m"))
        val block = DayPlanner().plan(sat, d, at(sat, 6)).blocks.first { it.taskId == "m" }
        assertTrue(block.minutes <= 20, "${block.minutes}")
    }

    @Test
    fun `a slipped block re-plans and lists what no longer fits today`() {
        val big = Task(title = "Big thing", estMin = 600, createdAt = 0)
        var d = Planning.ensureToday(base().copy(tasks = listOf(big)), at(sat, 6))
        val first = d.todayPlan!!.blocks.first { it.kind == BlockKind.TASK }
        val after = Planning.autoReplan(d, first.end + 5 * Time.MIN)
        assertTrue(after.todayPlan!!.generatedAt > d.todayPlan!!.generatedAt)
        assertEquals(after, Planning.autoReplan(after, first.end + 6 * Time.MIN), "doesn't re-plan again for the same slip")
    }

    @Test
    fun `work due this week that can't fit is flagged at risk`() {
        val huge = Task(title = "Impossible report", estMin = 6000, dueAt = at(thu.plusDays(2), 9), priority = Priority.HIGH, createdAt = 0)
        val d = Planning.ensureToday(base().copy(tasks = listOf(huge)), at(thu, 6))
        assertEquals(listOf("Impossible report"), d.todayPlan!!.atRisk)
    }

    @Test
    fun `two signs of running low prompt once`() {
        var d = base()
        d = Actions.upsertDay(d, DayContext(date = thu.toString(), sleepHours = 4.8))
        d = Actions.upsertDay(d, DayContext(date = thu.minusDays(1).toString(), sleepHours = 5.0))
        for (i in 1..2) d = Actions.checkRoutine(d, "r-wake", at(thu.minusDays(i.toLong() + 5), 6))
        val sig = Burnout.signals(d, at(thu, 13))
        assertTrue(sig.size >= 2, sig.toString())
        assertTrue(Burnout.shouldPrompt(d, at(thu, 13)))
        d = Actions.markPrompted(d, thu, at(thu, 13))
        assertFalse(Burnout.shouldPrompt(d, at(thu, 14)))
    }

    @Test
    fun `check-in feeds social and spirit`() {
        val on = base().let { it.copy(settings = it.settings.copy(checkInEnabled = true)) }
        val d = Actions.checkIn(on, LocalDate.parse("2026-10-19"), mapOf("social" to 1, "spirit" to 5), at(thu, 9))
        val stats = Stats.all(d, thu).associateBy { it.key }
        assertEquals(2.0, stats.getValue("social").score)
        assertEquals(10.0, stats.getValue("spirit").score)
        assertTrue("a-people" in Stats.lowAreas(d, thu))
    }

    @Test
    fun `v5 adds starter skill trees once`() {
        val old = base().copy(schema = 4, skillTrees = emptyList(), skills = emptyList())
        val v5 = Migration.apply(old)
        assertEquals(Migration.CURRENT, v5.schema)
        assertTrue(v5.skillTrees.any { it.name == "Climbing" && it.sport })
        assertNotNull(v5.skills.firstOrNull { it.name == "Piano" })
        assertEquals(v5, Migration.apply(v5))
    }
}
