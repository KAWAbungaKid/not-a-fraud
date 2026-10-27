package com.maven.notafraud.core

import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Lock
import com.maven.notafraud.core.workout.WorkoutLink
import com.maven.notafraud.core.workout.WorkoutStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class V8Test {
    private val zone = ZoneId.of("America/Chicago")
    private val day = LocalDate.parse("2026-10-27")
    private fun at(h: Int) = Time.at(day, h * 60, zone)

    @Test
    fun `numbers apply now unless they loosen`() {
        var d = Seed.data(at(4))
        d = Lock.setNumber(d, "maxChunkMin", 60, "x", at(20))
        assertEquals(60, d.settings.maxChunkMin)
        d = Lock.setNumber(d, "messageWindowMin", 15, "x", at(20))
        assertEquals(10, d.settings.messageWindowMin)
        assertEquals(1, d.pending.size)
        d = Lock.setNumber(d, "messageWindowMin", 5, "x", at(20))
        assertEquals(5, d.settings.messageWindowMin)
        assertEquals(0, d.pending.size, "tightening cancels the waiting looser change")
    }

    @Test
    fun `workout routine follows the workout app's days a week`() {
        val d = WorkoutLink.receive(Seed.data(at(4)), WorkoutStatus(today = day.toString(), targetPerWeek = 5), at(9))
        assertEquals(5, d.routine.first { it.id == "r-workout" }.weeklyTarget)
    }
}
