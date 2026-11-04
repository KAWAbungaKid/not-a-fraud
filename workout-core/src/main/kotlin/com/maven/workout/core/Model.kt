package com.maven.workout.core

import kotlinx.serialization.Serializable

/**
 * Everything the workout app stores, as one JSON file (same pattern as Not a Fraud).
 * Dates are ISO strings ("2026-10-26"), timestamps are epoch millis.
 */
@Serializable
data class WorkoutData(
    val version: Int = Migration.CURRENT,
    val exercises: List<Exercise> = emptyList(),
    val progressions: List<Progression> = emptyList(),
    val programs: List<Program> = emptyList(),
    val activeProgramId: String = "",
    val cycle: CycleState = CycleState(),
    val sessions: List<Session> = emptyList(),
    /** Maxes typed in by hand ("245x4"), the spreadsheet's Maxes sheet. Logged sets add to these. */
    val maxes: List<MaxEntry> = emptyList(),
    val injuries: List<Injury> = emptyList(),
    /** The workout in progress, if any. Survives the app being killed. */
    val active: Session? = null,
    val settings: Settings = Settings(),
    /** From Not a Fraud: date → a sport planned that day ("Climbing"). The day before spares shoulders & arms. */
    val sportDays: Map<String, String> = emptyMap(),
) {
    fun exercise(id: String) = exercises.firstOrNull { it.id == id }
    fun progression(id: String?) = id?.let { p -> progressions.firstOrNull { it.id == p } }
    fun program(id: String = activeProgramId) = programs.firstOrNull { it.id == id } ?: programs.firstOrNull()
    fun finished() = sessions.filter { it.endedAt != null }
}

/** How a set is measured. */
@Serializable
enum class Measure(val label: String) {
    WEIGHT_REPS("Weight × reps"),
    REPS("Reps (bodyweight)"),
    TIME("Time (hold / to failure)"),
}

@Serializable
enum class Muscle(val label: String) {
    CHEST("Chest"), BACK("Back"), SHOULDERS("Shoulders"), BICEPS("Biceps"), TRICEPS("Triceps"),
    FOREARMS("Forearms / grip"), CORE("Core"), QUADS("Quads"), HAMSTRINGS("Hamstrings"),
    GLUTES("Glutes"), CALVES("Calves"), SHINS("Shins"), FULL_BODY("Full body"),
    CARDIO("Cardio"), MOBILITY("Mobility"),
}

/** Joints an exercise loads. Injured mode skips (or lightens) exercises that load an injured joint. */
@Serializable
enum class Joint(val label: String) {
    NECK("Neck"), SHOULDER("Shoulder"), ELBOW("Elbow"), WRIST("Wrist"),
    LOWER_BACK("Lower back"), HIP("Hip"), KNEE("Knee"), ANKLE("Ankle"),
}

@Serializable
data class Exercise(
    val id: String,
    val name: String,
    val measure: Measure = Measure.WEIGHT_REPS,
    /** The "type" from the spreadsheet: which progression table programs this exercise. Null = just log it. */
    val progressionId: String? = null,
    val muscles: Set<Muscle> = emptySet(),
    val joints: Set<Joint> = emptySet(),
    /** Weight rounding (the sheet's MROUND 5). Cables are usually 2.5. */
    val roundTo: Double = 5.0,
    val notes: String = "",
    val archived: Boolean = false,
)

/**
 * A progression "type" (REFERENCE sheet rows RPE<type> / REPS<type>): one [Step] per week of the cycle,
 * laid out block by block: index = (block-1) * weeksPerBlock + (week-1).
 * If the program's cycle is longer than the list, the last step repeats.
 */
@Serializable
data class Progression(
    val id: String,
    val name: String,
    val steps: List<Step> = emptyList(),
    val notes: String = "",
) {
    fun step(index: Int): Step? = if (steps.isEmpty()) null else steps[index.coerceIn(0, steps.lastIndex)]
}

/**
 * One week's prescription for a type. Text form (what the editor shows): "3x4 @7 top3", "3x10 @9",
 * "3x1 @10 top1 test", "3xF" (to failure), "3x45s".
 */
@Serializable
data class Step(
    val sets: Int = 3,
    /** Reps per working set. Null = to failure (AMRAP / hold to failure). */
    val reps: Int? = null,
    /** Target seconds for timed holds. */
    val seconds: Int? = null,
    val rpe: Double? = null,
    /** A heavier top set before the working sets (the sheet's row 27 on main lifts). */
    val topReps: Int? = null,
    /** Max-test week: the top set becomes "work up to a new max". */
    val test: Boolean = false,
)

