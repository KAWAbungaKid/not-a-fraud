package com.maven.notafraud.core

import com.maven.notafraud.core.guard.Credits
import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.BlockProfile
import com.maven.notafraud.core.model.CalEvent
import com.maven.notafraud.core.model.DayContext
import com.maven.notafraud.core.model.DayMode
import com.maven.notafraud.core.model.Ledger
import com.maven.notafraud.core.model.Priority
import com.maven.notafraud.core.model.Settings
import com.maven.notafraud.core.model.Task
import com.maven.notafraud.core.model.TaskKind
import com.maven.notafraud.core.model.TaskStatus
import com.maven.notafraud.core.model.TaskStep
import com.maven.notafraud.core.plan.BlockKind
import com.maven.notafraud.core.plan.DayAdvisor
import com.maven.notafraud.core.plan.DayPlanner
import com.maven.notafraud.core.plan.Generated
import com.maven.notafraud.core.plan.Planning
import com.maven.notafraud.core.plan.Time
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

class V3Test {
    private val zone = ZoneId.of("America/Chicago")
    private val wed = LocalDate.parse("2026-10-21")
    private val sat = LocalDate.parse("2026-11-14") // no research session
    private fun at(d: LocalDate, h: Int, m: Int = 0) = Time.at(d, h * 60 + m, zone)
    private fun base() = Seed.data(at(wed, 4)).copy(tasks = emptyList())

    @Test
    fun `laundry books only attended minutes and runs in the background`() {
        val laundry = Task(
            title = "Laundry", kind = TaskKind.STEPS, createdAt = at(wed, 4),
            steps = listOf(TaskStep(title = "Start washer", min = 5), TaskStep(title = "Move to dryer", min = 5, waitBeforeMin = 50), TaskStep(title = "Fold", min = 10, waitBeforeMin = 60)),
        )
        val d = base().copy(tasks = listOf(laundry))
        val plan = DayPlanner().plan(sat, d, at(sat, 6))
        val steps = plan.blocks.filter { it.taskId == laundry.id && it.kind == BlockKind.TASK }
        assertEquals(3, steps.size, plan.blocks.joinToString("\n") { "${Time.clock(it.start, zone)} ${it.kind} ${it.title}" })
        assertTrue(steps[1].start >= steps[0].end + 50 * Time.MIN)
        assertTrue(steps[2].start >= steps[1].end + 60 * Time.MIN)
        assertEquals(2, plan.blocks.count { it.taskId == laundry.id && it.kind == BlockKind.BACKGROUND })
        assertEquals(20, plan.allotted[laundry.id])
    }

    @Test
    fun `exact steps land right when the wait ends`() {
        val run = Task(
            title = "Sensor run", kind = TaskKind.STEPS, createdAt = at(wed, 4), priority = Priority.HIGH,
            steps = listOf(TaskStep(title = "Prep", min = 45), TaskStep(title = "Test", min = 45, waitBeforeMin = 60, exact = true)),
        )
        val plan = DayPlanner().plan(sat, base().copy(tasks = listOf(run)), at(sat, 6))
        val b = plan.blocks.filter { it.taskId == run.id && it.kind == BlockKind.TASK }
        assertEquals(2, b.size)
        assertEquals(b[0].end + 60 * Time.MIN, b[1].start)
    }

    @Test
    fun `checking every step finishes the task, and a half-done chain resumes`() {
        val t = Task(title = "Laundry", kind = TaskKind.STEPS, createdAt = 0, steps = listOf(TaskStep(id = "a", title = "Start", min = 5), TaskStep(id = "b", title = "Fold", min = 10, waitBeforeMin = 30)))
        var d = base().copy(tasks = listOf(t))
        d = Actions.checkStep(d, t.id, "a", true, at(sat, 9))
        val plan = DayPlanner().plan(sat, d, at(sat, 9, 5))
        val fold = plan.blocks.first { it.stepId == "b" }
        assertTrue(fold.start >= at(sat, 9, 30))
        d = Actions.checkStep(d, t.id, "b", true, at(sat, 10))
        assertEquals(TaskStatus.DONE, d.tasks.first().status)
    }

    @Test
    fun `practice is sprinkled across the week, once a day, within its block limits`() {
        val piano = Task(title = "Piano", kind = TaskKind.PRACTICE, weeklyTargetMin = 120, minBlockMin = 15, maxBlockMin = 45, createdAt = at(wed, 4))
        val d0 = base().copy(tasks = listOf(piano))
        val d = Planning.ensureToday(d0, at(LocalDate.parse("2026-10-19"), 6))
        val days = listOf(d.todayPlan!!) + d.weekPlan
        val perDay = days.map { p -> p.blocks.filter { it.taskId == piano.id }.sumOf { it.minutes } }
        assertTrue(perDay.all { it <= 45 }, perDay.toString())
        assertTrue(days.take(7).sumOf { p -> p.blocks.filter { it.taskId == piano.id }.sumOf { it.minutes } } >= 120, perDay.toString())
    }

