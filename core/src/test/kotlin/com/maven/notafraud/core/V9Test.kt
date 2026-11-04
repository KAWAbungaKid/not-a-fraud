package com.maven.notafraud.core

import com.maven.notafraud.core.diet.DietLink
import com.maven.notafraud.core.diet.DietStatus
import com.maven.notafraud.core.diet.SyncNag
import com.maven.notafraud.core.model.AutoCheck
import com.maven.notafraud.core.model.RoutineCategory
import com.maven.notafraud.core.model.RoutineItem
import com.maven.notafraud.core.model.routineFor
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.routine.Mood
import com.maven.notafraud.core.routine.RoutinePolicy
import com.maven.notafraud.core.seed.Migration
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.core.workout.WorkoutStatus
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class V9Test {
    private val zone = ZoneId.of("America/Chicago")
    private val day = LocalDate.parse("2026-11-03")
    private fun at(h: Int, m: Int = 0, date: LocalDate = day) = Time.at(date, h * 60 + m, zone)
    private fun base() = Seed.data(at(4)).copy(settings = Seed.data(at(4)).settings.copy(routineSince = null))

    @Test
    fun `migration links meals to Twig slots and cardio to sports`() {
        val old = Seed.data(at(4)).let { d ->
            d.copy(schema = 8, routine = d.routine.map { it.copy(dietSlot = null, autoCheck = if (it.id == "r-cardio") null else it.autoCheck, title = if (it.id == "r-workout") "Workout (lift, climb...)" else it.title) })
        }
        val v9 = Migration.apply(old, at(4))
        assertEquals(9, v9.schema)
        assertEquals("Lunch", v9.routine.first { it.id == "r-lunch" }.dietSlot)
        assertEquals("Supper", v9.routine.first { it.id == "r-supper" }.dietSlot)
        assertEquals("Pre-workout", v9.routine.first { it.id == "r-meal1" }.dietSlot)
        assertEquals(AutoCheck.CARDIO, v9.routine.first { it.id == "r-cardio" }.autoCheck)
        assertEquals("Workout", v9.routine.first { it.id == "r-workout" }.title)
    }

    @Test
    fun `Twig ticks meals and drives their anger`() {
        var d = base().let { b -> b.copy(routine = b.routine.filter { it.id == "r-lunch" }) }
        val lunch = d.routine.first { it.id == "r-lunch" }
        // Twig nagging at level 2 about lunch → angry here, with Twig's text.
        d = DietLink.receive(d, DietStatus(today = day.toString(), nags = listOf(SyncNag("MEAL", "meal:Lunch", "Eat: Lunch", "Eat now", 2))), at(14))
        val st = RoutinePolicy.status(d, lunch, at(14))
        assertEquals(Mood.ANGRY, st.mood)
        assertTrue(st.fromDiet)
        val alerts = RoutinePolicy.alertsDue(d, at(14))
        assertTrue(alerts.any { it.item.id == lunch.id && it.fromDiet })
        // Logged in Twig → ticked here.
        d = DietLink.receive(d, DietStatus(today = day.toString(), slotsLogged = listOf("Lunch")), at(14, 10))
        assertTrue(RoutinePolicy.day(d, day).done.containsKey(lunch.id))
        assertEquals("Not a Twig", RoutinePolicy.day(d, day).notes[lunch.id])
    }

    @Test
    fun `Twig water and weigh-in reminders come through routine alerts`() {
        val d = DietLink.receive(base().copy(routine = emptyList()), DietStatus(today = day.toString(), nags = listOf(SyncNag("WATER", "water", "Drink water", "30 oz behind", 1))), at(11))
        val a = RoutinePolicy.alertsDue(d, at(11)).firstOrNull { it.item.id == "twig:water" }
        assertNotNull(a)
        assertEquals(Mood.LATE, a.mood)
        // Already nagged once at level 1: not again.
        val after = Actions.markAlerted(d, listOf("twig:water"), at(11))
        assertTrue(RoutinePolicy.alertsDue(after, at(11, 40)).none { it.item.id == "twig:water" })
        // Routing off: nothing.
        val off = d.copy(settings = d.settings.copy(dietNags = false))
        assertTrue(RoutinePolicy.alertsDue(off, at(11)).none { it.fromDiet })
    }

    @Test
    fun `streaks hold by themselves until broken`() {
        var d = base()
        val item = RoutineItem(id = "s1", title = "No soda", category = RoutineCategory.STREAK, startMin = 0, dueMin = 0, angryMin = 0, streakSince = day.minusDays(9).toString())
        d = Actions.upsertRoutine(d, item)
        var st = RoutinePolicy.status(d, d.routine.first { it.id == "s1" }, at(9))
        assertEquals(Mood.DONE, st.mood)
        assertEquals(10, st.streak)
        assertTrue(RoutinePolicy.alertsDue(d, at(23 - 2)).none { it.item.id == "s1" })
        assertTrue(RoutinePolicy.missedOn(d, day.minusDays(1)).none { it.id == "s1" })
        // Broke it 3 days ago: today is day 3 again, best (the 6 days before) stays.
        d = Actions.breakStreak(d, "s1", day.minusDays(3), at(20, 0, day.minusDays(3)))
        st = RoutinePolicy.status(d, d.routine.first { it.id == "s1" }, at(9))
        assertEquals(3, st.streak)
        assertEquals(6, st.best)
        // Broken today.
        d = Actions.breakStreak(d, "s1", day, at(9))
        st = RoutinePolicy.status(d, d.routine.first { it.id == "s1" }, at(9, 5))
        assertEquals(Mood.MISSED, st.mood)
        assertEquals(0, st.streak)
        d = Actions.unbreakStreak(d, "s1", day)
        assertEquals(Mood.DONE, RoutinePolicy.status(d, d.routine.first { it.id == "s1" }, at(9, 6)).mood)
    }

    @Test
    fun `with Troglodyte connected a sport session is cardio, not a workout`() {
        var d = base().copy(workout = WorkoutStatus(today = day.toString()))
        d = Actions.practiceSkill(d, "sk-backflip", 60, at(9))
        val tid = d.skills.first { it.id == "sk-backflip" }.practiceTaskId!!
        d = Actions.startSession(d, tid, null, at(17))
        d = Actions.stopSession(d, at(17, 40))
        val rd = RoutinePolicy.day(d, day)
        assertTrue(rd.done.containsKey(d.routineFor(AutoCheck.CARDIO)!!.id))
        assertFalse(rd.done.containsKey(d.routineFor(AutoCheck.WORKOUT)!!.id))
    }

    @Test
    fun `with both apps the meal schedule is Twig's`() {
        val slots = listOf(
            com.maven.notafraud.core.diet.MealSlot("Lunch", "13:00"), com.maven.notafraud.core.diet.MealSlot("Pre-workout", "16:30"),
            com.maven.notafraud.core.diet.MealSlot("Supper", "19:30"), com.maven.notafraud.core.diet.MealSlot("Snacks"),
        )
        val custom = RoutineItem(id = "m-x", title = "Second breakfast", category = RoutineCategory.MEAL, startMin = 9 * 60, dueMin = 10 * 60, angryMin = 11 * 60)
        var d = Actions.upsertRoutine(base(), custom)
        d = DietLink.receive(d, DietStatus(today = day.toString(), slots = slots), at(9))
        val pre = d.routine.first { it.id == "r-meal1" }
        assertEquals(16 * 60 + 30, pre.dueMin)
        assertEquals(15 * 60, pre.startMin, "keeps its 90-minute lead-in")
        assertEquals(13 * 60, d.routine.first { it.id == "r-lunch" }.dueMin)
        val paused = d.routine.first { it.id == "m-x" }
        assertTrue(paused.pausedByDiet && !paused.enabled)
        assertEquals(3, d.routine.count { it.category == RoutineCategory.MEAL && it.enabled })
        // A new slot in Twig becomes a routine meal; Twig going away brings the paused one back.
        d = DietLink.receive(d, DietStatus(today = day.toString(), slots = slots + com.maven.notafraud.core.diet.MealSlot("Breakfast", "08:00")), at(9, 5))
        assertTrue(d.routine.any { it.dietSlot == "Breakfast" && it.dueMin == 8 * 60 && it.enabled })
        d = DietLink.disconnect(d)
        assertTrue(d.routine.first { it.id == "m-x" }.enabled)
    }
}
