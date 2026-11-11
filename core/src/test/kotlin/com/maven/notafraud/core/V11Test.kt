package com.maven.notafraud.core

import com.maven.notafraud.core.guard.Ladder
import com.maven.notafraud.core.guard.UnlockCheckIn
import com.maven.notafraud.core.model.Anchor
import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.model.WorkSession
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayPlan
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.DayReview
import com.maven.notafraud.core.plan.PlanBlock
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.14: planned vs actual, day score, priority nag, check-in, backlog, ending a block early. */
class V11Test {
    private val zone = ZoneId.of("America/Chicago")
    private val day = LocalDate.parse("2026-11-10")
    private fun at(h: Int, m: Int = 0, date: LocalDate = day) = Time.at(date, h * 60 + m, zone)

    private val hw = Task(id = "hw", title = "hw8", estMin = 120, dueAt = at(23, 59, day.plusDays(2)))
    private val trading = Task(id = "tr", title = "Trading review", estMin = 600)
    private val test = Task(id = "test", title = "Study for Thursday test", estMin = 240, dueAt = at(9, 0, day.plusDays(2)), priority = Priority.HIGH)

    private fun base() = Seed.data(at(4)).let {
        it.copy(
            routine = emptyList(), tasks = listOf(hw, trading, test), sessions = emptyList(), anchors = emptyList(), events = emptyList(),
            settings = it.settings.copy(routineSince = null, ladder = it.settings.ladder.copy(enabled = false)),
            todayPlan = DayPlan(
                day.toString(),
                listOf(
                    PlanBlock(at(13, 50), at(14, 15), BlockKind.TASK, "hw8", taskId = "hw"),
                    PlanBlock(at(14, 20), at(14, 45), BlockKind.TASK, "hw8", taskId = "hw"),
                    PlanBlock(at(15, 0), at(15, 50), BlockKind.TASK, "Study", taskId = "test"),
                ),
                roughDay = false, focusCapMin = null, energy = Energy.MED, generatedAt = at(6),
            ),
        )
    }

    @Test
    fun `blocks are graded done, swapped, missed or explained`() {
        var d = base()
        d = Actions.logPast(d, "hw", null, at(13, 50), at(14, 15), at(16))
        d = Actions.logPast(d, "tr", null, at(14, 20), at(14, 45), at(16))
        val b = d.todayPlan!!.blocks
        assertEquals(DayReview.Mark.DONE, DayReview.mark(d, b[0], at(16))!!.mark)
        assertEquals(DayReview.Mark.OTHER, DayReview.mark(d, b[1], at(16))!!.mark)
        assertEquals("Trading review", DayReview.mark(d, b[1], at(16))!!.instead)
        assertEquals(DayReview.Mark.MISSED, DayReview.mark(d, b[2], at(16))!!.mark)
        assertEquals(1, DayReview.missed(d, at(16)).size)

        val honest = UnlockCheckIn.excuse(d, b[2].start, b[2].end, "nothing", counts = false, now = at(16))
        assertEquals(DayReview.Mark.MISSED, DayReview.mark(honest, b[2], at(16))!!.mark, "\"nothing\" is answered but still missed")
        val excused = UnlockCheckIn.excuse(d, b[2].start, b[2].end, "class ran late", counts = true, now = at(16))
        assertEquals(DayReview.Mark.EXCUSED, DayReview.mark(excused, b[2], at(16))!!.mark)
        assertEquals(0, DayReview.missed(excused, at(16)).size)
    }

    @Test
    fun `logging past work earns credits and is not double counted`() {
        var d = base()
        val before = d.ledger.balanceMin
        d = Actions.logPast(d, "hw", null, at(13), at(14), at(16))
        d = Actions.logPast(d, "hw", null, at(13, 30), at(14, 30), at(16))
        assertEquals(90, d.sessions.sumOf { it.minutes })
        assertEquals(90, d.tasks.first { it.id == "hw" }.loggedMin)
        assertTrue(d.ledger.balanceMin > before)
        val other = Actions.logPast(base(), null, "helped Dad", at(10), at(11), at(16))
        assertEquals("helped Dad", other.sessions.single().title)
    }