    @Test
    fun `importance reorders equal tasks and bump lifts one`() {
        val a = Task(title = "A", estMin = 60, createdAt = at(wed, 4))
        val b = Task(title = "B", estMin = 60, createdAt = at(wed, 4), importance = 5)
        val first = DayPlanner().plan(sat, base().copy(tasks = listOf(a, b)), at(sat, 6)).blocks.first { it.kind == BlockKind.TASK }
        assertEquals(b.id, first.taskId)
        val bumped = Actions.bump(base().copy(tasks = listOf(a, b.copy(importance = 3))), a.id, at(sat, 5))
        val first2 = DayPlanner().plan(sat, bumped, at(sat, 6)).blocks.first { it.kind == BlockKind.TASK }
        assertEquals(a.id, first2.taskId)
    }

    @Test
    fun `not today moves it to tomorrow and logs the deferral`() {
        val t = Task(title = "Essay", estMin = 60, createdAt = at(wed, 4))
        var d = base().copy(tasks = listOf(t))
        d = Actions.notToday(d, t.id, at(wed, 10))
        assertTrue(DayPlanner().plan(wed, d, at(wed, 10)).blocks.none { it.taskId == t.id })
        assertTrue(DayPlanner().plan(wed.plusDays(1), d, at(wed.plusDays(1), 0)).blocks.any { it.taskId == t.id })
        assertEquals(1, d.tasks.first().deferrals.size)
    }

    @Test
    fun `skipping a class with make-up creates a task and removes the block`() {
        val d0 = base()
        val lab = d0.anchors.first { it.title == "ME 4840 Lab" && 3 in it.days }
        val d = Actions.skipAnchor(d0, lab.id, wed, makeUp = true, now = at(wed, 13))
        assertTrue(DayPlanner().plan(wed, d, at(wed, 13)).blocks.none { it.anchorId == lab.id })
        val mk = d.tasks.single { it.sourceKey?.startsWith("makeup:") == true }
        assertEquals(50, mk.estMin)
        assertNotNull(mk.projectId)
    }

    @Test
    fun `week plan does not book the same work twice`() {
        val t = Task(title = "Big report", estMin = 600, createdAt = at(wed, 4))
        val d = Planning.ensureToday(base().copy(tasks = listOf(t)), at(wed, 6))
        val total = (listOf(d.todayPlan!!) + d.weekPlan).sumOf { p -> p.blocks.filter { it.taskId == t.id }.sumOf { it.minutes } }
        assertEquals(600, total)
        assertEquals(Planning.WEEK_DAYS, d.weekPlan.size)
    }

    @Test
    fun `canvas deadlines become tasks that update and disappear`() {
        val e = CalEvent(uid = "phone-77-1", title = "HW 6 [ME-3525-01]", start = at(wed.plusDays(3), 23, 59), end = at(wed.plusDays(3), 23, 59), source = "phone")
        var d = Generated.syncDeadlines(base(), listOf(e), at(wed, 9))
        val t = d.tasks.single { it.sourceKey == "deadline:phone-77-1" }
        assertEquals("ME 3525 Heat Transfer", d.projects.first { it.id == t.projectId }.name)
        d = Generated.syncDeadlines(d, listOf(e.copy(title = "HW 6 (revised) [ME-3525-01]")), at(wed, 10))
        assertEquals("HW 6 (revised) [ME-3525-01]", d.tasks.single { it.sourceKey == "deadline:phone-77-1" }.title)
        d = Generated.syncDeadlines(d, emptyList(), at(wed, 11))
        assertTrue(d.tasks.none { it.sourceKey == "deadline:phone-77-1" })
    }

    @Test
    fun `deleted generated tasks stay deleted`() {
        val e = CalEvent(uid = "x1", title = "HW 7 [ME-3521]", start = at(wed.plusDays(2), 12), end = at(wed.plusDays(2), 12), source = "phone")
        var d = Generated.syncDeadlines(base(), listOf(e), at(wed, 9))
        d = Actions.deleteTask(d, d.tasks.single { it.sourceKey == "deadline:x1" }.id)
        d = Generated.syncDeadlines(d, listOf(e), at(wed, 10))
        assertTrue(d.tasks.none { it.sourceKey == "deadline:x1" })
    }

