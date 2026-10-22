package com.maven.notafraud.core

import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.AnchorKind
import com.maven.notafraud.core.model.AutoCheck
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.RoutineItem
import com.maven.notafraud.core.model.Semester
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskKind
import com.maven.notafraud.core.model.liveTasks
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.seed.Migration
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.stats.Stats
import com.maven.notafraud.core.store.Actions
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V5Test {
    private val zone = ZoneId.of("America/Chicago")
    private val thu = LocalDate.parse("2026-10-22")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)
    private fun base() = Seed.data(at(thu, 4)).copy(tasks = emptyList())

    @Test
    fun `v6 turns hard-coded ids into fields and keeps the semester`() {
        val old = base().copy(
            schema = 5, semester = null,
            routine = base().routine.map { it.copy(autoCheck = null) },
            areas = base().areas.map { it.copy(stat = null) },
        )
        val v6 = Migration.apply(old)
        assertEquals(AutoCheck.WAKE, v6.routine.first { it.id == "r-wake" }.autoCheck)
        assertEquals("work", v6.areas.first { it.id == "a-research" }.stat)
        assertNotNull(v6.semester)
        assertEquals(v6, Migration.apply(v6))
    }

    @Test
    fun `any routine item can be the one picking 3 checks off`() {
        var d = base().copy(routine = listOf(RoutineItem(id = "mine", title = "Plan tomorrow", category = RoutineCategory.OTHER, startMin = 1200, dueMin = 1260, angryMin = 1290, autoCheck = AutoCheck.PICK3)))
        val t = Task(title = "X", createdAt = 0)
        d = d.copy(tasks = listOf(t))
        d = Actions.setPicks(d, thu.plusDays(1), listOf(t.id), at(thu, 20))
        assertTrue(RoutinePolicy.day(d, thu).done.containsKey("mine"))
    }

    @Test
    fun `stats follow the area's stat field`() {
        var d = base()
        val custom = d.areas.first { it.id == "a-making" }
        d = d.copy(areas = d.areas.map { if (it.id == custom.id) it.copy(stat = "work") else it })
        assertTrue(custom.id in Stats.areasFor(d, "work"))
        assertFalse(Stats.all(d, thu).any { it.key == "social" }, "check-in stats are off by default")
    }

    @Test
    fun `semester dates move every class and ending it archives classes`() {
        var d = base()
        d = Actions.setSemester(d, Semester("Spring 2027", "2027-01-19", "2027-05-07", setOf("2027-03-15")))
        assertTrue(d.anchors.filter { it.kind == AnchorKind.CLASS }.all { it.untilDate == "2027-05-07" && "2027-03-15" in it.skipDates })
        d = Actions.saveClass(d, "ME 4000 Design", listOf(Anchor(title = "", kind = AnchorKind.CLASS, startMin = 600, endMin = 690, days = setOf(1, 3))), null, 120, at(thu, 9))
        val proj = d.projects.first { it.name == "ME 4000 Design" }
        assertTrue(d.tasks.any { it.projectId == proj.id && it.kind == TaskKind.PRACTICE })
        d = d.copy(tasks = d.tasks + Task(title = "Homework 1", projectId = proj.id, createdAt = 0))
        d = Actions.endSemester(d, setOf(proj.id), at(thu, 9))
        assertNull(d.semester)
        assertTrue(d.anchors.none { it.kind == AnchorKind.CLASS })
        assertTrue(d.liveTasks().none { it.projectId == proj.id })
        assertTrue(d.tasks.any { it.title == "Homework 1" }, "tasks are kept, just not planned")
        val plan = DayPlanner().plan(thu.plusDays(4), d, at(thu.plusDays(4), 6))
        assertTrue(plan.blocks.none { it.projectId == proj.id })
    }

    @Test
    fun `manual low priority folds into importance`() {
        val d = base()
        val p = d.projects.first { it.areaId == "a-research" }
        val old = d.copy(schema = 5, tasks = listOf(Task(title = "Meh", projectId = p.id, priority = Priority.LOW, createdAt = 0)))
        val t = Migration.apply(old).tasks.single()
        assertEquals(Priority.NORMAL, t.priority)
        assertEquals(4, t.importance)
    }
}