    @Test
    fun `day score rewards doing the plan`() {
        val idle = base()
        val worked = Actions.logPast(Actions.logPast(base(), "hw", null, at(13, 50), at(14, 45), at(16)), "test", null, at(15), at(15, 50), at(16))
        val s0 = DayReview.score(idle, day, at(16))!!.value
        val s1 = DayReview.score(worked, day, at(16))!!.value
        assertTrue(s1 > s0, "$s1 > $s0")
        assertTrue(s1 >= 90)
    }

    @Test
    fun `priority nag names the important thing you've been avoiding`() {
        var d = Actions.logPast(base(), "tr", null, at(8), at(11), at(11))
        val n = assertNotNull(DayReview.priorityNag(d, at(11, 5)))
        assertEquals("test", n.task.id)
        assertTrue(n.text.contains("3h") && n.text.contains("Trading review"), n.text)
        d = Actions.logPast(d, "test", null, at(11), at(12), at(12))
        assertNull(DayReview.priorityNag(d, at(12, 5)))
    }

    @Test
    fun `check-in asks about missed blocks and Later adds a ladder point on the third`() {
        var d = base()
        val p = UnlockCheckIn.prompts(d, at(16))
        assertTrue(p.any { it.kind == UnlockCheckIn.Kind.BLOCK }, p.toString())
        assertTrue(UnlockCheckIn.due(d, at(16)))
        d = UnlockCheckIn.later(d, at(16))
        assertTrue(!UnlockCheckIn.due(d, at(16, 10)), "hidden for 20 min")
        assertTrue(UnlockCheckIn.due(d, at(16, 21)))
        d = UnlockCheckIn.later(UnlockCheckIn.later(d, at(16, 21)), at(16, 42))
        assertTrue(UnlockCheckIn.ignoring(d, at(16, 43)))
        d = d.copy(settings = d.settings.copy(ladder = d.settings.ladder.copy(enabled = true, bedtimeBrick = false)))
        assertTrue(Ladder.state(d, at(16, 43)).reasons.any { it.contains("check-in") })
        d = UnlockCheckIn.handled(d, "block:${at(15)}", at(16, 44))
        assertTrue(!UnlockCheckIn.ignoring(d, at(16, 45)))
    }

    @Test
    fun `ending a class early frees the rest of its slot for tasks`() {
        val lab = Anchor(id = "lab", title = "ME 4840 Lab", kind = com.maven.notafraud.core.model.AnchorKind.CLASS, startMin = 15 * 60, endMin = 16 * 60 + 50, days = setOf(day.dayOfWeek.value))
        var d = base().copy(anchors = listOf(lab), todayPlan = null)
        d = Planning.ensureToday(d, at(14, 55))
        val block = d.todayPlan!!.blocks.first { it.anchorId == "lab" }
        d = Actions.endBlockEarly(d, DayPlanner.endKey(block), at(16))
        d = Planning.replan(d, at(16))
        val labNow = d.todayPlan!!.blocks.first { it.anchorId == "lab" }
        assertEquals(at(16), labNow.end)
        assertTrue(d.todayPlan!!.blocks.any { it.kind == BlockKind.TASK && it.start >= at(16) && it.start < at(16, 50) }, d.todayPlan!!.blocks.joinToString { "${it.title} ${Time.clock(it.start, zone)}" })
        assertNotNull(DayReview.fill(d, at(16), 50))
    }

    @Test
    fun `summary shows the evening and yesterday's next morning`() {
        val d = Actions.logPast(base(), "hw", null, at(13, 50), at(14, 45), at(16))
        val s = DayReview.summary(d, day, at(21, 30))
        assertEquals("hw8", s.top.first().first)
        assertEquals(day, DayReview.summaryDate(d, at(21, 30)))
        assertEquals(day, DayReview.summaryDate(d, at(8, 0, day.plusDays(1))))
        val seen = Actions.summarySeen(d, day)
        assertNull(DayReview.summaryDate(seen, at(8, 0, day.plusDays(1))))
        assertTrue(d.tasks.first { it.id == "hw" }.status == TaskStatus.TODO)
        @Suppress("UNUSED_VARIABLE") val unused = WorkSession(title = "x", start = 0, end = 0, minutes = 0)
    }
}
