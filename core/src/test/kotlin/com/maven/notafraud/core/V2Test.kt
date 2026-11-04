package com.maven.notafraud.core

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Project
import com.maven.notafraud.core.model.ProjectKind
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.Generated
import com.maven.notafraud.core.plan.NowResolver
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.Mood
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.seed.Migration
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V2Test {
    private val zone = ZoneId.of("America/Chicago")
    private val tue = LocalDate.parse("2026-10-20")
    private val sun = LocalDate.parse("2026-10-25")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)
    private fun data() = Seed.data(at(tue, 5))

    @Test
    fun `installing mid-afternoon doesn't fire a burst`() {
        val d = Seed.data(at(tue, 16))
        assertTrue(RoutinePolicy.alertsDue(d, at(tue, 16, 1)).none { it.item.id == "r-lunch" })
        assertTrue(RoutinePolicy.alertsDue(Seed.data(at(tue, 5)), at(tue, 16)).size <= RoutinePolicy.MAX_AT_ONCE)
    }

    // ---- Migration ----

    @Test
    fun `v1 data migrates to goals, areas, routine and new blocks`() {
        val v1 = AppData(
            schema = 1,
            projects = listOf(Project(id = "p1", name = "ME 3411 Dynamic Systems", kind = ProjectKind.SCHOOL), Project(id = "p2", name = "Biosensor research", kind = ProjectKind.RESEARCH)),
            tasks = listOf(
                Task(title = "Study for Heat Transfer Final", status = TaskStatus.DONE, doneAt = 1L),
                Task(title = "Install Not a Fraud + grant permissions (Setup tab)"),
            ),
        )
        val v2 = Migration.apply(v1, at(tue, 9))
        assertEquals(Migration.CURRENT, v2.schema)
        assertEquals(6, v2.goals.size)
        assertEquals("a-school", v2.projects.first { it.id == "p1" }.areaId)
        assertEquals("a-research", v2.projects.first { it.id == "p2" }.areaId)
        assertTrue(v2.routine.any { it.title == "Lunch" })
        assertTrue(v2.anchors.none { it.title == "Lift" || it.title == "Lunch" }, "meals and lifting moved to routine")
        assertTrue(v2.tasks.none { it.title.startsWith("Study for Heat") }, "seeded exam studying is gone (tests come from the calendar)")
        assertEquals(TaskStatus.DONE, v2.tasks.first { it.title.startsWith("Install") }.status)
        assertEquals(v2, Migration.apply(v2), "idempotent")
    }

    @Test
    fun `every area feeds a goal and every seeded project has an area`() {
        val d = data()
        val goalIds = d.goals.map { it.id }.toSet()
        assertTrue(d.areas.all { a -> a.goalIds.isNotEmpty() && a.goalIds.all { it in goalIds } })
        assertTrue(d.projects.all { it.areaId != null }, d.projects.filter { it.areaId == null }.joinToString { it.name })
    }

    // ---- Routine ----

    @Test
    fun `lunch gets angrier through the afternoon`() {
        val d = data()
        val lunch = d.routine.first { it.id == "r-lunch" }
        assertEquals(Mood.CALM, RoutinePolicy.status(d, lunch, at(tue, 10)).mood)
        assertEquals(Mood.DUE, RoutinePolicy.status(d, lunch, at(tue, 12, 30)).mood)
        assertEquals(Mood.LATE, RoutinePolicy.status(d, lunch, at(tue, 14)).mood)
        assertEquals(Mood.ANGRY, RoutinePolicy.status(d, lunch, at(tue, 16)).mood)
        val done = Actions.checkRoutine(d, "r-lunch", at(tue, 12, 40))
        assertEquals(Mood.DONE, RoutinePolicy.status(done, lunch, at(tue, 16)).mood)
    }

    @Test
    fun `angry nags repeat every 30 minutes, late nags once`() {
        var d = data()
        for (id in listOf("r-wake", "r-fluids", "r-cardio", "r-shower", "r-vitamins", "r-meal1")) d = Actions.checkRoutine(d, id, at(tue, 7))
        val fourPm = at(tue, 16)
        val first = RoutinePolicy.alertsDue(d, fourPm).map { it.item.id }
        assertTrue("r-lunch" in first)
        d = Actions.markAlerted(d, first, fourPm)
        assertTrue(RoutinePolicy.alertsDue(d, fourPm + 10 * Time.MIN).none { it.item.id == "r-lunch" })
        assertTrue(RoutinePolicy.alertsDue(d, fourPm + 30 * Time.MIN).any { it.item.id == "r-lunch" })
        val next = RoutinePolicy.nextAlertAt(d, fourPm + Time.MIN)
        assertNotNull(next)
        assertTrue(next <= fourPm + 30 * Time.MIN)
    }

    @Test
    fun `going to bed without brushing triggers immediately`() {
        val d = data()
        val ninePm = at(tue, 21, 10)
        assertTrue(RoutinePolicy.alertsDue(d, ninePm).none { it.item.id == "r-teeth" }, "no clock nag yet")
        val bedtime = RoutinePolicy.alertsDue(d, ninePm, bedtimeSignal = true)
        assertTrue(bedtime.any { it.item.id == "r-teeth" && it.mood == Mood.ANGRY })
    }

    @Test
    fun `workout target met makes it optional`() {
        var d = data()
        val mon = LocalDate.parse("2026-10-19")
        for (i in 0..5) d = Actions.checkRoutine(d, "r-workout", at(mon.plusDays(i.toLong()), 17, 30))
        val w = d.routine.first { it.id == "r-workout" }
        val st = RoutinePolicy.status(d, w, at(sun, 20))
        assertEquals(6, st.weekDone)
        assertTrue(st.mood == Mood.CALM || st.mood == Mood.OFF)
    }

    @Test
    fun `routine reserves time in the plan`() {
        val plan = DayPlanner().plan(tue, data(), at(tue, 5))
        assertTrue(plan.blocks.any { it.routineId == "r-workout" && it.kind == BlockKind.ANCHOR })
        assertTrue(plan.blocks.any { it.routineId == "r-lunch" })
    }

    @Test
    fun `now line carries the pressing routine item`() {
        var d0 = data()
        for (id in listOf("r-wake", "r-fluids", "r-cardio", "r-shower", "r-vitamins", "r-meal1")) d0 = Actions.checkRoutine(d0, id, at(tue, 7))
        val d = Planning.ensureToday(d0, at(tue, 15, 30))
        val s = NowResolver.resolve(d.todayPlan!!, d, at(tue, 15, 30))
        assertNotNull(s.routine)
        assertTrue(s.routine!!.contains("Lunch"))
    }

    // ---- Trading + make-up ----

    @Test
    fun `trading wins over ME 3411 and a make-up task is created once`() {
        val d = Planning.ensureToday(data(), at(tue, 6))
        val s = NowResolver.resolve(d.todayPlan!!, d, at(tue, 8, 45))
        assertTrue(s.headline.startsWith("Trading"), s.headline)
        val makeups = d.tasks.filter { it.sourceKey?.startsWith("makeup:") == true }
        assertEquals(1, makeups.size, makeups.joinToString { it.title })
        assertTrue(makeups.first().title.contains("ME 3411"))
        assertNotNull(makeups.first().projectId)
        val again = Planning.replan(d, at(tue, 10))
        assertEquals(1, again.tasks.count { it.sourceKey?.startsWith("makeup:") == true })
    }

    // ---- Hot + habits ----

    @Test
    fun `hot deadline takes over habit blocks that yield, not hard ones`() {
        val exam = Task(title = "Study for big exam", estMin = 600, dueAt = at(sun.plusDays(1), 9), priority = Priority.HIGH, createdAt = at(tue, 5))
        val d = data().let { it.copy(tasks = it.tasks.filter { t -> t.fixedStartMin != null } + exam) }
        val plan = DayPlanner().plan(sun, d, at(sun, 6))
        assertFalse(plan.blocks.any { it.title == "CSWE practice" }, "CSWE gave way")
        assertTrue(plan.blocks.any { it.title.startsWith("Weekly review") }, "review doesn't yield")
        assertTrue(plan.blocks.any { it.title == "Off: work stops" }, "protected stays")
        assertTrue(plan.note.contains("gave way"))
        assertTrue(plan.blocks.any { it.taskId == exam.id && it.hot })
    }

    @Test
    fun `without a hot deadline habits keep their slot`() {
        // Since v0.11 the weekly habit blocks are practice tasks with a fixed time.
        val plan = DayPlanner().plan(sun, data().let { it.copy(tasks = it.tasks.filter { t -> t.fixedStartMin != null }) }, at(sun, 6))
        assertTrue(plan.blocks.any { it.title == "CSWE practice" && it.taskId != null })
    }

    // ---- Pick 3 ----

    @Test
    fun `picked tasks are planned first and marked`() {
        val low = Task(title = "Low thing", estMin = 30, priority = Priority.LOW, createdAt = at(tue, 5))
        var d = data().copy(tasks = (1..8).map { Task(title = "Normal $it", estMin = 90, createdAt = at(tue, 5)) } + low)
        d = Actions.setPicks(d, tue, listOf(low.id), at(tue, 4))
        val plan = DayPlanner().plan(tue, d, at(tue, 5))
        val first = plan.blocks.filter { it.kind == BlockKind.TASK }.minByOrNull { it.start }
        assertEquals(low.id, first?.taskId)
        assertTrue(first!!.picked)
    }

    // ---- Exams from calendar ----

    @Test
    fun `exam events without a study task are offered once`() {
        val now = at(tue, 9)
        val ev = CalEvent(uid = "e1", title = "Thermo Exam 2", start = at(tue.plusDays(9), 10), end = at(tue.plusDays(9), 11), source = "phone")
        var d = data().copy(events = listOf(ev), settings = data().settings.copy(autoExamTasks = true))
        assertEquals(listOf("e1"), Generated.examCandidates(d, now).map { it.uid })
        val t = Generated.studyTaskFor(d, ev, now)
        d = Actions.addTask(d, t)
        assertTrue(Generated.examCandidates(d, now).isEmpty())
        assertEquals("a-school", d.projects.first { it.id == t.projectId }.areaId)
        val ignored = Actions.ignoreExam(data().copy(events = listOf(ev)), "e1")
        assertTrue(Generated.examCandidates(ignored, now).isEmpty())
    }

    @Test
    fun `research dates can be replaced`() {
        val d = Actions.setResearchDates(data(), listOf("2026-11-14"))
        val dated = d.anchors.filter { it.dates.isNotEmpty() }
        assertEquals(4, dated.size)
        assertTrue(dated.all { it.dates == setOf("2026-11-14") })
        assertNull(d.anchors.firstOrNull { "2026-10-24" in it.dates })
    }
}