    @Test
    fun `exams in the calendar get study tasks automatically`() {
        val ev = CalEvent(uid = "ex1", title = "A&P Exam 4", start = at(wed.plusDays(20), 11), end = at(wed.plusDays(20), 12), source = "phone")
        val d = Generated.autoExams(base().copy(events = listOf(ev), settings = base().settings.copy(autoExamTasks = true)), at(wed, 9))
        assertEquals(1, d.tasks.count { it.sourceKey == "exam:ex1" })
        assertEquals(d, Generated.autoExams(d, at(wed, 9)))
    }

    @Test
    fun `write it off plans no work, reset keeps only what's due`() {
        val due = Task(title = "Due tonight", estMin = 60, dueAt = at(wed, 23), priority = Priority.HIGH, createdAt = at(wed, 4))
        val later = Task(title = "Someday", estMin = 60, createdAt = at(wed, 4))
        val filler = Task(title = "Inbox zero", estMin = 20, kind = TaskKind.FILLER, createdAt = at(wed, 4))
        val d0 = base().copy(tasks = listOf(due, later, filler))
        val off = Actions.setDayMode(d0, wed, DayMode.WRITEOFF, 20, at(wed, 13))
        assertTrue(DayPlanner().plan(wed, off, at(wed, 13)).blocks.none { it.kind == BlockKind.TASK })
        val reset = Actions.setDayMode(d0, wed, DayMode.RESET, 45, at(wed, 13))
        val ids = DayPlanner().plan(wed, reset, at(wed, 13)).blocks.mapNotNull { it.taskId }.toSet()
        assertTrue(due.id in ids && filler.id in ids)
        assertFalse(later.id in ids)
        val recharge = Actions.setDayMode(d0, wed, DayMode.RECHARGE, 90, at(wed, 13))
        val r = DayPlanner().plan(wed, recharge, at(wed, 13)).blocks.first { it.kind == BlockKind.RECHARGE }
        assertEquals(90, r.minutes)
    }

    @Test
    fun `advisor suggests a long nap after two short nights, reset after 3pm`() {
        var d = base()
        d = Actions.upsertDay(d, DayContext(date = wed.toString(), sleepHours = 5.0))
        d = Actions.upsertDay(d, DayContext(date = wed.minusDays(1).toString(), sleepHours = 5.2))
        val a = DayAdvisor.suggest(d, at(wed, 13))
        assertEquals(DayMode.RECHARGE, a.mode)
        assertEquals(90, a.rechargeMin)
        assertEquals(DayMode.RESET, DayAdvisor.suggest(d, at(wed, 16)).mode)
    }

    @Test
    fun `sport counts toward workouts only after three standard ones`() {
        var d = base()
        val mon = LocalDate.parse("2026-10-19")
        d = Actions.checkRoutine(d, "r-workout", at(mon, 17), "Climbing")
        d = Actions.checkRoutine(d, "r-workout", at(mon.plusDays(1), 17), "Lift")
        val w = d.routine.first { it.id == "r-workout" }
        assertEquals(1, RoutinePolicy.weekCount(d, w, wed))
        d = Actions.checkRoutine(d, "r-workout", at(mon.plusDays(2), 17), "Lift")
        d = Actions.checkRoutine(d, "r-workout", at(mon.plusDays(3), 17), "Lift")
        assertEquals(4, RoutinePolicy.weekCount(d, w, wed))
    }

    @Test
    fun `wake up falls back to first unlock and is graded late`() {
        var d = base()
        d = Actions.recordFirstUnlock(d, at(wed, 3))
        d = Actions.recordFirstUnlock(d, at(wed, 6, 40))
        d = Actions.recordFirstUnlock(d, at(wed, 7))
        d = Actions.autoFromHealth(d, wed)
        val st = RoutinePolicy.status(d, d.routine.first { it.id == "r-wake" }, at(wed, 8))
        assertEquals(at(wed, 6, 40), st.doneAt)
        assertTrue(RoutinePolicy.doneLate(d, st))
    }

