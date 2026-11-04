package com.maven.workout.core

import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * What the workout app tells Not a Fraud (and anything else signed with the same key).
 * Served by the app's ContentProvider as JSON; field names are a contract — add, don't rename.
 * See docs/workout/STATE.md § Sync contract.
 */
@Serializable
data class SyncStatus(
    val schema: Int = 1,
    val generatedAt: Long,
    val today: String,
    val weekStart: String,
    /** Workouts the week must have (the floor). */
    val minPerWeek: Int,
    /** Workouts a full week has (the program's day count unless overridden) — what to plan for. */
    val targetPerWeek: Int,
    val doneThisWeek: Int,
    val doneDates: List<String>,
    val trainedToday: Boolean,
    val daysLeft: Int,
    val needed: Int,
    val slack: Int,
    /** MET, ON_TRACK, TIGHT, MUST_TODAY, MISSED. */
    val risk: String,
    val message: String,
    val suggestedToday: String?,
    val suggestedReason: String?,
    val inProgress: Boolean,
    val inProgressName: String?,
    val inProgressStartedAt: Long?,
    val lastWorkoutDate: String?,
    val program: String?,
    val week: Int,
    val block: Int,
    val streakWeeks: Int,
    val injured: List<String>,
)

@Serializable
data class SyncSession(
    val id: String,
    val date: String,
    val name: String,
    val mode: String,
    val startedAt: Long,
    val endedAt: Long,
    val sets: Int,
    val minutes: Int,
)

object Sync {
    fun status(d: WorkoutData, today: LocalDate, now: Long): SyncStatus {
        val w = Week.status(d, today)
        val sug = Suggest.today(d, today)
        return SyncStatus(
            generatedAt = now,
            today = today.toString(),
            weekStart = w.weekStart.toString(),
            minPerWeek = w.minimum,
            targetPerWeek = w.target,
            doneThisWeek = w.done,
            doneDates = w.doneDates.map { it.toString() },
            trainedToday = w.trainedToday,
            daysLeft = w.daysLeft,
            needed = w.needed,
            slack = w.slack,
            risk = w.risk.name,
            message = w.message(),
            suggestedToday = sug.pick?.template?.name,
            suggestedReason = sug.pick?.reason,
            inProgress = d.active != null && d.active.reopenedEnd == null,
            inProgressName = d.active?.name,
            inProgressStartedAt = d.active?.startedAt,
            lastWorkoutDate = Week.trainedDates(d).maxOrNull()?.toString(),
            program = d.program()?.name,
            week = d.cycle.week,
            block = d.cycle.block,
            streakWeeks = Week.streak(d, today),
            injured = Injuries.activeJoints(d).map { it.name },
        )
    }

    fun sessions(d: WorkoutData, since: LocalDate?): List<SyncSession> = d.finished()
        .filter { since == null || !LocalDate.parse(it.date).isBefore(since) }
        .sortedBy { it.startedAt }
        .map { s ->
            SyncSession(
                s.id, s.date, s.name, s.mode.name, s.startedAt, s.endedAt!!, s.doneSets,
                ((s.endedAt - s.startedAt) / 60_000L).toInt(),
            )
        }
}
