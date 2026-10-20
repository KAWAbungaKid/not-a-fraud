package com.maven.notafraud.core

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.DayContext
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.plan.Anchors
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.NowResolver
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlannerTest {
    private val zone = ZoneId.of("America/Chicago")
    private val tue = LocalDate.parse("2026-10-20")
    private val fri = LocalDate.parse("2026-10-23")
    private val sessionSat = LocalDate.parse("2026-10-24")
    private val plainSat = LocalDate.parse("2026-11-14")
    private val sun = LocalDate.parse("2026-10-25")

    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)
    private val seedNow = at(tue, 5)
    private fun data() = Seed.data(seedNow)

    @Test
    fun `tuesday has the three morning classes, lab and lift`() {
        val titles = Anchors.on(tue, data().anchors).map { it.title }
        assertTrue("ME 3411 Dynamic Systems" in titles)
        assertTrue("ME 3521 Applied Thermo" in titles)
        assertTrue("ME 3525 Heat Transfer" in titles)
        assertTrue("ME 4840 Lab" in titles)
        assertTrue("Lift" in titles)
        assertFalse("Market open" in titles, "Tue/Thu are protected for ME 3411")
    }

    @Test
    fun `session saturday replaces the craft block with research`() {
        val titles = Anchors.on(sessionSat, data().anchors).map { it.title }
        assertTrue(titles.any { it.startsWith("Biosensor: load") })
        assertFalse("Craft block: build" in titles)
        val plain = Anchors.on(plainSat, data().anchors).map { it.title }
        assertTrue("Craft block: build" in plain)
    }

    @Test
    fun `no classes thanksgiving week`() {
        val titles = Anchors.on(LocalDate.parse("2026-11-24"), data().anchors).map { it.title }
        assertFalse(titles.any { it.startsWith("ME 3411") })
    }

    @Test
    fun `tasks never land inside fixed blocks`() {
        val plan = DayPlanner().plan(tue, data(), at(tue, 5))
        val fixed = plan.blocks.filter { it.kind == BlockKind.ANCHOR || it.kind == BlockKind.EVENT }
        val tasks = plan.blocks.filter { it.kind == BlockKind.TASK }
        assertTrue(tasks.isNotEmpty(), "expected some task blocks")
        for (t in tasks) for (f in fixed) {
            assertFalse(t.start < f.end && f.start < t.end, "${t.title} overlaps ${f.title}")
        }
    }

    @Test
    fun `nothing is scheduled during date night or sunday evening`() {
        val d = data()
        for (day in listOf(fri, sun)) {
            val plan = DayPlanner().plan(day, d, at(day, 5))
            val protectedBlocks = plan.blocks.filter { it.protected }
            assertTrue(protectedBlocks.isNotEmpty())
            val tasks = plan.blocks.filter { it.kind == BlockKind.TASK }
            for (t in tasks) for (p in protectedBlocks) assertFalse(t.start < p.end && p.start < t.end)
        }
    }

    @Test
    fun `promoted midweek idea waits for friday craft block`() {
        val d0 = data().copy(tasks = emptyList())
        val tueNoon = at(tue, 12)
        val d1 = Actions.captureIdea(d0, "Build a 3D printed phone stand", tueNoon)
        val d2 = Actions.promoteIdea(d1, d1.ideas.first().id, tueNoon)
        val wed = LocalDate.parse("2026-10-21")
        assertTrue(DayPlanner().plan(wed, d2, at(wed, 5)).blocks.none { it.kind == BlockKind.TASK })
        val friPlan = DayPlanner().plan(fri, d2, at(fri, 5))
        val ideaBlocks = friPlan.blocks.filter { it.kind == BlockKind.TASK }
        assertTrue(ideaBlocks.isNotEmpty(), "idea should be schedulable on Friday")
        assertTrue(ideaBlocks.all { it.start >= at(fri, 16) })
    }

    @Test
    fun `rough day caps focus and lowers energy`() {
        val many = (1..12).map { Task(title = "task $it", estMin = 120, priority = Priority.NORMAL, energy = Energy.MED, createdAt = seedNow) }
        val d = Actions.upsertDay(data().copy(tasks = many), DayContext(date = sun.toString(), sleepHours = 4.5))
        val plan = DayPlanner().plan(sun, d, at(sun, 5))
        assertTrue(plan.roughDay)
        assertEquals(Energy.LOW, plan.energy)
        val focus = plan.blocks.filter { it.kind == BlockKind.TASK }.sumOf { it.minutes }
        assertTrue(focus <= 240, "focus $focus should be capped at 240")
        assertTrue(plan.blocks.filter { it.kind == BlockKind.TASK }.all { it.minutes <= 50 })
    }

    @Test
    fun `plan only places tasks after now`() {
        val now = at(tue, 13, 7)
        val plan = DayPlanner().plan(tue, data(), now)
        assertTrue(plan.blocks.filter { it.kind == BlockKind.TASK }.all { it.start >= now })
    }

    @Test
    fun `now resolver reports class and next block`() {
        val d = data()
        val plan = DayPlanner().plan(tue, d, at(tue, 5))
        val state = NowResolver.resolve(plan, d, at(tue, 8, 10))
        assertEquals("ME 3411 Dynamic Systems", state.headline)
        assertTrue(state.detail.contains("Next"))
    }

    @Test
    fun `empty data still produces a plan`() {
        val plan = DayPlanner().plan(tue, AppData(), at(tue, 9))
        assertTrue(plan.blocks.isEmpty())
        val s = NowResolver.resolve(plan, AppData(), at(tue, 9))
        assertTrue(s.headline.isNotBlank())
    }
}
