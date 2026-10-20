package com.maven.notafraud.core

import com.maven.notafraud.core.model.Energy
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.score.Scorer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScorerTest {
    private val now = 1_800_000_000_000L

    private fun task(
        estMin: Int = 60, dueInHours: Double? = null, priority: Priority = Priority.NORMAL,
        energy: Energy = Energy.MED, minBlock: Int = 15, logged: Int = 0, lastWorkedDaysAgo: Double = 0.0,
    ) = Task(
        title = "t", estMin = estMin, dueAt = dueInHours?.let { now + (it * Time.HOUR).toLong() },
        priority = priority, energy = energy, minBlockMin = minBlock, loggedMin = logged,
        createdAt = now - (lastWorkedDaysAgo * Time.DAY).toLong(), lastWorkedAt = now - (lastWorkedDaysAgo * Time.DAY).toLong(),
    )

    @Test
    fun `no due date gives urgency 8`() {
        assertEquals(8.0, Scorer.breakdown(task(), now, 60, Energy.MED).urgency, 1e-9)
    }

    @Test
    fun `urgency formula before deadline`() {
        // 60 min left, due in 13h -> slack 12h -> 100/(1+1) = 50
        assertEquals(50.0, Scorer.breakdown(task(dueInHours = 13.0), now, 60, Energy.MED).urgency, 1e-9)
    }

    @Test
    fun `urgency decays after deadline instead of saturating`() {
        // due 35h ago with 60 min left -> slack -36h -> 100/(1+1) = 50
        assertEquals(50.0, Scorer.breakdown(task(dueInHours = -35.0), now, 60, Energy.MED).urgency, 1e-9)
    }

    @Test
    fun `overdue low-priority chore does not outrank exam due in 8 hours`() {
        val chore = task(estMin = 30, dueInHours = -72.0, priority = Priority.LOW, lastWorkedDaysAgo = 3.0)
        val exam = task(estMin = 240, dueInHours = 8.0, priority = Priority.HIGH, energy = Energy.HIGH, minBlock = 45)
        val choreScore = Scorer.score(chore, now, 90, Energy.MED)
        val examScore = Scorer.score(exam, now, 90, Energy.MED)
        assertTrue(examScore > choreScore, "exam $examScore should beat chore $choreScore")
    }

    @Test
    fun `fit penalizes windows smaller than minimum block`() {
        assertEquals(0.2, Scorer.breakdown(task(minBlock = 45), now, 30, Energy.MED).fit, 1e-9)
        assertEquals(1.15, Scorer.breakdown(task(estMin = 30), now, 60, Energy.MED).fit, 1e-9)
        assertEquals(1.0, Scorer.breakdown(task(estMin = 120), now, 60, Energy.MED).fit, 1e-9)
    }

    @Test
    fun `energy multipliers`() {
        assertEquals(1.15, Scorer.breakdown(task(energy = Energy.LOW), now, 60, Energy.LOW).energy, 1e-9)
        assertEquals(0.45, Scorer.breakdown(task(energy = Energy.HIGH), now, 60, Energy.LOW).energy, 1e-9)
        assertEquals(0.85, Scorer.breakdown(task(energy = Energy.LOW), now, 60, Energy.HIGH).energy, 1e-9)
        assertEquals(1.0, Scorer.breakdown(task(energy = Energy.MED), now, 60, Energy.HIGH).energy, 1e-9)
    }

    @Test
    fun `neglect grows 3 per day and caps at 18`() {
        assertEquals(6.0, Scorer.breakdown(task(lastWorkedDaysAgo = 2.0), now, 60, Energy.MED).neglect, 1e-6)
        assertEquals(18.0, Scorer.breakdown(task(lastWorkedDaysAgo = 30.0), now, 60, Energy.MED).neglect, 1e-6)
    }

    @Test
    fun `minutes left floors at 10`() {
        assertEquals(10, Scorer.minutesLeft(task(estMin = 60, logged = 90)))
        assertEquals(10, Scorer.minutesLeft(task(estMin = 5)))
    }
}