@Serializable
data class Program(
    val id: String,
    val name: String,
    val weeksPerBlock: Int = 4,
    val blocks: Int = 3,
    /** Workout days in the order the app suggests them. The first [coreDays] are the minimum split. */
    val days: List<DayTemplate> = emptyList(),
    val coreDays: Int = 3,
    val notes: String = "",
) {
    val cycleWeeks get() = weeksPerBlock * blocks
}

@Serializable
data class DayTemplate(
    val id: String,
    val name: String,
    val exerciseIds: List<String> = emptyList(),
)

/** Where we are in the program cycle. Week and block are 1-based. */
@Serializable
data class CycleState(
    val week: Int = 1,
    val block: Int = 1,
    /** Monday (or Sunday) of the last calendar week already counted toward advancing. */
    val countedThrough: String? = null,
    val cyclesDone: Int = 0,
)

@Serializable
enum class Mode(val label: String) {
    NORMAL("Normal"),
    SHORT("Short"),
    INJURED("Injured"),
}

@Serializable
enum class SetKind(val short: String) { WARMUP("W"), TOP("T"), WORK(""), TEST("MAX") }

@Serializable
data class SetLog(
    val kind: SetKind = SetKind.WORK,
    val weight: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val rpe: Double? = null,
    val done: Boolean = false,
    // What the program asked for (shown as placeholders).
    val targetWeight: Double? = null,
    val targetReps: Int? = null,
    val targetSeconds: Int? = null,
    val targetRpe: Double? = null,
    val doneAt: Long? = null,
)

@Serializable
data class Entry(
    val exerciseId: String,
    val sets: List<SetLog> = emptyList(),
    val note: String = "",
    /** Skipped by injured mode (or by hand) — kept so it can be restored. */
    val skipped: Boolean = false,
    val skipReason: String = "",
    /** Entries sharing a group are a superset: rest comes after the last one of the group. */
    val superset: Int? = null,
)

@Serializable
data class Session(
    val id: String,
    val name: String,
    val templateId: String? = null,
    val programId: String? = null,
    val week: Int = 1,
    val block: Int = 1,
    val mode: Mode = Mode.NORMAL,
    val date: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val entries: List<Entry> = emptyList(),
    val note: String = "",
    /** Re-opened finished workout being edited: its original end time (kept on save). */
    val reopenedEnd: Long? = null,
    /** Imported from Hevy (source tag), not logged here. */
    val source: String? = null,
) {
    val doneSets get() = entries.sumOf { e -> e.sets.count { it.done } }
}

@Serializable
data class MaxEntry(
    val exerciseId: String,
    val date: String,
    val weight: Double,
    val reps: Int,
    val rpe: Double = 10.0,
)

@Serializable
data class Injury(
    val id: String,
    val joints: Set<Joint>,
    val note: String = "",
    val since: String,
    val until: String? = null,
) {
    val active get() = until == null
}

@Serializable
enum class Advance(val label: String) {
    /** A program week moves on only after a calendar week where you hit the minimum. */
    EARNED("When I hit my minimum"),
    /** Moves every calendar week regardless. */
    CALENDAR("Every calendar week"),
    /** Only when I change it. */
    MANUAL("Only by hand"),
}

@Serializable
data class Settings(
    val minPerWeek: Int = 3,
    /** How many workouts a full week has. Null = the program's day count. */
    val targetPerWeek: Int? = null,
    val weekStartsMonday: Boolean = true,
    val advance: Advance = Advance.EARNED,
    /** No workouts for this many days → restart the current block at week 1. 0 = never. */
    val reentryAfterDays: Int = 14,
    val shortMinutes: Int = 35,
    val restSec: Int = 120,
    val restHeavySec: Int = 180,
    val reminder: Boolean = true,
    val reminderHour: Int = 16,
    val unit: String = "lb",
    /** Training max = best e1RM in this many days (injured sessions excluded). */
    val maxWindowDays: Int = 42,
    /** Bar weight and the plates you have (per side), for the plate calculator. */
    val barWeight: Double = 45.0,
    val plates: List<Double> = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5),
    /** Write finished workouts to Health Connect (Samsung Health sees them). */
    val writeHealth: Boolean = true,
)
