package com.maven.workout.core

import java.time.LocalDate
import java.util.UUID

/** Every change to the data, as pure functions (the UI calls these through the store). */
object Actions {
    fun newId(): String = UUID.randomUUID().toString().take(8)

    fun slug(name: String, taken: Set<String>): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "x" }
        var id = base; var n = 2
        while (id in taken) id = "$base-${n++}"
        return id
    }

    // ---- Workout in progress ----

    fun start(d: WorkoutData, templateId: String?, mode: Mode, today: LocalDate, now: Long): WorkoutData {
        val t = d.program()?.days?.firstOrNull { it.id == templateId }
        val s = Prescriber.session(d, t, mode, today, now, newId(), name = t?.name ?: "Workout")
        return d.copy(active = s)
    }

    /** Switch normal / short / injured. Rebuilds the plan, keeping sets already completed. */
    fun changeMode(d: WorkoutData, mode: Mode, today: LocalDate): WorkoutData {
        val a = d.active ?: return d
        val t = d.program(a.programId ?: d.activeProgramId)?.days?.firstOrNull { it.id == a.templateId }
        val fresh = Prescriber.session(d, t, mode, today, a.startedAt, a.id, a.name)
        val done = a.entries.filter { e -> e.sets.any { it.done } }
        val merged = fresh.entries.map { f -> done.firstOrNull { it.exerciseId == f.exerciseId } ?: f } +
            done.filter { e -> fresh.entries.none { it.exerciseId == e.exerciseId } } +
            a.entries.filter { e -> e.sets.none { it.done } && t?.exerciseIds?.contains(e.exerciseId) != true }
        return d.copy(active = a.copy(mode = mode, entries = merged))
    }

    private fun editActive(d: WorkoutData, f: (Session) -> Session) = d.active?.let { d.copy(active = f(it)) } ?: d

    private fun editEntry(d: WorkoutData, ei: Int, f: (Entry) -> Entry) = editActive(d) { s ->
        if (ei !in s.entries.indices) s else s.copy(entries = s.entries.mapIndexed { i, e -> if (i == ei) f(e) else e })
    }

    fun editSet(d: WorkoutData, ei: Int, si: Int, f: (SetLog) -> SetLog) = editEntry(d, ei) { e ->
        if (si !in e.sets.indices) e else e.copy(sets = e.sets.mapIndexed { i, s -> if (i == si) f(s) else s })
    }

    /** Tick a set. Empty fields (weight, reps, time, RPE) take the target values — ticking means "did what was prescribed". */
    fun completeSet(d: WorkoutData, ei: Int, si: Int, now: Long): WorkoutData = editSet(d, ei, si) { s ->
        if (s.done) s.copy(done = false, doneAt = null)
        else s.copy(
            done = true, doneAt = now,
            weight = s.weight ?: s.targetWeight,
            reps = s.reps ?: s.targetReps,
            seconds = s.seconds ?: s.targetSeconds,
            rpe = s.rpe ?: s.targetRpe,
        )
    }

    fun addSet(d: WorkoutData, ei: Int) = editEntry(d, ei) { e ->
        val last = e.sets.lastOrNull()
        val new = if (last == null) SetLog() else SetLog(
            kind = if (last.kind == SetKind.WARMUP) SetKind.WARMUP else SetKind.WORK,
            targetWeight = last.weight ?: last.targetWeight, targetReps = last.reps ?: last.targetReps,
            targetSeconds = last.targetSeconds, targetRpe = last.targetRpe,
        )
        e.copy(sets = e.sets + new)
    }

    fun removeSet(d: WorkoutData, ei: Int, si: Int) = editEntry(d, ei) { e -> e.copy(sets = e.sets.filterIndexed { i, _ -> i != si }) }

    fun addExercise(d: WorkoutData, exId: String, today: LocalDate): WorkoutData = editActive(d) { s ->
        val ex = d.exercise(exId) ?: return@editActive s
        val step = Prescriber.stepFor(d, ex, d.program(), s.week, s.block)
        s.copy(entries = s.entries + Entry(ex.id, Prescriber.plan(d, ex, step, today)))
    }

    fun removeEntry(d: WorkoutData, ei: Int) = editActive(d) { s -> s.copy(entries = s.entries.filterIndexed { i, _ -> i != ei }) }

    fun moveEntry(d: WorkoutData, ei: Int, by: Int) = editActive(d) { s ->
        val j = ei + by
        if (ei !in s.entries.indices || j !in s.entries.indices) s
        else s.copy(entries = s.entries.toMutableList().also { val t = it[ei]; it[ei] = it[j]; it[j] = t })
    }

    /** Put an injured-mode skip back as light sets, or skip an exercise by hand. */
    fun toggleSkip(d: WorkoutData, ei: Int, today: LocalDate) = editEntry(d, ei) { e ->
        if (e.skipped) Prescriber.light(d, e, today) else e.copy(skipped = true, skipReason = "skipped")
    }

    fun setNote(d: WorkoutData, note: String) = editActive(d) { it.copy(note = note) }

    /** Saves the workout: keeps completed sets only. Returns the data and the saved session (null if nothing was done). */
    fun finish(d: WorkoutData, now: Long): Pair<WorkoutData, Session?> {
        val a = d.active ?: return d to null
        val entries = a.entries.filter { !it.skipped }.map { e -> e.copy(sets = e.sets.filter { it.done }) }.filter { it.sets.isNotEmpty() }
        // Editing a finished workout: it keeps its own times and replaces itself.
        if (a.reopenedEnd != null) {
            val s = a.copy(endedAt = a.reopenedEnd, reopenedEnd = null, entries = entries)
            val rest = d.sessions.filter { it.id != a.id }
            return if (entries.isEmpty()) d.copy(active = null, sessions = rest) to null else d.copy(active = null, sessions = rest + s) to s
        }
        if (entries.isEmpty()) return d.copy(active = null) to null
        val s = a.copy(endedAt = now, entries = entries)
        return d.copy(active = null, sessions = d.sessions + s) to s
    }

    /** Edit a finished workout's sets: it opens like a running one; Finish saves it back, Discard leaves it as it was. */
    fun reopen(d: WorkoutData, id: String): WorkoutData {
        if (d.active != null) return d
        val s = d.sessions.firstOrNull { it.id == id && it.endedAt != null } ?: return d
        return d.copy(active = s.copy(reopenedEnd = s.endedAt, endedAt = null, entries = s.entries.map { e -> e.copy(sets = e.sets.map { it.copy(done = true) }) }))
    }

    /** Superset: join entry [ei] with the next one (or leave the group). */
    fun toggleSuperset(d: WorkoutData, ei: Int) = editActive(d) { s ->
        val e = s.entries.getOrNull(ei) ?: return@editActive s
        val next = s.entries.getOrNull(ei + 1)
        if (e.superset != null && next?.superset == e.superset) {
            // Leaving: this one and the next part ways.
            s.copy(entries = s.entries.mapIndexed { i, x -> if (i == ei) x.copy(superset = null) else x })
        } else {
            if (next == null) return@editActive s
            val g = e.superset ?: next.superset ?: ((s.entries.mapNotNull { it.superset }.maxOrNull() ?: 0) + 1)
            s.copy(entries = s.entries.mapIndexed { i, x -> if (i == ei || i == ei + 1) x.copy(superset = g) else x })
        }
    }

    /** Warm-up sets before the first working set (bar, ~40 %, ~60 %, ~80 %), skipped if it already has some. */
    fun addWarmups(d: WorkoutData, ei: Int) = editEntry(d, ei) { e ->
        if (e.sets.any { it.kind == SetKind.WARMUP }) return@editEntry e
        val top = e.sets.firstOrNull { it.kind != SetKind.WARMUP }?.let { it.weight ?: it.targetWeight } ?: return@editEntry e
        val ex = d.exercise(e.exerciseId)
        val warm = Plates.warmups(top, d.settings.barWeight, ex?.roundTo ?: 5.0).map { (w, r) -> SetLog(kind = SetKind.WARMUP, targetWeight = w, targetReps = r) }
        e.copy(sets = warm + e.sets)
    }

    fun setSportDay(d: WorkoutData, date: String, sport: String?) =
        d.copy(sportDays = (if (sport.isNullOrBlank()) d.sportDays - date else d.sportDays + (date to sport)).filterKeys { it >= java.time.LocalDate.now().minusDays(7).toString() })

    fun discard(d: WorkoutData) = d.copy(active = null)

    fun deleteSession(d: WorkoutData, id: String) = d.copy(sessions = d.sessions.filter { it.id != id })

    /**
     * Fix the clock on a workout (forgot to start it, or forgot to finish it). Works on the active workout
     * ([end] ignored) or a finished one. The workout's date follows its start time. End before start = no change.
     */
    fun setTimes(d: WorkoutData, id: String, start: Long, end: Long?, zone: java.time.ZoneId): WorkoutData {
        val date = java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate().toString()
        val a = d.active
        if (a != null && a.id == id) return d.copy(active = a.copy(startedAt = start, date = date))
        if (end == null || end <= start) return d
        return d.copy(sessions = d.sessions.map { if (it.id == id) it.copy(startedAt = start, endedAt = end, date = date) else it })
    }

    // ---- Library ----

    fun saveExercise(d: WorkoutData, ex: Exercise): WorkoutData =
        if (d.exercises.any { it.id == ex.id }) d.copy(exercises = d.exercises.map { if (it.id == ex.id) ex else it })
        else d.copy(exercises = d.exercises + ex)

    fun newExercise(d: WorkoutData, name: String): Exercise =
        Exercise(id = slug(name, d.exercises.map { it.id }.toSet()), name = name.trim())

    fun addMax(d: WorkoutData, exId: String, date: LocalDate, weight: Double, reps: Int, rpe: Double) =
        d.copy(maxes = d.maxes + MaxEntry(exId, date.toString(), weight, reps, rpe))

    fun saveProgression(d: WorkoutData, p: Progression): WorkoutData =
        if (d.progressions.any { it.id == p.id }) d.copy(progressions = d.progressions.map { if (it.id == p.id) p else it })
        else d.copy(progressions = d.progressions + p)

    fun deleteProgression(d: WorkoutData, id: String) = d.copy(
        progressions = d.progressions.filter { it.id != id },
        exercises = d.exercises.map { if (it.progressionId == id) it.copy(progressionId = null) else it },
    )

    fun saveProgram(d: WorkoutData, p: Program): WorkoutData =
        if (d.programs.any { it.id == p.id }) d.copy(programs = d.programs.map { if (it.id == p.id) p else it })
        else d.copy(programs = d.programs + p)

    fun duplicateProgram(d: WorkoutData, id: String): WorkoutData {
        val p = d.program(id) ?: return d
        val nid = slug(p.name + " copy", d.programs.map { it.id }.toSet())
        return d.copy(programs = d.programs + p.copy(id = nid, name = p.name + " (copy)"))
    }

    fun deleteProgram(d: WorkoutData, id: String): WorkoutData {
        if (d.programs.size <= 1) return d
        val left = d.programs.filter { it.id != id }
        return d.copy(programs = left, activeProgramId = if (d.activeProgramId == id) left.first().id else d.activeProgramId)
    }

    fun activate(d: WorkoutData, id: String) = d.copy(activeProgramId = id, cycle = d.cycle.copy(week = 1, block = 1))

    fun setCycle(d: WorkoutData, week: Int, block: Int): WorkoutData {
        val p = d.program() ?: return d
        return d.copy(cycle = d.cycle.copy(week = week.coerceIn(1, p.weeksPerBlock), block = block.coerceIn(1, p.blocks)))
    }

    // ---- Injuries ----

    fun addInjury(d: WorkoutData, joints: Set<Joint>, note: String, today: LocalDate) =
        if (joints.isEmpty()) d else d.copy(injuries = d.injuries + Injury(newId(), joints, note, today.toString()))

    fun healInjury(d: WorkoutData, id: String, today: LocalDate) =
        d.copy(injuries = d.injuries.map { if (it.id == id) it.copy(until = today.toString()) else it })

    fun settings(d: WorkoutData, f: (Settings) -> Settings) = d.copy(settings = f(d.settings))
}