    @Test
    fun `reply window, typing extends it, still-texting after, emergency instant`() {
        val now = 1_800_000_000_000L
        var l = Credits.freeWindow(Ledger(balanceMin = 0.0), "snap", 5, now, "Reply window")
        assertTrue(Credits.isUnlocked(l, "snap", now + 4 * Time.MIN))
        l = Credits.extendWhileTyping(l, "snap", now + 4 * Time.MIN)
        assertTrue(Credits.isUnlocked(l, "snap", now + 5 * Time.MIN + 30_000))
        assertTrue(Credits.canStillTexting(l, "snap", now + 7 * Time.MIN))
        val p = BlockProfile(quickOpen = true, quickOpenPerDay = 1)
        val q = Credits.quickOpen(Ledger(), p, "snap", "Snapchat", now, now - Time.HOUR)
        assertNotNull(q)
        assertNull(Credits.quickOpen(q, p, "snap", "Snapchat", now + 1000, now - Time.HOUR))
        assertNotNull(Credits.emergencyInstant(Ledger(), Settings(), BlockProfile(), "ig", "IG", now, now - Time.HOUR))
    }

    @Test
    fun `v3 migration adds catch-up practice, research importance, review queue`() {
        val v2 = Seed.data(at(wed, 4)).let { it.copy(schema = 2, tasks = it.tasks.filterNot { t -> t.sourceKey?.startsWith("catchup:") == true }) }
        val v3 = Migration.apply(v2, at(wed, 9))
        assertEquals(Migration.CURRENT, v3.schema)
        assertEquals(5, v3.areas.first { it.id == "a-research" }.importance)
        assertTrue(v3.tasks.count { it.kind == TaskKind.PRACTICE && it.title.startsWith("Catch up:") } >= 4)
        assertTrue(v3.reviewTaskIds.isNotEmpty())
        assertTrue(v3.tasks.filter { t -> v3.projects.first { it.id == t.projectId }.areaId == "a-research" && t.status == TaskStatus.TODO }.all { it.hot })
        assertEquals(v3, Migration.apply(v3))
    }

    @Test
    fun `tests calendar makes study tasks that follow the test and leave with it`() {
        val ev = CalEvent(uid = "t1", title = "ME 3525 Exam 2", start = at(wed.plusDays(12), 10), end = at(wed.plusDays(12), 11), source = "phone")
        var d = Generated.syncTests(base(), listOf(ev), at(wed, 9))
        val t = d.tasks.single { it.sourceKey == "test:t1" }
        assertEquals(360, t.estMin)
        assertEquals("ME 3525 Heat Transfer", d.projects.first { it.id == t.projectId }.name)
        assertEquals(ev.start - 10 * Time.DAY, t.notBefore)
        val moved = ev.copy(start = ev.start + Time.DAY, end = ev.end + Time.DAY)
        d = Generated.syncTests(d, listOf(moved), at(wed, 10))
        assertEquals(moved.start, d.tasks.single { it.sourceKey == "test:t1" }.dueAt)
        d = Generated.syncTests(d, emptyList(), at(wed, 11))
        assertTrue(d.tasks.none { it.sourceKey == "test:t1" })
    }

    @Test
    fun `opening snap soon after a message gets one reply window per message`() {
        val now = 1_800_000_000_000L
        val p = BlockProfile()
        var l = Credits.messageArrived(Ledger(), "snap", now)
        val w = Credits.replyWindowIfMessage(l, p, "snap", "Snapchat", now + 2 * Time.MIN)
        assertNotNull(w)
        assertTrue(Credits.isUnlocked(w, "snap", now + 6 * Time.MIN))
        assertNull(Credits.replyWindowIfMessage(w, p, "snap", "Snapchat", now + 8 * Time.MIN), "same message, no second window")
        l = Credits.messageArrived(w, "snap", now + 9 * Time.MIN)
        assertNotNull(Credits.replyWindowIfMessage(l, p, "snap", "Snapchat", now + 10 * Time.MIN))
        assertNull(Credits.replyWindowIfMessage(Credits.messageArrived(Ledger(), "snap", now), p, "snap", "Snapchat", now + 20 * Time.MIN), "too long after")
    }

    @Test
    fun `v4 migration removes seeded exam studying and the seeded final`() {
        val old = Seed.data(at(wed, 4)).copy(schema = 3, events = listOf(CalEvent(uid = "seed-x", title = "ME 3411 final", start = 1, end = 2, source = "seed")),
            tasks = listOf(Task(title = "Study for A&P Test 4", createdAt = 0), Task(title = "Study for ME 3411 Exam 3", createdAt = 0, loggedMin = 30), Task(title = "Study: Thermo exam", sourceKey = "exam:e9", createdAt = 0)))
        val v4 = Migration.apply(old, at(wed, 9))
        assertEquals(listOf("Study for ME 3411 Exam 3"), v4.tasks.map { it.title }, "worked-on studying is kept")
        assertTrue(v4.events.isEmpty())
        assertFalse(v4.settings.autoExamTasks)
    }
}
