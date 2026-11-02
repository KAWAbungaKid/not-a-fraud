package com.maven.workout.core

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkoutTest {
    private val mon = LocalDate.parse("2026-10-26")
    private val seed = Seed.data(mon.toString())

    // ---------- Numbers from the spreadsheet ----------

    @Test
    fun `rpe chart matches the sheet`() {
        assertEquals(1.0, Rpe.pct(1, 10.0), 1e-9)
        assertEquals(0.955, Rpe.pct(1, 9.0), 1e-9)
        assertEquals(0.955, Rpe.pct(2, 10.0), 1e-9)
        assertEquals(0.837, Rpe.pct(3, 7.0), 1e-9)
        assertEquals(0.707, Rpe.pct(10, 9.0), 1e-9)
        assertEquals(0.574, Rpe.pct(12, 6.0), 1e-9)
        assertEquals((0.762 + 0.739) / 2, Rpe.pct(8, 8.5), 1e-9)
        assertTrue(Rpe.pct(15, 8.0) < 0.574)
    }

    @Test
    fun `e1rm matches Maxes sheet`() {
        assertEquals(274.6636771, Rpe.e1rm(245.0, 4)!!, 1e-6)
        assertEquals(268.7411598, Rpe.e1rm(190.0, 11)!!, 1e-6)
        assertEquals(365.0, Rpe.e1rm(365.0, 1)!!, 1e-9)
        assertEquals(439.2624729, Rpe.e1rm(405.0, 3)!!, 1e-6) // the "new max" calculator
        assertEquals(263.4529148, Rpe.e1rm(235.0, 4, 10.0)!!, 1e-6) // Phone!C19
    }

    @Test
    fun `week 2 block 1 push matches the Phone sheet`() {
        val s = Prescriber.session(seed, seed.program()!!.days[0], Mode.NORMAL, mon, 0L, "x")
        fun e(id: String) = s.entries.first { it.exerciseId == id }.sets
        val bench = e("bench")
        assertEquals(SetKind.TOP, bench[0].kind)
        assertEquals(3, bench[0].targetReps); assertEquals(7.0, bench[0].targetRpe); assertEquals(230.0, bench[0].targetWeight)
        assertEquals(4, bench.size)
        assertEquals(4, bench[1].targetReps); assertEquals(225.0, bench[1].targetWeight)
        assertEquals(160.0, e("incline-bench")[0].targetWeight)
        assertEquals(6, e("incline-bench")[0].targetReps)
        assertEquals(190.0, e("machine-fly")[0].targetWeight)
        assertEquals(10, e("machine-fly")[0].targetReps)
        assertEquals(9.0, e("machine-fly")[0].targetRpe)
        // Shoulder press: 75x8 @8.5 → e1RM ≈ 99.9; secondary type W2B1 = 3x6 @7 → 76 → 75.
        assertEquals(99.93, Strength.trainingMax(seed, "shoulder-press", mon)!!, 0.01)
        assertEquals(75.0, e("shoulder-press")[0].targetWeight)
    }

    @Test
    fun `max test week and block reps`() {
        val p = seed.program()!!
        val bench = seed.progression("bench")!!
        assertEquals(12, bench.steps.size)
        val last = bench.step(Prescriber.stepIndex(p, 4, 3))!!
        assertTrue(last.test); assertEquals(10.0, last.rpe); assertEquals(2, last.reps)
        assertEquals(1, bench.step(Prescriber.stepIndex(p, 1, 3))!!.topReps)
        assertEquals(3, seed.progression("type2")!!.step(Prescriber.stepIndex(p, 3, 2))!!.sets)
        assertEquals(11, seed.progression("type2")!!.step(Prescriber.stepIndex(p, 3, 2))!!.reps)
        val d = seed.copy(cycle = CycleState(4, 3))
        val s = Prescriber.session(d, p.days[0], Mode.NORMAL, mon, 0L, "x")
        assertEquals(SetKind.TEST, s.entries.first { it.exerciseId == "bench" }.sets[0].kind)
    }

    @Test
    fun `step text round trips`() {
        for (t in listOf("3x4 @7 top3", "3x10 @9", "3x1 @10 top1 test", "3xF", "3x45s @8", "2x8 @8.5")) {
            assertEquals(t, StepFormat.format(StepFormat.parse(t)!!))
        }
        assertNull(StepFormat.parse("hello"))
        assertEquals(Triple(75.0, 8, 8.5), SetText.parse("75x8 @8.5"))
        assertEquals(Triple(245.0, 4, 10.0), SetText.parse("245x4"))
    }

    // ---------- Weekly minimum ----------

    private fun done(d: WorkoutData, date: String, template: String = "push"): WorkoutData {
        val s = Session(
            id = Actions.newId(), name = template, templateId = template, date = date, startedAt = LocalDate.parse(date).toEpochDay() * 86_400_000L,
            endedAt = LocalDate.parse(date).toEpochDay() * 86_400_000L + 3_600_000L,
            entries = listOf(Entry("bench", listOf(SetLog(weight = 225.0, reps = 4, done = true)))),
        )
        return d.copy(sessions = d.sessions + s)
    }

    @Test
    fun `thursday with nothing done is tight`() {
        val w = Week.status(seed, LocalDate.parse("2026-10-29"))
        assertEquals(4, w.daysLeft) // Thu Fri Sat Sun
        assertEquals(3, w.needed)
        assertEquals(1, w.slack)
        assertEquals(Risk.TIGHT, w.risk)
        assertEquals(6, w.target)
        assertEquals(Risk.MUST_TODAY, Week.status(seed, LocalDate.parse("2026-10-30")).risk)
        assertEquals(Risk.MISSED, Week.status(seed, LocalDate.parse("2026-10-31")).risk)
    }

    @Test
    fun `training today removes today from days left`() {
        val d = done(done(seed, "2026-10-26"), "2026-10-29")
        val w = Week.status(d, LocalDate.parse("2026-10-29"))
        assertEquals(2, w.done); assertTrue(w.trainedToday); assertEquals(3, w.daysLeft); assertEquals(Risk.ON_TRACK, w.risk)
        val d3 = done(d, "2026-10-30")
        assertEquals(Risk.MET, Week.status(d3, LocalDate.parse("2026-10-30")).risk)
    }

    // ---------- Cycle ----------

    @Test
    fun `program week advances only after a week that met the minimum`() {
        var d = Cycle.roll(seed, mon).data // first run starts counting
        assertEquals(2, d.cycle.week)
        d = done(done(done(d, "2026-10-26"), "2026-10-27"), "2026-10-28")
        d = Cycle.roll(d, LocalDate.parse("2026-11-02")).data
        assertEquals(3, d.cycle.week)
        // A week with one workout: repeat.
        d = done(d, "2026-11-03")
        val r = Cycle.roll(d, LocalDate.parse("2026-11-09"))
        assertEquals(3, r.data.cycle.week)
        assertTrue(r.notes.isNotEmpty())
        // Rolling twice in the same week changes nothing.
        assertEquals(r.data, Cycle.roll(r.data, LocalDate.parse("2026-11-10")).data)
    }

    @Test
    fun `cycle wraps after block 3 week 4`() {
        val p = seed.program()!!
        assertEquals(CycleState(1, 2), Cycle.next(CycleState(4, 1), p))
        assertEquals(CycleState(1, 1, cyclesDone = 1), Cycle.next(CycleState(4, 3), p))
    }

    @Test
    fun `long break restarts the block`() {
        var d = seed.copy(cycle = CycleState(3, 2, countedThrough = "2026-10-19"))
        d = done(d, "2026-10-20")
        d = Cycle.roll(d, LocalDate.parse("2026-11-09")).data
        assertEquals(1, d.cycle.week); assertEquals(2, d.cycle.block)
    }

    // ---------- Suggestions ----------

    @Test
    fun `suggests core days first then extra days`() {
        assertEquals("push", Suggest.today(seed, mon).pick!!.template.id)
        var d = done(seed, "2026-10-26", "push")
        assertEquals("pull", Suggest.today(d, LocalDate.parse("2026-10-27")).pick!!.template.id)
        d = done(done(d, "2026-10-27", "pull"), "2026-10-28", "legs")
        assertEquals("chest-back", Suggest.today(d, LocalDate.parse("2026-10-29")).pick!!.template.id)
    }

    @Test
    fun `habit breaker puts the skipped day first`() {
        var d = seed
        for (day in listOf("2026-10-05", "2026-10-12", "2026-10-19", "2026-10-07")) d = done(d, day, "push")
        d = done(d, "2026-10-14", "pull")
        val r = Suggest.today(d, mon)
        assertEquals("legs", r.pick!!.template.id)
        assertTrue(r.pick!!.reason.contains("evening it out"))
        assertNotNull(r.nudge)
        assertNotNull(Suggest.warnFor(d, d.program()!!.days[0], mon))
    }

    // ---------- Modes ----------

    @Test
    fun `short mode trims to the time budget`() {
        val p = seed.program()!!
        val s = Prescriber.session(seed, p.days[1], Mode.SHORT, mon, 0L, "x")
        assertTrue(s.entries.size < p.days[1].exerciseIds.size)
        assertEquals("deadlift", s.entries[0].exerciseId)
        assertTrue(s.entries.all { e -> e.sets.size <= 2 })
        val minutes = s.entries.sumOf { e -> e.sets.sumOf { Prescriber.minutesFor(seed.exercise(e.exerciseId)!!, it) } }
        assertTrue(minutes <= seed.settings.shortMinutes + 7.1)
    }

    @Test
    fun `injured mode skips exercises on the hurt joint`() {
        val d = Actions.addInjury(seed, setOf(Joint.SHOULDER), "tweak", mon)
        val s = Prescriber.session(d, d.program()!!.days[0], Mode.INJURED, mon, 0L, "x")
        assertTrue(s.entries.first { it.exerciseId == "bench" }.skipped)
        assertFalse(s.entries.first { it.exerciseId == "skullcrusher" }.skipped)
        val light = Prescriber.light(d, s.entries.first { it.exerciseId == "machine-fly" }, mon)
        assertEquals(2, light.sets.size); assertEquals(6.0, light.sets[0].targetRpe)
    }

    // ---------- Logging ----------

    @Test
    fun `finishing keeps completed sets and updates e1rm`() {
        var d = Actions.start(seed, "push", Mode.NORMAL, mon, 1000L)
        val bi = d.active!!.entries.indexOfFirst { it.exerciseId == "bench" }
        d = Actions.editSet(d, bi, 0) { it.copy(weight = 255.0, reps = 3, rpe = 8.0) }
        d = Actions.completeSet(d, bi, 0, 2000L)
        d = Actions.completeSet(d, bi, 1, 2100L) // takes the targets
        val (after, s) = Actions.finish(d, 5000L)
        assertNotNull(s)
        assertNull(after.active)
        assertEquals(1, s.entries.size)
        assertEquals(2, s.entries[0].sets.size)
        assertEquals(225.0, s.entries[0].sets[1].weight)
        val tm = Strength.trainingMax(after, "bench", mon)!!
        assertEquals(255.0 / Rpe.pct(3, 8.0), tm, 1e-6)
        assertTrue(Strength.prs(after, s).any { it.exerciseId == "bench" })
        assertEquals(255.0 * 3 + 225.0 * 4, Strength.volume(s), 1e-9)
    }

    @Test
    fun `injured sessions do not change the training max`() {
        var d = Actions.start(seed, "push", Mode.INJURED, mon, 1000L)
        val i = d.active!!.entries.indexOfFirst { !it.skipped && seed.exercise(it.exerciseId)!!.measure == Measure.WEIGHT_REPS }
        val id = d.active!!.entries[i].exerciseId
        val before = Strength.trainingMax(d, id, mon)!!
        d = Actions.editSet(d, i, 0) { it.copy(weight = 999.0, reps = 5) }
        d = Actions.completeSet(d, i, 0, 2000L)
        d = Actions.finish(d, 3000L).first
        assertEquals(before, Strength.trainingMax(d, id, mon)!!, 1e-9)
    }

    @Test
    fun `timed exercise shows best to beat`() {
        var d = Actions.start(seed, "legs", Mode.NORMAL, mon, 1000L)
        val pi = d.active!!.entries.indexOfFirst { it.exerciseId == "plank" }
        d = Actions.editSet(d, pi, 0) { it.copy(seconds = 95) }
        d = Actions.completeSet(d, pi, 0, 2000L)
        d = Actions.finish(d, 3000L).first
        val next = Prescriber.session(d, d.program()!!.days[2], Mode.NORMAL, mon.plusDays(2), 0L, "y")
        assertEquals(95, next.entries.first { it.exerciseId == "plank" }.sets[0].targetSeconds)
    }

    @Test
    fun `workout times can be fixed`() {
        val z = java.time.ZoneId.of("America/Chicago")
        fun at(s: String) = java.time.LocalDateTime.parse(s).atZone(z).toInstant().toEpochMilli()
        var d = Actions.start(seed, "push", Mode.NORMAL, mon, at("2026-10-26T18:00"))
        val id = d.active!!.id
        // Forgot to start: really began yesterday evening.
        d = Actions.setTimes(d, id, at("2026-10-25T17:30"), null, z)
        assertEquals("2026-10-25", d.active!!.date)
        d = Actions.completeSet(d, 0, 0, at("2026-10-25T17:40"))
        // Forgot to finish: saved hours later with the real end.
        d = Actions.finish(d, at("2026-10-25T18:40")).first
        val s = d.sessions.single()
        assertEquals(60L, (s.endedAt!! - s.startedAt) / 60_000)
        // Edit a finished workout; end before start is refused.
        d = Actions.setTimes(d, id, at("2026-10-24T07:00"), at("2026-10-24T08:15"), z)
        assertEquals("2026-10-24", d.sessions.single().date)
        assertEquals(d, Actions.setTimes(d, id, at("2026-10-24T09:00"), at("2026-10-24T08:00"), z))
        assertEquals(java.time.LocalDate.parse("2026-10-24"), Week.trainedDates(d).single())
    }

    @Test
    fun `sync status serializes`() {
        val st = Sync.status(seed, LocalDate.parse("2026-10-29"), 0L)
        assertEquals("TIGHT", st.risk)
        assertEquals("Push", st.suggestedToday)
        val json = WorkoutStore.JSON.encodeToString(SyncStatus.serializer(), st)
        assertTrue(json.contains("\"minPerWeek\":3"))
        assertTrue(json.contains("\"targetPerWeek\":6"))
    }

    @Test
    fun `store round trips`() {
        val f = java.io.File.createTempFile("workout", ".json")
        f.delete()
        val st = WorkoutStore(f) { seed }
        st.update { Actions.addInjury(it, setOf(Joint.KNEE), "", mon) }
        val again = WorkoutStore(f) { error("should load") }
        assertEquals(1, again.current.injuries.size)
        assertEquals(seed.exercises.size, again.current.exercises.size)
    }
}
