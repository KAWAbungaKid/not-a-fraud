package com.maven.workout.core

import java.time.LocalDate

/**
 * Turns a day template into today's sets — the Phone sheet. For each exercise:
 * type → step for this week/block → sets × reps @ RPE → weight = RPE-chart % × training max, rounded.
 */
object Prescriber {

    fun stepIndex(p: Program, week: Int, block: Int): Int =
        (block.coerceIn(1, p.blocks) - 1) * p.weeksPerBlock + (week.coerceIn(1, p.weeksPerBlock) - 1)

    fun stepFor(d: WorkoutData, ex: Exercise, p: Program?, week: Int, block: Int): Step? {
        val prog = d.progression(ex.progressionId) ?: return null
        return prog.step(if (p == null) 0 else stepIndex(p, week, block))
    }

    /** Planned sets for one exercise (no weights entered yet; targets filled). */
    fun plan(d: WorkoutData, ex: Exercise, step: Step?, today: LocalDate): List<SetLog> {
        val tm = if (ex.measure == Measure.WEIGHT_REPS) Strength.trainingMax(d, ex.id, today) else null
        if (step == null) {
            // No type: repeat what was done last time, or three empty sets.
            val last = Strength.lastSets(d, ex.id).filter { it.kind != SetKind.WARMUP && it.kind != SetKind.DROP }
            if (last.isNotEmpty()) return last.map {
                SetLog(kind = it.kind, targetWeight = it.weight, targetReps = it.reps, targetSeconds = it.seconds, targetRpe = it.rpe)
            }
            return List(3) { SetLog() }
        }
        fun w(reps: Int?, rpe: Double?): Double? =
            if (tm == null || reps == null) null else Rpe.round(Rpe.weightFor(tm, reps, rpe), ex.roundTo)

        val out = ArrayList<SetLog>()
        if (step.test) {
            out += SetLog(kind = SetKind.TEST, targetReps = 1, targetRpe = 10.0, targetWeight = tm?.let { Rpe.round(it, ex.roundTo) })
        } else if (step.topReps != null) {
            out += SetLog(kind = SetKind.TOP, targetReps = step.topReps, targetRpe = step.rpe, targetWeight = w(step.topReps, step.rpe))
        }
        val beat = if (step.reps == null && ex.measure != Measure.WEIGHT_REPS) Strength.bestEffort(d, ex.id) else null
        repeat(step.sets) {
            out += SetLog(
                kind = SetKind.WORK,
                targetReps = step.reps ?: if (ex.measure == Measure.REPS) beat else null,
                targetSeconds = step.seconds ?: if (ex.measure == Measure.TIME) beat else null,
                targetRpe = step.rpe,
                targetWeight = w(step.reps, step.rpe),
            )
        }
        return out
    }

    /**
     * A whole workout. [mode]: SHORT keeps the first exercises that fit in [Settings.shortMinutes] with at most
     * two working sets each; INJURED skips exercises that load an injured joint (they can be put back as light sets).
     */
    fun session(
        d: WorkoutData, template: DayTemplate?, mode: Mode, today: LocalDate, now: Long, id: String,
        name: String = template?.name ?: "Workout",
    ): Session {
        val p = d.program()
        val cyc = d.cycle
        val hurt = Injuries.activeJoints(d)
        var minutesLeft = d.settings.shortMinutes.toDouble()
        val entries = ArrayList<Entry>()
        for ((i, slotId) in (template?.exerciseIds ?: emptyList()).withIndex()) {
            // Either / or: whichever of the slot's options you did last time on this day.
            val options = Options.of(template, slotId)
            val exId = if (options.size > 1) Options.lastPick(d, template!!, slotId) else slotId
            val slot = if (options.size > 1) slotId else null
            val ex = d.exercise(exId) ?: continue
            val step = stepFor(d, ex, p, cyc.week, cyc.block)
            var sets = plan(d, ex, step, today)
            if (mode == Mode.INJURED) {
                val hits = ex.joints.intersect(hurt)
                if (hits.isNotEmpty()) {
                    entries += Entry(ex.id, sets, skipped = true, skipReason = hits.joinToString { it.label.lowercase() }, slot = slot)
                    continue
                }
            }
            if (mode == Mode.SHORT) {
                // Drop the top set and cap working sets at 2; keep the first exercise no matter what.
                sets = sets.filter { it.kind == SetKind.WORK || it.kind == SetKind.TEST }.let { s ->
                    val test = s.filter { it.kind == SetKind.TEST }
                    test + s.filter { it.kind == SetKind.WORK }.take(2)
                }
                val cost = sets.sumOf { minutesFor(ex, it) }
                if (i > 0 && cost > minutesLeft) continue
                minutesLeft -= cost
            }
            entries += Entry(ex.id, sets, slot = slot)
        }
        return Session(
            id = id, name = name, templateId = template?.id, programId = p?.id,
            week = cyc.week, block = cyc.block, mode = mode, date = today.toString(), startedAt = now, entries = entries,
        )
    }

    /** Light version of a skipped exercise for injured days: 2 sets at RPE 6, same reps. */
    fun light(d: WorkoutData, entry: Entry, today: LocalDate): Entry {
        val ex = d.exercise(entry.exerciseId) ?: return entry.copy(skipped = false)
        val tm = Strength.trainingMax(d, ex.id, today)
        val reps = entry.sets.firstOrNull { it.kind == SetKind.WORK }?.targetReps ?: 10
        val w = tm?.let { Rpe.round(Rpe.weightFor(it, reps, 6.0), ex.roundTo) }
        return entry.copy(skipped = false, sets = List(2) { SetLog(targetReps = reps, targetRpe = 6.0, targetWeight = w) })
    }

    /** Rough minutes a set takes including rest — used only by short mode. */
    fun minutesFor(ex: Exercise, s: SetLog): Double = when {
        ex.measure == Measure.TIME -> 1.5
        (s.targetReps ?: 8) <= 5 -> 3.5
        else -> 2.25
    }

    /** One-line summary of a step for cards: "3×4 @7 · top 3". */
    fun describe(step: Step?, measure: Measure): String {
        if (step == null) return "log freely"
        val body = when {
            step.seconds != null -> "${step.sets}×${Fmt.secs(step.seconds)}"
            step.reps != null -> "${step.sets}×${step.reps}"
            measure == Measure.TIME -> "${step.sets}× hold to failure"
            else -> "${step.sets}× to failure"
        }
        val rpe = step.rpe?.let { " @${Rpe.fmt(it)}" } ?: ""
        val extra = when {
            step.test -> " · max test"
            step.topReps != null -> " · top ${step.topReps}"
            else -> ""
        }
        return body + rpe + extra
    }
}

object Injuries {
    fun activeJoints(d: WorkoutData): Set<Joint> = d.injuries.filter { it.active }.flatMap { it.joints }.toSet()
}
