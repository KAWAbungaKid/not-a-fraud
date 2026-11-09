package com.maven.notafraud.core

import com.maven.notafraud.core.guard.GuardAction
import com.maven.notafraud.core.guard.GuardPolicy
import com.maven.notafraud.core.guard.Ladder
import com.maven.notafraud.core.guard.SettingsGuard
import com.maven.notafraud.core.model.ActiveSession
import com.maven.notafraud.core.model.UsageDay
import com.maven.notafraud.core.plan.Time
import com.maven.notafraud.core.seed.Seed
import com.maven.notafraud.core.store.Actions
import com.maven.notafraud.core.store.Lock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class V10Test {
    private val zone = ZoneId.of("America/Chicago")
    private val day = LocalDate.parse("2026-11-04")
    private fun at(h: Int, m: Int = 0, date: LocalDate = day) = Time.at(date, h * 60 + m, zone)
    private fun base() = Seed.data(at(4)).let { it.copy(routine = emptyList(), settings = it.settings.copy(routineSince = null)) }

    @Test
    fun `bedtime is level 3 once the list is set up, and only the list opens`() {
        var d = base()
        assertEquals(2, Ladder.state(d, at(23)).level, "no list yet: dull, not brick")
        d = d.copy(settings = d.settings.copy(ladder = d.settings.ladder.copy(allowlist = setOf("com.google.android.apps.maps"), allowlistConfirmed = true)))
        val s = Ladder.state(d, at(23))
        assertEquals(3, s.level)
        assertTrue(s.bedtime && s.creditsFrozen)
        assertEquals(GuardAction.BLOCK, GuardPolicy.decide("com.reddit.frontpage", d, at(23)).action)
        assertTrue(GuardPolicy.decide("com.reddit.frontpage", d, at(23)).brick)
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("com.google.android.apps.maps", d, at(23)).action)
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("com.samsung.android.dialer", d, at(23)).action)
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("com.maven.workout", d, at(23)).action)
        assertEquals(0, Ladder.state(d, at(10)).level)
    }

    @Test
    fun `drift climbs the ladder and working drops it`() {
        var d = base()
        d = d.copy(usage = listOf(UsageDay(day.toString(), "phone", mapOf("com.instagram.android" to 100))), rules = d.rules.copy(blocked = d.rules.blocked + "com.instagram.android"))
        assertEquals(1, Ladder.state(d, at(14)).level)
        d = d.copy(usage = listOf(UsageDay(day.toString(), "phone", mapOf("com.instagram.android" to 160))))
        assertEquals(1, Ladder.state(d, at(14)).level, "3 points is still friction")
        d = d.copy(active = ActiveSession(title = "Heat transfer", start = at(14)))
        assertEquals(0, Ladder.state(d, at(14, 5)).level)
    }

    @Test
    fun `ladder loosening waits, tightening applies`() {
        val d = base()
        val strict = Lock.setLadder(d, d.settings.ladder.copy(bedMin = 21 * 60), "Bedtime 9", at(12))
        assertEquals(21 * 60, strict.settings.ladder.bedMin)
        assertTrue(strict.pending.isEmpty())
        val loose = Lock.setLadder(d, d.settings.ladder.copy(enabled = false), "Ladder off", at(12))
        assertTrue(loose.settings.ladder.enabled)
        assertEquals(1, loose.pending.size)
        val confirmed = Lock.confirm(loose, loose.pending.first().id)
        assertFalse(confirmed.settings.ladder.enabled)
    }

    @Test
    fun `settings guard protects switching off, not switching on`() {
        val labels = setOf("Not a Fraud")
        val appInfo = listOf(SettingsGuard.Node("Not a Fraud"), SettingsGuard.Node("Uninstall"), SettingsGuard.Node("Force stop"))
        assertTrue(SettingsGuard.protects(appInfo, labels))
        val onPage = listOf(SettingsGuard.Node("Not a Fraud"), SettingsGuard.Node("On", checkable = true, checked = true))
        assertTrue(SettingsGuard.protects(onPage, labels))
        val offPage = listOf(SettingsGuard.Node("Not a Fraud"), SettingsGuard.Node("Off", checkable = true, checked = false))
        assertFalse(SettingsGuard.protects(offPage, labels))
        val list = listOf(
            SettingsGuard.Node("Instagram", top = 0, bottom = 100), SettingsGuard.Node("", true, true, 0, 100),
            SettingsGuard.Node("Not a Fraud", top = 100, bottom = 200), SettingsGuard.Node("", true, false, 100, 200),
            SettingsGuard.Node("Snapchat", top = 200, bottom = 300), SettingsGuard.Node("", true, true, 200, 300),
            SettingsGuard.Node("Spotify", top = 300, bottom = 400), SettingsGuard.Node("", true, true, 300, 400),
        )
        assertFalse(SettingsGuard.protects(list, labels), "other apps' switches on a list page are free")
        assertFalse(SettingsGuard.protects(listOf(SettingsGuard.Node("Wi-Fi"), SettingsGuard.Node("", true, true)), labels))
    }

    @Test
    fun `skip several times or until a date`() {
        val d = base()
        val trading = d.anchors.first { it.title.contains("Trading", true) || it.title.contains("Market", true) }
        val (x, n) = Actions.skipAnchorMany(d, trading.id, day, count = 3, until = null, makeUp = false, now = at(8))
        assertEquals(3, n)
        assertEquals(3, x.anchors.first { it.id == trading.id }.skipDates.count { it >= day.toString() })
        val (y, m) = Actions.skipAnchorMany(d, trading.id, day, count = null, until = day.plusDays(6), makeUp = true, now = at(8))
        assertTrue(m in 4..5)
        assertEquals(m, y.tasks.count { it.sourceKey?.startsWith("makeup:") == true })
    }

    @Test
    fun `habit blocks became practice with a fixed time`() {
        val fresh = Seed.data(at(4))
        assertTrue(fresh.anchors.none { it.mode == com.maven.notafraud.core.model.AnchorMode.HABIT && it.yieldsToHot && it.dates.isEmpty() })
        assertTrue(fresh.anchors.any { it.title.startsWith("Weekly review") }, "rhythm blocks stay blocks")
        val cswe = fresh.tasks.first { it.title == "CSWE practice" }
        assertEquals(com.maven.notafraud.core.model.TaskKind.PRACTICE, cswe.kind)
        assertEquals(setOf(7), cswe.fixedDays)
        assertEquals(90, cswe.weeklyTargetMin)
        val sun = LocalDate.parse("2026-11-08")
        val plan = com.maven.notafraud.core.plan.DayPlanner().plan(sun, fresh.copy(tasks = listOf(cswe)), at(6, 0, sun))
        val b = plan.blocks.first { it.taskId == cswe.id }
        assertEquals(10 * 60, Time.minuteOfDay(b.start, zone))
        assertEquals(1, plan.blocks.count { it.taskId == cswe.id }, "the fixed slot covers the week's target: no extra sprinkles")
    }

    @Test
    fun `old saves migrate habits too`() {
        val old = Seed.data(at(4)).let { d ->
            d.copy(schema = 9, tasks = d.tasks.filter { it.sourceKey?.startsWith("habit:") != true }, anchors = d.anchors + com.maven.notafraud.core.model.Anchor(
                title = "Piano", kind = com.maven.notafraud.core.model.AnchorKind.ROUTINE, startMin = 18 * 60, endMin = 18 * 60 + 30, days = setOf(2, 4),
                mode = com.maven.notafraud.core.model.AnchorMode.HABIT, yieldsToHot = true,
            ))
        }
        val v = com.maven.notafraud.core.seed.Migration.apply(old, at(4))
        val piano = v.tasks.first { it.title == "Piano" }
        assertEquals(60, piano.weeklyTargetMin)
        assertTrue(v.anchors.none { it.title == "Piano" })
    }

    @Test
    fun `routine items open their app on unlock and tick after time in it`() {
        val bible = com.maven.notafraud.core.model.RoutineItem(
            id = "bible", title = "Read the Bible", startMin = 7 * 60, dueMin = 8 * 60, angryMin = 9 * 60,
            openPkg = "com.sirma.mobile.bible.android", openFromStage = 1, openAtLevel = 0, openEveryMin = 15, openDoneMin = 15,
        )
        var d = base().copy(routine = listOf(bible))
        assertEquals(null, com.maven.notafraud.core.routine.RoutinePolicy.appToOpen(d, at(7, 30), 0), "due but not late yet")
        assertEquals("bible", com.maven.notafraud.core.routine.RoutinePolicy.appToOpen(d, at(8, 10), 0)?.id)
        assertEquals(null, com.maven.notafraud.core.routine.RoutinePolicy.appToOpen(d.copy(routine = listOf(bible.copy(openAtLevel = 2))), at(8, 10), 1), "ladder too low")
        d = Actions.markOpened(d, "bible", at(8, 10))
        assertEquals(null, com.maven.notafraud.core.routine.RoutinePolicy.appToOpen(d, at(8, 20), 0), "opened 10 min ago")
        assertEquals("bible", com.maven.notafraud.core.routine.RoutinePolicy.appToOpen(d, at(8, 26), 0)?.id)
        d = Actions.routineAppTime(d, "com.sirma.mobile.bible.android", 9, at(8, 30))
        assertFalse(com.maven.notafraud.core.routine.RoutinePolicy.day(d, day).done.containsKey("bible"))
        d = Actions.routineAppTime(d, "com.sirma.mobile.bible.android", 7, at(8, 45))
        assertTrue(com.maven.notafraud.core.routine.RoutinePolicy.day(d, day).done.containsKey("bible"))
        assertEquals(null, com.maven.notafraud.core.routine.RoutinePolicy.appToOpen(d, at(9, 30), 3), "done: no more opening")
        // Always opens at Level 3 / bedtime.
        val locked = d.copy(settings = d.settings.copy(ladder = d.settings.ladder.copy(allowlistConfirmed = true)))
        assertTrue(Ladder.allowedAtTop(locked, "com.sirma.mobile.bible.android", true))
    }

    @Test
    fun `consistency counts days in a row and skips days it doesn't apply`() {
        val wkdays = com.maven.notafraud.core.model.RoutineItem(id = "w", title = "Wake", startMin = 300, dueMin = 330, angryMin = 360, days = setOf(1, 2, 3, 4, 5))
        var d = base().copy(routine = listOf(wkdays))
        // day = Wed Nov 4. Done Mon, Tue, plus the previous Thu, Fri; weekend doesn't apply.
        for (k in listOf(1L, 2L, 5L, 6L)) d = Actions.checkRoutine(d, "w", at(5, 20, day.minusDays(k)), null)
        val c = com.maven.notafraud.core.routine.RoutinePolicy.consistency(d, d.routine.first(), day)
        assertEquals(4, c.count, "today not done yet doesn't break it")
        d = Actions.checkRoutine(d, "w", at(5, 20), null)
        assertEquals(5, com.maven.notafraud.core.routine.RoutinePolicy.consistency(d, d.routine.first(), day).count)
        // A missed Monday breaks it.
        d = Actions.uncheckRoutine(d, "w", day.minusDays(2))
        assertEquals(2, com.maven.notafraud.core.routine.RoutinePolicy.consistency(d, d.routine.first(), day).count)
    }

    @Test
    fun `onboarding builds a setup with nothing of Maven's`() {
        val fresh = com.maven.notafraud.core.seed.Templates.fresh(at(4), "America/New_York")
        assertFalse(fresh.settings.onboarded)
        assertTrue(fresh.anchors.isEmpty() && fresh.goals.isEmpty() && fresh.routine.isEmpty() && fresh.tasks.isEmpty())
        val t = com.maven.notafraud.core.seed.Templates
        val a = com.maven.notafraud.core.seed.Templates.Answers(
            name = "Sam", goals = mapOf("fitness" to 5, "faith" to 4), customGoals = listOf("Learn guitar"),
            modules = t.suggestedModules(setOf("fitness", "faith")), wakeMin = 7 * 60, bedMin = 23 * 60,
            schedule = com.maven.notafraud.core.seed.Templates.Schedule.WORK, starters = t.suggestedStarters(setOf("fitness", "faith")), workoutsPerWeek = 4,
        )
        val d = t.apply(fresh, a, at(9))
        assertTrue(d.settings.onboarded)
        assertEquals(listOf("Get fit and strong", "Grow in my faith", "Learn guitar"), d.goals.map { it.name })
        assertEquals(5, d.areas.first { it.name == "Fitness" }.importance)
        val wake = d.routine.first { it.title == "Wake up" }
        assertEquals(7 * 60 + 30, wake.dueMin)
        assertEquals(23 * 60, d.routine.first { it.title == "In bed, phone down" }.dueMin)
        assertEquals(4, d.routine.first { it.title == "Workout" }.weeklyTarget)
        assertTrue(d.anchors.any { it.title == "Work" })
        val all = kotlinx.serialization.json.Json.encodeToString(com.maven.notafraud.core.model.AppData.serializer(), d)
        for (w in listOf("ME 3411", "Trading", "CSWE", "Biosensor", "scout", "Chicago", "creatine", "Date night")) assertFalse(all.contains(w, ignoreCase = true), w)
    }

    @Test
    fun `switched-off modules go quiet`() {
        var d = base().copy(settings = base().settings.copy(modules = com.maven.notafraud.core.model.Module.entries.toSet() - com.maven.notafraud.core.model.Module.BLOCKING))
        assertEquals(GuardAction.IGNORE, GuardPolicy.decide("com.instagram.android", d, at(12)).action)
        assertEquals(0, Ladder.state(d, at(23)).level)
        // Turning Routine off waits for the morning; Stats goes right away.
        d = Lock.setModule(d, com.maven.notafraud.core.model.Module.ROUTINE, false, at(12))
        assertTrue(com.maven.notafraud.core.model.Module.ROUTINE in d.settings.modules)
        assertEquals(1, d.pending.size)
        d = Lock.setModule(d, com.maven.notafraud.core.model.Module.STATS, false, at(12))
        assertFalse(com.maven.notafraud.core.model.Module.STATS in d.settings.modules)
    }
}
