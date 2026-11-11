package com.maven.workout.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 0.3: drop sets, either / or slots, equipment. */
class OptionsTest {
    private val mon = LocalDate.parse("2026-10-26")
    private val seed = Seed.data(mon.toString())

    private fun withPullOptions(d: WorkoutData): WorkoutData {
        val p = d.program()!!
        return Actions.setAlternatives(d, p.id, "pull", "chin-up", listOf("lat-pulldown"))
    }

    @Test
    fun `either-or slot remembers what you picked last time`() {
        var d = withPullOptions(seed)
        d = Actions.start(d, "pull", Mode.NORMAL, mon, 1_000L)
        val ei = d.active!!.entries.indexOfFirst { it.slot == "chin-up" }
        assertTrue(ei >= 0)
        assertEquals("chin-up", d.active!!.entries[ei].exerciseId)
        d = Actions.chooseOption(d, ei, "lat-pulldown", mon)
        assertEquals("lat-pulldown", d.active!!.entries[ei].exerciseId)
        d = Actions.completeSet(d, ei, 0, 2_000L).let { Actions.editSet(it, ei, 0) { s -> s.copy(weight = s.weight ?: 100.0, reps = s.reps ?: 10) } }
        d = Actions.finish(d, 3_000L).first
        d = Actions.start(d, "pull", Mode.NORMAL, mon.plusDays(3), 4_000L)
        assertEquals("lat-pulldown", d.active!!.entries.first { it.slot == "chin-up" }.exerciseId)
    }

    @Test
    fun `drop sets go under their set, lighter, and don't count as hard sets or maxes`() {
        var d = Actions.start(seed, "pull", Mode.NORMAL, mon, 1_000L)
        val ei = 0
        d = Actions.editSet(d, ei, 1) { it.copy(weight = 200.0, reps = 8, kind = SetKind.WORK) }
        d = Actions.completeSet(d, ei, 1, 2_000L)
        d = Actions.addDrop(d, ei, 1)
        d = Actions.addDrop(d, ei, 1)
        val sets = d.active!!.entries[ei].sets
        assertEquals(SetKind.DROP, sets[2].kind)
        assertEquals(SetKind.DROP, sets[3].kind)
        assertEquals(160.0, sets[2].targetWeight)
        assertTrue(sets[3].targetWeight!! < 160.0)
        d = Actions.editSet(d, ei, 2) { it.copy(reps = 6) }
        d = Actions.completeSet(d, ei, 2, 2_100L)
        val fin = Actions.finish(d, 3_000L).first
        assertTrue(Volume.week(fin, mon).values.all { it == 1 }, Volume.week(fin, mon).toString())
        val s = Actions.finish(d, 3_000L).second!!
        assertEquals(1, s.entries[ei].sets.count { it.kind == SetKind.DROP && it.done })
    }

    @Test
    fun `equipment decides plates`() {
        val cable = Exercise("c", "Cable High Row")
        assertEquals(Equipment.STACK, Equip.of(cable))
        assertNull(Equip.loadText(seed, cable, 100.0))
        assertEquals(Equipment.DUMBBELL, Equip.of(Exercise("d", "DB curl")))
        assertEquals(Equipment.BARBELL, Equip.of(Exercise("b", "Barbell row")))
        val home = Exercise("h", "Home pulldown", equipment = Equipment.PULLEY, pulleyRatio = 2.0)
        val t = assertNotNull(Equip.loadText(seed, home, 50.0))
        assertTrue(t.contains("Load 100"), t)
    }
}
