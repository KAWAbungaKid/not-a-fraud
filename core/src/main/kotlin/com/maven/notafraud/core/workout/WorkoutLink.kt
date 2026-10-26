package com.maven.notafraud.core.workout

import com.maven.notafraud.core.model.AppData
import com.maven.notafraud.core.model.AutoCheck
import com.maven.notafraud.core.model.routineFor
import com.maven.notafraud.core.plan.Time
import kotlinx.serialization.Serializable
import java.time.LocalDate

/**
 * What the workout app tells Not a Fraud (content://com.maven.workout.sync/status, docs/workout/STATE.md § 5).
 * Field names are the contract; everything has a default so a newer workout app can add fields.
 */
@Serializable
data class WorkoutStatus(
    val schema: Int = 1,
    val generatedAt: Long = 0,
    val today: String = "",
    val weekStart: String = "",
    val minPerWeek: Int = 3,
    val targetPerWeek: Int = 6,
    val doneThisWeek: Int = 0,
    val doneDates: List<String> = emptyList(),
    val trainedToday: Boolean = false,
    val daysLeft: Int = 0,
    val needed: Int = 0,
    val slack: Int = 0,
    /** MET, ON_TRACK, TIGHT, MUST_TODAY, MISSED. */
    val risk: String = "ON_TRACK",
    val message: String = "",
    val suggestedToday: String? = null,
    val suggestedReason: String? = null,
    val inProgress: Boolean = false,
    val inProgressName: String? = null,
    val inProgressStartedAt: Long? = null,
    val lastWorkoutDate: String? = null,
    val program: String? = null,
    val week: Int = 0,
    val block: Int = 0,
    val streakWeeks: Int = 0,
    val injured: List<String> = emptyList(),
)

object WorkoutLink {
    const val MET = "MET"; const val ON_TRACK = "ON_TRACK"; const val TIGHT = "TIGHT"; const val MUST_TODAY = "MUST_TODAY"; const val MISSED = "MISSED"

    /** The last status, only if it was made today (risk depends on the day). */
    fun today(d: AppData, now: Long): WorkoutStatus? {
        val s = d.workout ?: return null
        val today = Time.dateOf(now, Time.zone(d.settings.zoneId)).toString()
        return s.takeIf { it.today == today }
    }

    /** Every remaining day needs a workout and today's isn't done or going. */
    fun mustToday(d: AppData, now: Long): Boolean {
        val s = today(d, now) ?: return false
        return s.risk == MUST_TODAY && !s.trainedToday && !s.inProgress
    }

    fun tight(d: AppData, now: Long): Boolean {
        val s = today(d, now) ?: return false
        return s.risk == TIGHT && !s.trainedToday && !s.inProgress
    }

    /** Credits can't open blocked apps until today's workout is started (setting). Emergencies, reply windows, purpose still work. */
    fun creditsPaused(d: AppData, now: Long) = d.settings.workoutPausesCredits && mustToday(d, now)

    fun trainedOn(d: AppData, date: LocalDate): Boolean = d.workout?.doneDates?.contains(date.toString()) == true

    /** New status in: store it, tick the workout routine item if today's is done. */
    fun receive(d: AppData, status: WorkoutStatus, now: Long): AppData {
        var x = d.copy(workout = status)
        val item = x.routineFor(AutoCheck.WORKOUT) ?: return x
        val today = Time.dateOf(now, Time.zone(d.settings.zoneId))
        if (status.trainedToday && status.today == today.toString()) {
            val done = x.routineDays.firstOrNull { it.date == today.toString() }?.done?.containsKey(item.id) == true
            if (!done) x = com.maven.notafraud.core.store.Actions.checkRoutine(x, item.id, now, "Workout app")
        }
        return x
    }
}
