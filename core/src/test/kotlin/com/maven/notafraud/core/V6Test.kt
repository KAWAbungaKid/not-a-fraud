package com.maven.notafraud.core

import com.maven.notafraud.core.model.BlockProfile
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.CalMode
import com.maven.notafraud.core.model.ChangeKind
import com.maven.notafraud.core.model.Horizon
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.UsageDay
import com.maven.notafraud.core.model.liveTasks
import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.Homework
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Migration
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.core.store.Lock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class V6Test {
    private val zone = ZoneId.of("America/Chicago")
    private val sun = LocalDate.parse("2026-11-15")
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)
    private fun base() = Seed.data(at(sun, 4)).copy(tasks = emptyList(), anchors = emptyList())

    @Test
    fun `homework gets 2 hours, aims 2 days early, and a big one spreads over days`() {
        val hw = CalEvent(uid = "a1", title = "Big report", start = at(sun.plusDays(5), 23, 59), end = at(sun.plusDays(5), 23, 59), source = "phone")
        var d = Homework.sync(base(), listOf(hw), at(sun, 6))
        val t = d.tasks.single()
        assertEquals(120, t.estMin)
        assertEquals(t.dueAt!! - 2 * Time.DAY, t.targetAt)
        // You say it'll take 6 hours.
        d = d.copy(tasks = d.tasks.map { it.copy(estMin = 360) })
        val first = DayPlanner().plan(sun, d, at(sun, 6)).blocks.filter { it.taskId == t.id }.sumOf { it.minutes }
        assertTrue(first in 90..130, "first day $first min")
        val week = Planning.ensureToday(d, at(sun, 6)).let { Planning.withWeek(it, at(sun, 6)) }
        val days = (listOf(week.todayPlan!!) + week.weekPlan).count { p -> p.blocks.any { it.taskId == t.id } }
        assertTrue(days >= 3, "spread over $days days")
        // The sync keeps your 6 hours.
        assertEquals(360, Homework.sync(d, listOf(hw), at(sun, 7)).tasks.single().estMin)
    }

    @Test
    fun `ahead meter`() {
        val hw = CalEvent(uid = "a2", title = "HW 3", start = at(sun.plusDays(1), 23, 59), end = at(sun.plusDays(1), 23, 59), source = "phone")
        val d = Homework.sync(base(), listOf(hw), at(sun, 6))
        val a = Homework.ahead(d, at(sun, 12))
        assertFalse(a.onTrack)
        assertEquals(120, a.behindMin)
        val done = Actions.setDone(d, d.tasks.single().id, true, at(sun, 13))
        assertTrue(Homework.ahead(done, at(sun, 14)).onTrack)
    }

    @Test
    fun `to-do levels`() {
        var d = base()
        d = Actions.addTodo(d, "Return package", Horizon.ASAP, null, null, null, 30, at(sun, 8))
        d = Actions.addTodo(d, "Call grandma", Horizon.ON_DAY, sun.plusDays(2), null, null, 30, at(sun, 8))
        d = Actions.addTodo(d, "Learn to juggle", Horizon.SOMEDAY, null, null, null, 60, at(sun, 8))
        assertTrue(d.liveTasks().none { it.title == "Learn to juggle" }, "someday is never planned")
        val today = DayPlanner().plan(sun, d, at(sun, 6)).blocks
        assertTrue(today.any { it.title == "Return package" })
        assertTrue(today.none { it.title == "Call grandma" })
        val tue = DayPlanner().plan(sun.plusDays(2), d, at(sun.plusDays(2), 6)).blocks
        assertTrue(tue.any { it.title == "Call grandma" })
    }

    @Test
    fun `loosening waits for the morning, tightening is instant`() {
        var d = base()
        val night = at(sun, 23, 30)
        d = Lock.setSwitch(d, ChangeKind.BLOCKING, false, "Turn blocking off", night)
        assertTrue(d.rules.blockingEnabled)
        assertEquals(1, d.pending.size)
        assertTrue(Lock.due(d, at(sun.plusDays(1), 6, 59)).isEmpty())
        val due = Lock.due(d, at(sun.plusDays(1), 7, 1))
        assertEquals(1, due.size)
        d = Lock.confirm(d, due.single().id)
        assertFalse(d.rules.blockingEnabled)
        d = Lock.setSwitch(d, ChangeKind.BLOCKING, true, "on", night)
        assertTrue(d.rules.blockingEnabled)

        val snap = "com.snapchat.android"
        val old = d.rules.profile(snap)
        d = Lock.setProfile(d, snap, old.copy(replyWindowMin = 30, creditWaitSec = 120), "Snapchat", night)
        assertEquals(120, d.rules.profile(snap).creditWaitSec, "stricter part applied now")
        assertEquals(old.replyWindowMin, d.rules.profile(snap).replyWindowMin, "looser part waits")
        d = Lock.setBlocked(d, d.rules.blocked - snap + "com.new.app", night) { it }
        assertTrue(snap in d.rules.blocked && "com.new.app" in d.rules.blocked)
    }

    @Test
    fun `purpose window and typed extra emergencies`() {
        val d = base()
        val yt = Migration.YOUTUBE
        val p = d.rules.profile(yt)
        assertTrue(p.askPurpose)
        val day0 = at(sun, 0)
        val l = Credits.purpose(d.ledger, p, yt, "YouTube", "how to fix bike chain", at(sun, 12), day0)
        assertNotNull(l)
        assertEquals(at(sun, 12, 5), l.unlockedUntil[yt])
        assertNull(Credits.purpose(d.ledger, p, yt, "YouTube", "x", at(sun, 12), day0), "has to say what")
        var led = d.ledger
        repeat(3) { led = Credits.emergencyInstant(led, d.settings, BlockProfile(), "s", "Snap", at(sun, 13 + it), day0)!! }
        assertNull(Credits.emergencyInstant(led, d.settings, BlockProfile(), "s", "Snap", at(sun, 17), day0))
        assertNull(Credits.emergencyWithPhrase(led, d.settings, BlockProfile(), "s", "Snap", "whatever", at(sun, 17), day0))
        assertNotNull(Credits.emergencyWithPhrase(led, d.settings, BlockProfile(), "s", "Snap", "i am choosing this on purpose.", at(sun, 17), day0))
    }

    @Test
    fun `suggests heavy apps to block`() {
        val d = base().copy(usage = (0..6).map { UsageDay(sun.minusDays(it.toLong()).toString(), "phone", mapOf("com.chess" to 45, "com.calc" to 3), mapOf("com.chess" to "Chess")) })
        assertEquals(listOf("com.chess"), Actions.blockSuggestions(d, sun, emptySet()).map { it.first })
    }

    @Test
    fun `v7 turns deadline calendars into homework`() {
        val old = base().copy(
            schema = 6,
            settings = base().settings.copy(calendarModes = mapOf(5L to CalMode.DEADLINES)),
            tasks = listOf(Task(title = "HW 1", estMin = 60, dueAt = at(sun.plusDays(3), 23), sourceKey = "deadline:x", createdAt = 0)),
        )
        val v7 = Migration.apply(old)
        assertEquals(CalMode.HOMEWORK, v7.settings.calendarModes[5L])
        assertEquals("homework:x", v7.tasks.single().sourceKey)
        assertEquals(120, v7.tasks.single().estMin)
        assertEquals(v7, Migration.apply(v7))
    }
}
