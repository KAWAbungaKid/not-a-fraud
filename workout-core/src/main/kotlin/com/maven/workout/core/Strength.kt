package com.maven.workout.core

import java.time.LocalDate

/** One estimated-1RM data point: the best set of a workout (or a hand-typed max). */
data class E1rmPoint(
    val date: LocalDate,
    val e1rm: Double,
    val weight: Double,
    val reps: Int,
    val rpe: Double?,
    val sessionId: String?,
    /** Logged while in injured mode: shown, but not used to program weights. */
    val injured: Boolean = false,
)

/**
 * Estimated 1RMs. The sheet kept one number per lift, overwritten by hand. Here every workout produces a
 * point, and the number used to program weights (the "training max") is the best e1RM in the last
 * [Settings.maxWindowDays] days — so a bad day doesn't drop your weights, but a long layoff does.
 */
object Strength {
    fun history(d: WorkoutData, exerciseId: String): List<E1rmPoint> {
        val pts = ArrayList<E1rmPoint>()
        for (m in d.maxes) if (m.exerciseId == exerciseId) {
            val e = Rpe.e1rm(m.weight, m.reps, m.rpe) ?: continue
            pts += E1rmPoint(LocalDate.parse(m.date), e, m.weight, m.reps, m.rpe, null)
        }
        for (s in d.finished()) {
            best(s, exerciseId)?.let { pts += it }
        }
        return pts.sortedBy { it.date }
    }

    /** The best e1RM set of [exerciseId] in one session. */
    fun best(s: Session, exerciseId: String): E1rmPoint? {
        var top: E1rmPoint? = null
        for (en in s.entries) if (en.exerciseId == exerciseId && !en.skipped) for (set in en.sets) {
            if (!set.done || set.kind == SetKind.DROP) continue
            val w = set.weight ?: continue
            val r = set.reps ?: continue
            val e = Rpe.e1rm(w, r, set.rpe) ?: continue
            if (top == null || e > top.e1rm) top = E1rmPoint(LocalDate.parse(s.date), e, w, r, set.rpe, s.id, s.mode == Mode.INJURED)
        }
        return top
    }

    fun trainingMax(d: WorkoutData, exerciseId: String, today: LocalDate): Double? {
        val pts = history(d, exerciseId).filter { !it.injured }
        if (pts.isEmpty()) return null
        val from = today.minusDays(d.settings.maxWindowDays.toLong())
        val recent = pts.filter { !it.date.isBefore(from) && !it.date.isAfter(today) }
        return (recent.ifEmpty { listOf(pts.last()) }).maxOf { it.e1rm }
    }

    /** Best time / reps ever for timed and bodyweight exercises (for "beat it"). */
    fun bestEffort(d: WorkoutData, exerciseId: String, excludeSession: String? = null): Int? {
        val ex = d.exercise(exerciseId) ?: return null
        var best: Int? = null
        for (s in d.finished()) if (s.id != excludeSession) for (en in s.entries) if (en.exerciseId == exerciseId) for (set in en.sets) {
            if (!set.done) continue
            val v = when (ex.measure) { Measure.TIME -> set.seconds; Measure.REPS -> set.reps; else -> null } ?: continue
            if (best == null || v > best) best = v
        }
        return best
    }

    /** Last time this exercise was done: its completed sets. */
    fun lastSets(d: WorkoutData, exerciseId: String, excludeSession: String? = null): List<SetLog> {
        for (s in d.finished().sortedByDescending { it.startedAt }) if (s.id != excludeSession) {
            val sets = s.entries.filter { it.exerciseId == exerciseId }.flatMap { it.sets }.filter { it.done }
            if (sets.isNotEmpty()) return sets
        }
        return emptyList()
    }

    data class Pr(val exerciseId: String, val label: String)

    /** Personal records set in [s] compared with everything before it. */
    fun prs(d: WorkoutData, s: Session): List<Pr> {
        val before = d.copy(sessions = d.sessions.filter { it.id != s.id && it.startedAt < s.startedAt })
        val out = ArrayList<Pr>()
        for (exId in s.entries.map { it.exerciseId }.distinct()) {
            val ex = d.exercise(exId) ?: continue
            when (ex.measure) {
                Measure.WEIGHT_REPS -> {
                    val now = best(s, exId) ?: continue
                    val prev = history(before, exId).maxOfOrNull { it.e1rm }
                    if (prev == null || now.e1rm > prev + 0.5) out += Pr(exId, "${ex.name}: e1RM ${Rpe.fmt(Rpe.round(now.e1rm, 1.0))}")
                }
                Measure.TIME, Measure.REPS -> {
                    val now = s.entries.filter { it.exerciseId == exId }.flatMap { it.sets }.filter { it.done }
                        .mapNotNull { if (ex.measure == Measure.TIME) it.seconds else it.reps }.maxOrNull() ?: continue
                    val prev = bestEffort(before, exId)
                    if (prev == null || now > prev) out += Pr(exId, "${ex.name}: ${if (ex.measure == Measure.TIME) Fmt.secs(now) else "$now reps"}")
                }
            }
        }
        return out
    }

    /** Total weight × reps of completed sets. */
    fun volume(s: Session): Double = s.entries.filter { !it.skipped }.sumOf { e ->
        e.sets.filter { it.done }.sumOf { (it.weight ?: 0.0) * (it.reps ?: 0) }
    }
}

object Fmt {
    fun secs(s: Int): String = if (s >= 60) "%d:%02d".format(s / 60, s % 60) else "${s}s"
}
